package com.tradingplatform.app.ui.screens.pairing

import app.cash.turbine.test
import com.tradingplatform.app.domain.model.DevicePairingInfo
import com.tradingplatform.app.domain.model.PairingSession
import com.tradingplatform.app.domain.model.PairingStatus
import com.tradingplatform.app.domain.usecase.pairing.ConfirmPairingUseCase
import com.tradingplatform.app.domain.usecase.pairing.ParseVpsQrUseCase
import com.tradingplatform.app.domain.usecase.pairing.ScanDeviceQrUseCase
import com.tradingplatform.app.domain.usecase.pairing.SendPinToDeviceUseCase
import com.tradingplatform.app.domain.usecase.pairing.UnrecognizedQrException
import com.tradingplatform.app.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import kotlin.test.assertIs
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PairingViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val parseVpsQrUseCase = mockk<ParseVpsQrUseCase>()
    private val scanDeviceQrUseCase = mockk<ScanDeviceQrUseCase>()
    private val sendPinToDeviceUseCase = mockk<SendPinToDeviceUseCase>()
    private val confirmPairingUseCase = mockk<ConfirmPairingUseCase>()

    private lateinit var viewModel: PairingViewModel

    private val fakeSession = PairingSession(
        sessionId = "session-uuid-123",
        sessionPin = "472938", // never assert on this value in logs
        deviceWgIp = "10.42.0.5",
    )

    private val fakeDevice = DevicePairingInfo(
        deviceId = "device-id-456",
        wgPubkey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        localIp = "192.168.1.42",
        port = 8099,
    )

    @Before
    fun setUp() {
        viewModel = PairingViewModel(
            parseVpsQrUseCase = parseVpsQrUseCase,
            scanDeviceQrUseCase = scanDeviceQrUseCase,
            sendPinToDeviceUseCase = sendPinToDeviceUseCase,
            confirmPairingUseCase = confirmPairingUseCase,
        )
    }

    // ── Initial state ─────────────────────────────────────────────────────────

    @Test
    fun `initial step is Idle`() = runTest {
        assertIs<PairingStep.Idle>(viewModel.step.value)
    }

    // ── VPS QR scan transitions ───────────────────────────────────────────────

    @Test
    fun `Idle + valid VPS QR transitions to VpsScanned`() = runTest {
        coEvery { parseVpsQrUseCase(any()) } returns Result.success(fakeSession)

        viewModel.step.test {
            assertIs<PairingStep.Idle>(awaitItem())

            viewModel.onVpsQrScanned("{valid-vps-qr}")

            val next = awaitItem()
            assertIs<PairingStep.VpsScanned>(next)
            assertEquals(fakeSession.sessionId, (next as PairingStep.VpsScanned).session.sessionId)
        }
    }

    @Test
    fun `DeviceScanned + valid VPS QR transitions to BothScanned`() = runTest {
        coEvery { scanDeviceQrUseCase(any()) } returns Result.success(fakeDevice)
        coEvery { parseVpsQrUseCase(any()) } returns Result.success(fakeSession)

        viewModel.step.test {
            assertIs<PairingStep.Idle>(awaitItem())

            viewModel.onDeviceQrScanned("pairing://radxa?...")
            assertIs<PairingStep.DeviceScanned>(awaitItem())

            viewModel.onVpsQrScanned("{valid-vps-qr}")
            val next = awaitItem()
            assertIs<PairingStep.BothScanned>(next)

            val both = next as PairingStep.BothScanned
            assertEquals(fakeSession.sessionId, both.session.sessionId)
            assertEquals(fakeDevice.deviceId, both.device.deviceId)
        }
    }

    // ── Device QR scan transitions ────────────────────────────────────────────

    @Test
    fun `Idle + valid Device QR transitions to DeviceScanned`() = runTest {
        coEvery { scanDeviceQrUseCase(any()) } returns Result.success(fakeDevice)

        viewModel.step.test {
            assertIs<PairingStep.Idle>(awaitItem())

            viewModel.onDeviceQrScanned("pairing://radxa?...")

            val next = awaitItem()
            assertIs<PairingStep.DeviceScanned>(next)
            assertEquals(fakeDevice.deviceId, (next as PairingStep.DeviceScanned).device.deviceId)
        }
    }

    @Test
    fun `VpsScanned + valid Device QR transitions to BothScanned`() = runTest {
        coEvery { parseVpsQrUseCase(any()) } returns Result.success(fakeSession)
        coEvery { scanDeviceQrUseCase(any()) } returns Result.success(fakeDevice)

        viewModel.step.test {
            assertIs<PairingStep.Idle>(awaitItem())

            viewModel.onVpsQrScanned("{valid-vps-qr}")
            assertIs<PairingStep.VpsScanned>(awaitItem())

            viewModel.onDeviceQrScanned("pairing://radxa?...")
            val next = awaitItem()
            assertIs<PairingStep.BothScanned>(next)

            val both = next as PairingStep.BothScanned
            assertEquals(fakeSession.sessionId, both.session.sessionId)
            assertEquals(fakeDevice.deviceId, both.device.deviceId)
        }
    }

    // ── Invalid QR transitions ────────────────────────────────────────────────

    @Test
    fun `invalid VPS QR transitions to Error with retryable true`() = runTest {
        coEvery { parseVpsQrUseCase(any()) } returns Result.failure(UnrecognizedQrException())

        viewModel.step.test {
            assertIs<PairingStep.Idle>(awaitItem())

            viewModel.onVpsQrScanned("not-a-valid-qr")

            val next = awaitItem()
            assertIs<PairingStep.Error>(next)

            val error = next as PairingStep.Error
            assertTrue("Error must be retryable for unrecognized QR", error.retryable)
            assertTrue(error.message.isNotBlank())
        }
    }

    @Test
    fun `invalid Device QR transitions to Error with retryable true`() = runTest {
        coEvery { scanDeviceQrUseCase(any()) } returns Result.failure(UnrecognizedQrException())

        viewModel.step.test {
            assertIs<PairingStep.Idle>(awaitItem())

            viewModel.onDeviceQrScanned("not-a-valid-qr")

            val next = awaitItem()
            assertIs<PairingStep.Error>(next)

            val error = next as PairingStep.Error
            assertTrue("Error must be retryable for unrecognized QR", error.retryable)
        }
    }

    // ── startPairing() flow ───────────────────────────────────────────────────

    @Test
    fun `startPairing transitions SendingPin then WaitingConfirmation then Success`() = runTest {
        coEvery { parseVpsQrUseCase(any()) } returns Result.success(fakeSession)
        coEvery { scanDeviceQrUseCase(any()) } returns Result.success(fakeDevice)
        coEvery { sendPinToDeviceUseCase(any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { confirmPairingUseCase(any(), any(), any()) } returns Result.success(PairingStatus.PAIRED)

        viewModel.step.test {
            assertIs<PairingStep.Idle>(awaitItem())

            viewModel.onVpsQrScanned("{valid-vps-qr}")
            assertIs<PairingStep.VpsScanned>(awaitItem())

            viewModel.onDeviceQrScanned("pairing://radxa?...")
            assertIs<PairingStep.BothScanned>(awaitItem())

            viewModel.startPairing()

            assertIs<PairingStep.SendingPin>(awaitItem())
            assertIs<PairingStep.WaitingConfirmation>(awaitItem())
            assertIs<PairingStep.Success>(awaitItem())
        }
    }

    @Test
    fun `startPairing transitions to Error when sendPin fails`() = runTest {
        coEvery { parseVpsQrUseCase(any()) } returns Result.success(fakeSession)
        coEvery { scanDeviceQrUseCase(any()) } returns Result.success(fakeDevice)
        coEvery { sendPinToDeviceUseCase(any(), any(), any(), any()) } returns
            Result.failure(RuntimeException("LAN unreachable"))

        viewModel.step.test {
            assertIs<PairingStep.Idle>(awaitItem())

            viewModel.onVpsQrScanned("{valid-vps-qr}")
            assertIs<PairingStep.VpsScanned>(awaitItem())

            viewModel.onDeviceQrScanned("pairing://radxa?...")
            assertIs<PairingStep.BothScanned>(awaitItem())

            viewModel.startPairing()

            assertIs<PairingStep.SendingPin>(awaitItem())

            val next = awaitItem()
            assertIs<PairingStep.Error>(next)
            assertFalse("Error from sendPin should not be retryable", (next as PairingStep.Error).retryable)
        }
    }

    @Test
    fun `startPairing transitions to Error when confirm returns FAILED`() = runTest {
        coEvery { parseVpsQrUseCase(any()) } returns Result.success(fakeSession)
        coEvery { scanDeviceQrUseCase(any()) } returns Result.success(fakeDevice)
        coEvery { sendPinToDeviceUseCase(any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { confirmPairingUseCase(any(), any(), any()) } returns Result.success(PairingStatus.FAILED)

        viewModel.step.test {
            assertIs<PairingStep.Idle>(awaitItem())

            viewModel.onVpsQrScanned("{valid-vps-qr}")
            assertIs<PairingStep.VpsScanned>(awaitItem())

            viewModel.onDeviceQrScanned("pairing://radxa?...")
            assertIs<PairingStep.BothScanned>(awaitItem())

            viewModel.startPairing()

            assertIs<PairingStep.SendingPin>(awaitItem())
            assertIs<PairingStep.WaitingConfirmation>(awaitItem())

            val next = awaitItem()
            assertIs<PairingStep.Error>(next)
        }
    }

    @Test
    fun `startPairing transitions to Error on timeout`() = runTest {
        coEvery { parseVpsQrUseCase(any()) } returns Result.success(fakeSession)
        coEvery { scanDeviceQrUseCase(any()) } returns Result.success(fakeDevice)
        coEvery { sendPinToDeviceUseCase(any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { confirmPairingUseCase(any(), any(), any()) } returns
            Result.failure(Exception("Pairing timeout"))

        viewModel.step.test {
            assertIs<PairingStep.Idle>(awaitItem())

            viewModel.onVpsQrScanned("{valid-vps-qr}")
            assertIs<PairingStep.VpsScanned>(awaitItem())

            viewModel.onDeviceQrScanned("pairing://radxa?...")
            assertIs<PairingStep.BothScanned>(awaitItem())

            viewModel.startPairing()

            assertIs<PairingStep.SendingPin>(awaitItem())
            assertIs<PairingStep.WaitingConfirmation>(awaitItem())

            val next = awaitItem()
            assertIs<PairingStep.Error>(next)
        }
    }

    // ── retry() and reset() ───────────────────────────────────────────────────

    @Test
    fun `retry resets step to Idle`() = runTest {
        coEvery { parseVpsQrUseCase(any()) } returns Result.failure(UnrecognizedQrException())

        viewModel.onVpsQrScanned("bad-qr")
        // Give coroutine time to process
        assertIs<PairingStep.Error>(viewModel.step.value)

        viewModel.retry()
        assertIs<PairingStep.Idle>(viewModel.step.value)
    }

    @Test
    fun `reset resets step to Idle from any state`() = runTest {
        coEvery { parseVpsQrUseCase(any()) } returns Result.success(fakeSession)

        viewModel.onVpsQrScanned("{valid-vps-qr}")
        assertIs<PairingStep.VpsScanned>(viewModel.step.value)

        viewModel.reset()
        assertIs<PairingStep.Idle>(viewModel.step.value)
    }

    // ── startPairing() guard ──────────────────────────────────────────────────

    @Test
    fun `startPairing is a no-op when state is not BothScanned`() = runTest {
        // State is Idle — startPairing must not change state
        assertIs<PairingStep.Idle>(viewModel.step.value)

        viewModel.startPairing()

        // State must remain Idle — guard in ViewModel prevents execution
        assertIs<PairingStep.Idle>(viewModel.step.value)
    }
}
