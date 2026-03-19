package com.tradingplatform.app.usecase.pairing

import app.cash.turbine.test
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.domain.exception.PairingTimeoutException
import com.tradingplatform.app.domain.model.DevicePairingInfo
import com.tradingplatform.app.domain.model.PairingSession
import com.tradingplatform.app.domain.model.PairingStatus
import com.tradingplatform.app.domain.repository.PairingRepository
import com.tradingplatform.app.domain.usecase.pairing.ConfirmPairingUseCase
import com.tradingplatform.app.domain.usecase.pairing.ParseVpsQrUseCase
import com.tradingplatform.app.domain.usecase.pairing.ScanDeviceQrUseCase
import com.tradingplatform.app.domain.usecase.pairing.SendPinToDeviceUseCase
import com.tradingplatform.app.domain.usecase.pairing.StoreDevicePairingResultUseCase
import com.tradingplatform.app.ui.screens.pairing.PairingStep
import com.tradingplatform.app.ui.screens.pairing.PairingViewModel
import com.tradingplatform.app.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import kotlin.test.assertIs

/**
 * Integration tests for the full pairing flow:
 * ParseVpsQr → ScanDeviceQr → SendPin → ConfirmPairing
 *
 * These tests cover sequences that span multiple UseCases and the ViewModel state machine,
 * complementing the unit tests for each UseCase in isolation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairingFlowIntegrationTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // ── Real UseCases (no repository dependency) ──────────────────────────────
    private val parseVpsQrUseCase = ParseVpsQrUseCase()
    private val scanDeviceQrUseCase = ScanDeviceQrUseCase()

    // ── Mocked components with repository dependency ──────────────────────────
    private val pairingRepository = mockk<PairingRepository>()
    private val dataStore = mockk<EncryptedDataStore>(relaxed = true)

    private lateinit var sendPinToDeviceUseCase: SendPinToDeviceUseCase
    private lateinit var confirmPairingUseCase: ConfirmPairingUseCase
    private lateinit var storeDevicePairingResultUseCase: StoreDevicePairingResultUseCase
    private lateinit var viewModel: PairingViewModel

    // ── Test fixtures ─────────────────────────────────────────────────────────

    /**
     * Valid VPS QR JSON — all fields present.
     * session_pin and local_token are never asserted in log output — [REDACTED].
     */
    private val validVpsQrRaw = """
        {
          "session_id": "session-uuid-abc",
          "session_pin": "472938",
          "device_wg_ip": "10.42.0.5",
          "local_token": "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899",
          "nonce": "deadbeef0123456789abcdef0123456789abcdef0123456789abcdef01234567"
        }
    """.trimIndent()

    /**
     * Valid Radxa QR URI — scheme pairing://radxa, port 8099, 44-char pubkey.
     */
    private val validDeviceQrRaw =
        "pairing://radxa?id=device-id-456&pub=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=&ip=192.168.1.42&port=8099"

    private val expectedSession = PairingSession(
        sessionId = "session-uuid-abc",
        sessionPin = "472938",
        deviceWgIp = "10.42.0.5",
        localToken = "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899",
        nonce = "deadbeef0123456789abcdef0123456789abcdef0123456789abcdef01234567",
    )

    private val expectedDevice = DevicePairingInfo(
        deviceId = "device-id-456",
        wgPubkey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        localIp = "192.168.1.42",
        port = 8099,
    )

    @Before
    fun setUp() {
        sendPinToDeviceUseCase = SendPinToDeviceUseCase(pairingRepository)
        confirmPairingUseCase = ConfirmPairingUseCase(pairingRepository)
        storeDevicePairingResultUseCase = StoreDevicePairingResultUseCase(dataStore)

        viewModel = PairingViewModel(
            parseVpsQrUseCase = parseVpsQrUseCase,
            scanDeviceQrUseCase = scanDeviceQrUseCase,
            sendPinToDeviceUseCase = sendPinToDeviceUseCase,
            confirmPairingUseCase = confirmPairingUseCase,
            storeDevicePairingResultUseCase = storeDevicePairingResultUseCase,
        )
    }

    // ── UseCase layer — sequential invocation ─────────────────────────────────

    @Test
    fun `full pairing flow succeeds - both QRs parsed then pin sent and confirmed`() = runTest {
        // Arrange
        coEvery {
            pairingRepository.sendPin(
                deviceIp = expectedDevice.localIp,
                devicePort = expectedDevice.port,
                sessionId = expectedSession.sessionId,
                sessionPin = expectedSession.sessionPin,
                localToken = expectedSession.localToken,
                nonce = expectedSession.nonce,
                radxaWgPubkey = expectedDevice.wgPubkey,
            )
        } returns Result.success(Unit)

        every {
            pairingRepository.pollStatus(
                deviceIp = expectedDevice.localIp,
                devicePort = expectedDevice.port,
                sessionId = expectedSession.sessionId,
            )
        } returns flowOf(PairingStatus.PENDING, PairingStatus.PAIRED)

        // Act — parse both QR codes
        val sessionResult = parseVpsQrUseCase(validVpsQrRaw)
        val deviceResult = scanDeviceQrUseCase(validDeviceQrRaw)

        assertTrue("VPS QR parse must succeed", sessionResult.isSuccess)
        assertTrue("Device QR parse must succeed", deviceResult.isSuccess)

        val session = sessionResult.getOrThrow()
        val device = deviceResult.getOrThrow()

        assertEquals(expectedSession.sessionId, session.sessionId)
        assertEquals(expectedDevice.deviceId, device.deviceId)
        assertEquals(expectedDevice.localIp, device.localIp)

        // Act — send PIN
        val sendResult = sendPinToDeviceUseCase(
            deviceIp = device.localIp,
            devicePort = device.port,
            sessionId = session.sessionId,
            sessionPin = session.sessionPin,
            localToken = session.localToken,
            nonce = session.nonce,
            radxaWgPubkey = device.wgPubkey,
        )
        assertTrue("sendPin must succeed", sendResult.isSuccess)

        // Act — confirm pairing
        val confirmResult = confirmPairingUseCase(
            deviceIp = device.localIp,
            devicePort = device.port,
            sessionId = session.sessionId,
        )

        // Assert
        assertTrue("confirmPairing must succeed", confirmResult.isSuccess)
        assertEquals(PairingStatus.PAIRED, confirmResult.getOrThrow())
    }

    @Test
    fun `pairing times out — ConfirmPairingUseCase returns PairingTimeoutException`() = runTest {
        // Arrange — pollStatus never emits a terminal status
        coEvery {
            pairingRepository.sendPin(any(), any(), any(), any(), any(), any(), any())
        } returns Result.success(Unit)

        every {
            pairingRepository.pollStatus(any(), any(), any())
        } returns flow {
            // Emit PENDING indefinitely — ConfirmPairingUseCase will time out at 120s
            while (true) {
                emit(PairingStatus.PENDING)
                // No delay needed — the timeout in ConfirmPairingUseCase wraps the entire
                // withTimeout block; the test uses advanceUntilIdle via UnconfinedTestDispatcher.
                // The use case uses withTimeout(120_000L) which is virtual-time-aware in runTest.
            }
        }

        // Act — confirm pairing (expects timeout)
        val confirmResult = confirmPairingUseCase(
            deviceIp = expectedDevice.localIp,
            devicePort = expectedDevice.port,
            sessionId = expectedSession.sessionId,
        )

        // Assert — must fail with PairingTimeoutException (wraps TimeoutCancellationException)
        assertTrue("Result must be failure on timeout", confirmResult.isFailure)
        assertTrue(
            "Exception must be PairingTimeoutException, was: ${confirmResult.exceptionOrNull()?.javaClass?.simpleName}",
            confirmResult.exceptionOrNull() is PairingTimeoutException,
        )
    }

    @Test
    fun `pairing fails when sendPin returns IOException`() = runTest {
        // Arrange
        coEvery {
            pairingRepository.sendPin(any(), any(), any(), any(), any(), any(), any())
        } returns Result.failure(IOException("Radxa unreachable"))

        // Act — parse QRs successfully
        val session = parseVpsQrUseCase(validVpsQrRaw).getOrThrow()
        val device = scanDeviceQrUseCase(validDeviceQrRaw).getOrThrow()

        val sendResult = sendPinToDeviceUseCase(
            deviceIp = device.localIp,
            devicePort = device.port,
            sessionId = session.sessionId,
            sessionPin = session.sessionPin,
            localToken = session.localToken,
            nonce = session.nonce,
            radxaWgPubkey = device.wgPubkey,
        )

        // Assert — flow stops at sendPin failure, confirmPairing must never be called
        assertTrue("sendPin result must be failure", sendResult.isFailure)
        assertTrue(sendResult.exceptionOrNull() is IOException)
    }

    @Test
    fun `pairing fails when device confirms FAILED status`() = runTest {
        // Arrange
        coEvery {
            pairingRepository.sendPin(any(), any(), any(), any(), any(), any(), any())
        } returns Result.success(Unit)

        every {
            pairingRepository.pollStatus(any(), any(), any())
        } returns flowOf(PairingStatus.PENDING, PairingStatus.FAILED)

        // Act
        val session = parseVpsQrUseCase(validVpsQrRaw).getOrThrow()
        val device = scanDeviceQrUseCase(validDeviceQrRaw).getOrThrow()

        sendPinToDeviceUseCase(
            deviceIp = device.localIp,
            devicePort = device.port,
            sessionId = session.sessionId,
            sessionPin = session.sessionPin,
            localToken = session.localToken,
            nonce = session.nonce,
            radxaWgPubkey = device.wgPubkey,
        )

        val confirmResult = confirmPairingUseCase(
            deviceIp = device.localIp,
            devicePort = device.port,
            sessionId = session.sessionId,
        )

        // ConfirmPairingUseCase returns success(FAILED) — not a Result.failure
        assertTrue("confirmPairing must succeed (result wraps FAILED status)", confirmResult.isSuccess)
        assertEquals(PairingStatus.FAILED, confirmResult.getOrThrow())
    }

    // ── ViewModel state machine — full sequence ───────────────────────────────

    @Test
    fun `PairingViewModel transitions through all states correctly - VPS QR first`() = runTest {
        // Arrange
        coEvery {
            pairingRepository.sendPin(any(), any(), any(), any(), any(), any(), any())
        } returns Result.success(Unit)

        every {
            pairingRepository.pollStatus(any(), any(), any())
        } returns flowOf(PairingStatus.PAIRED)

        // Act + Assert via Turbine
        viewModel.step.test {
            // Initial state
            assertIs<PairingStep.Idle>(awaitItem())

            // Scan VPS QR first
            viewModel.onVpsQrScanned(validVpsQrRaw)
            val afterVps = awaitItem()
            assertIs<PairingStep.VpsScanned>(afterVps)
            assertEquals(expectedSession.sessionId, (afterVps as PairingStep.VpsScanned).session.sessionId)

            // Scan Device QR second
            viewModel.onDeviceQrScanned(validDeviceQrRaw)
            val afterDevice = awaitItem()
            assertIs<PairingStep.BothScanned>(afterDevice)
            val both = afterDevice as PairingStep.BothScanned
            assertEquals(expectedSession.sessionId, both.session.sessionId)
            assertEquals(expectedDevice.deviceId, both.device.deviceId)

            // Start pairing
            viewModel.startPairing()

            assertIs<PairingStep.SendingPin>(awaitItem())
            assertIs<PairingStep.WaitingConfirmation>(awaitItem())
            assertIs<PairingStep.Success>(awaitItem())
        }
    }

    @Test
    fun `PairingViewModel transitions through all states correctly - Device QR first`() = runTest {
        // Arrange — order of QR scans is reversed compared to the previous test
        coEvery {
            pairingRepository.sendPin(any(), any(), any(), any(), any(), any(), any())
        } returns Result.success(Unit)

        every {
            pairingRepository.pollStatus(any(), any(), any())
        } returns flowOf(PairingStatus.PAIRED)

        viewModel.step.test {
            assertIs<PairingStep.Idle>(awaitItem())

            // Scan Device QR first
            viewModel.onDeviceQrScanned(validDeviceQrRaw)
            val afterDevice = awaitItem()
            assertIs<PairingStep.DeviceScanned>(afterDevice)
            assertEquals(expectedDevice.deviceId, (afterDevice as PairingStep.DeviceScanned).device.deviceId)

            // Scan VPS QR second
            viewModel.onVpsQrScanned(validVpsQrRaw)
            val afterVps = awaitItem()
            assertIs<PairingStep.BothScanned>(afterVps)
            val both = afterVps as PairingStep.BothScanned
            assertEquals(expectedSession.sessionId, both.session.sessionId)
            assertEquals(expectedDevice.deviceId, both.device.deviceId)

            viewModel.startPairing()

            assertIs<PairingStep.SendingPin>(awaitItem())
            assertIs<PairingStep.WaitingConfirmation>(awaitItem())
            assertIs<PairingStep.Success>(awaitItem())
        }
    }

    @Test
    fun `PairingViewModel goes to Error when sendPin fails - not retryable`() = runTest {
        // Arrange
        coEvery {
            pairingRepository.sendPin(any(), any(), any(), any(), any(), any(), any())
        } returns Result.failure(IOException("LAN unreachable"))

        viewModel.step.test {
            assertIs<PairingStep.Idle>(awaitItem())

            viewModel.onVpsQrScanned(validVpsQrRaw)
            assertIs<PairingStep.VpsScanned>(awaitItem())

            viewModel.onDeviceQrScanned(validDeviceQrRaw)
            assertIs<PairingStep.BothScanned>(awaitItem())

            viewModel.startPairing()
            assertIs<PairingStep.SendingPin>(awaitItem())

            val error = awaitItem()
            assertIs<PairingStep.Error>(error)
            // sendPin errors are not retryable — PIN may have been consumed
            assertTrue(
                "Error after sendPin failure must not be retryable",
                !(error as PairingStep.Error).retryable,
            )
        }
    }

    @Test
    fun `PairingViewModel goes to Error on timeout - message references session expiry`() = runTest {
        // Arrange — ConfirmPairingUseCase returns PairingTimeoutException
        coEvery {
            pairingRepository.sendPin(any(), any(), any(), any(), any(), any(), any())
        } returns Result.success(Unit)

        every {
            pairingRepository.pollStatus(any(), any(), any())
        } returns flow {
            while (true) {
                emit(PairingStatus.PENDING)
            }
        }

        viewModel.step.test {
            assertIs<PairingStep.Idle>(awaitItem())

            viewModel.onVpsQrScanned(validVpsQrRaw)
            assertIs<PairingStep.VpsScanned>(awaitItem())

            viewModel.onDeviceQrScanned(validDeviceQrRaw)
            assertIs<PairingStep.BothScanned>(awaitItem())

            viewModel.startPairing()
            assertIs<PairingStep.SendingPin>(awaitItem())
            assertIs<PairingStep.WaitingConfirmation>(awaitItem())

            val error = awaitItem()
            assertIs<PairingStep.Error>(error)
            assertTrue(
                "Error message must not be blank",
                (error as PairingStep.Error).message.isNotBlank(),
            )
            // Timeout is not retryable — user must restart from VPS admin panel
            assertTrue("Timeout error must not be retryable", !error.retryable)
        }
    }

    @Test
    fun `PairingViewModel invalid VPS QR followed by valid VPS QR succeeds`() = runTest {
        // Arrange — simulate user scanning wrong QR first, then correct one
        coEvery {
            pairingRepository.sendPin(any(), any(), any(), any(), any(), any(), any())
        } returns Result.success(Unit)

        every {
            pairingRepository.pollStatus(any(), any(), any())
        } returns flowOf(PairingStatus.PAIRED)

        viewModel.step.test {
            assertIs<PairingStep.Idle>(awaitItem())

            // First scan: invalid QR → Error
            viewModel.onVpsQrScanned("not-json-at-all")
            assertIs<PairingStep.Error>(awaitItem())

            // User retries → Idle
            viewModel.retry()
            assertIs<PairingStep.Idle>(awaitItem())

            // Second scan: valid QR → VpsScanned
            viewModel.onVpsQrScanned(validVpsQrRaw)
            assertIs<PairingStep.VpsScanned>(awaitItem())

            // Device QR → BothScanned
            viewModel.onDeviceQrScanned(validDeviceQrRaw)
            assertIs<PairingStep.BothScanned>(awaitItem())

            // Proceed to success
            viewModel.startPairing()
            assertIs<PairingStep.SendingPin>(awaitItem())
            assertIs<PairingStep.WaitingConfirmation>(awaitItem())
            assertIs<PairingStep.Success>(awaitItem())
        }
    }

    @Test
    fun `QR parse results carry through to sendPin arguments correctly`() = runTest {
        // Verify that the data from parsed QR codes is passed verbatim to sendPin.
        // This guards against silent field mapping errors between parsing and dispatch.
        var capturedDeviceIp: String? = null
        var capturedDevicePort: Int? = null
        var capturedSessionId: String? = null
        var capturedNonce: String? = null
        var capturedWgPubkey: String? = null

        coEvery {
            pairingRepository.sendPin(any(), any(), any(), any(), any(), any(), any())
        } answers {
            capturedDeviceIp = firstArg()
            capturedDevicePort = secondArg()
            capturedSessionId = thirdArg()
            // arg(3) = sessionPin — captured but not asserted in logs ([REDACTED])
            // arg(4) = localToken — same
            capturedNonce = arg(5)
            capturedWgPubkey = arg(6)
            Result.success(Unit)
        }

        val session = parseVpsQrUseCase(validVpsQrRaw).getOrThrow()
        val device = scanDeviceQrUseCase(validDeviceQrRaw).getOrThrow()

        sendPinToDeviceUseCase(
            deviceIp = device.localIp,
            devicePort = device.port,
            sessionId = session.sessionId,
            sessionPin = session.sessionPin,
            localToken = session.localToken,
            nonce = session.nonce,
            radxaWgPubkey = device.wgPubkey,
        )

        assertEquals(expectedDevice.localIp, capturedDeviceIp)
        assertEquals(8099, capturedDevicePort)
        assertEquals(expectedSession.sessionId, capturedSessionId)
        // nonce and wgPubkey are verified — they are not secret and must map correctly
        assertEquals(expectedSession.nonce, capturedNonce)
        assertEquals(expectedDevice.wgPubkey, capturedWgPubkey)
    }
}
