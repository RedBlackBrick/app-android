package com.tradingplatform.app.data.repository

import android.app.Application
import com.tradingplatform.app.data.api.PairingLanApi
import com.tradingplatform.app.domain.exception.PairingDeviceException
import com.tradingplatform.app.domain.model.PairingStatus
import com.tradingplatform.app.security.SealedBoxHelper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response

/**
 * Polling `/status` et réponse de `/pin` face aux deux firmwares Radxa (fb847ec en service,
 * 93fdc58 à venir). Les deux : limite 10 req/60 s par IP (429 sans Retry-After), `unknown` =
 * session_id différent, `/pin` 200 → `{"status":"paired","device_id":"<id VPS>"}`.
 * Robolectric : `sealLanBody` utilise `android.util.Base64`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PairingRepositoryImplTest {

    private val api = mockk<PairingLanApi>()
    private val sealedBox = mockk<SealedBoxHelper> {
        every { seal(any(), any()) } returns ByteArray(48)
    }
    private val repository = PairingRepositoryImpl(api, sealedBox)

    private companion object {
        const val MAX_EMISSIONS = 12
    }

    private val ip = "192.168.1.42"
    private val pubkey = "A".repeat(43) + "="

    private fun status(value: String): Response<Map<String, Any>> =
        Response.success(mapOf<String, Any>("status" to value))

    private fun httpError(code: Int): Response<Map<String, Any>> =
        Response.error(code, "".toResponseBody(null))

    private fun givenStatuses(vararg responses: Response<Map<String, Any>>) {
        coEvery { api.getStatus(any()) } returnsMany responses.toList()
    }

    /**
     * Borné : si une régression fait perdre au polling sa condition d'arrêt (ex. `unknown` jamais
     * déclaré échoué), le test doit échouer net sur la liste émise — pas tourner à l'infini.
     */
    private suspend fun poll(): List<PairingStatus> =
        repository.pollStatus(ip, 8099, "session-1").take(MAX_EMISSIONS).toList()

    // ── /status : unknown ─────────────────────────────────────────────────────

    @Test
    fun `a single unknown is tolerated and pairing goes on`() = runTest {
        givenStatuses(status("unknown"), status("paired"))

        assertEquals(listOf(PairingStatus.PENDING, PairingStatus.PAIRED), poll())
    }

    @Test
    fun `three consecutive unknown fail the pairing instead of waiting the full 120 s`() = runTest {
        givenStatuses(status("unknown"), status("unknown"), status("unknown"), status("paired"))

        val emitted = poll()

        assertEquals(listOf(PairingStatus.PENDING, PairingStatus.PENDING, PairingStatus.FAILED), emitted)
        coVerify(exactly = 3) { api.getStatus(any()) }
    }

    @Test
    fun `unknown responses separated by a real status do not add up`() = runTest {
        givenStatuses(
            status("unknown"), status("unknown"), status("pairing"),
            status("unknown"), status("unknown"), status("paired"),
        )

        assertEquals(PairingStatus.PAIRED, poll().last())
    }

    @Test
    fun `unknown is matched case-insensitively`() = runTest {
        givenStatuses(status("UNKNOWN"), status("Unknown"), status("unknown"))

        assertEquals(PairingStatus.FAILED, poll().last())
    }

    // ── /status : cadence ─────────────────────────────────────────────────────

    @Test
    fun `nominal cadence stays 2 s between polls`() = runTest {
        givenStatuses(status("pairing"), status("pairing"), status("paired"))

        poll()

        // Deux attentes de 2 s (règle CLAUDE.md §8) — valeur en dur : comparer à la constante
        // validerait n'importe quelle cadence.
        assertEquals(4_000L, currentTime)
    }

    @Test
    fun `a 429 waits 10 s instead of hammering the rate-limited device every 2 s`() = runTest {
        givenStatuses(httpError(429), status("paired"))

        val emitted = poll()

        assertEquals(listOf(PairingStatus.PENDING, PairingStatus.PAIRED), emitted)
        assertEquals(10_000L, currentTime)
    }

    @Test
    fun `another HTTP error keeps the nominal 2 s cadence`() = runTest {
        givenStatuses(httpError(500), status("paired"))

        poll()

        assertEquals(2_000L, currentTime)
    }

    @Test
    fun `error and paired map to their terminal states`() = runTest {
        givenStatuses(status("error"))

        assertEquals(listOf(PairingStatus.FAILED), poll())
    }

    @Test
    fun `waiting written by the HAT on 93fdc58 is a pending state`() = runTest {
        givenStatuses(status("waiting"), status("paired"))

        assertEquals(listOf(PairingStatus.PENDING, PairingStatus.PAIRED), poll())
    }

    // ── /pin : device_id du 200 ───────────────────────────────────────────────

    private fun givenPinResponds(response: Response<ResponseBody>) {
        coEvery { api.sendPin(any(), any()) } returns response
    }

    private suspend fun sendPin(): Result<String?> = repository.sendPin(
        deviceIp = ip,
        devicePort = 8099,
        sessionId = "session-1",
        sessionPin = "472938",
        localToken = "tok",
        nonce = "n".repeat(64),
        radxaWgPubkey = pubkey,
    )

    @Test
    fun `pin 200 returns the definitive device_id allocated by the VPS`() = runTest {
        givenPinResponds(Response.success("""{"status":"paired","device_id":"radxa-3bdf9efaeefd"}""".toResponseBody(null)))

        assertEquals("radxa-3bdf9efaeefd", sendPin().getOrThrow())
    }

    @Test
    fun `pin 200 with an empty body is still a success with no device_id`() = runTest {
        givenPinResponds(Response.success("".toResponseBody(null)))

        val result = sendPin()

        assertTrue(result.isSuccess)
        assertNull(result.getOrThrow())
    }

    @Test
    fun `pin 200 with a non-JSON body is still a success with no device_id`() = runTest {
        givenPinResponds(Response.success("OK".toResponseBody(null)))

        val result = sendPin()

        assertTrue(result.isSuccess)
        assertNull(result.getOrThrow())
    }

    @Test
    fun `pin 200 with a blank or missing device_id gives none`() = runTest {
        givenPinResponds(Response.success("""{"status":"paired","device_id":""}""".toResponseBody(null)))
        assertNull(sendPin().getOrThrow())

        givenPinResponds(Response.success("""{"status":"paired"}""".toResponseBody(null)))
        assertNull(sendPin().getOrThrow())
    }

    @Test
    fun `pin 409 is still a PairingDeviceException carrying the body`() = runTest {
        givenPinResponds(Response.error(409, """{"error":"Already paired"}""".toResponseBody(null)))

        val failure = sendPin().exceptionOrNull()

        assertTrue(failure is PairingDeviceException)
        assertEquals(409, (failure as PairingDeviceException).httpCode)
        assertEquals("""{"error":"Already paired"}""", failure.body)
    }
}
