package com.tradingplatform.app.ui.screens.setup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tradingplatform.app.data.repository.MobileProvisioningRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Instrumented smoke test for [MobileProvisioningRepositoryImpl] — the one network call in the
 * app that runs *before* the VPN/auth/CSRF interceptor chain exists (audit finding #3,
 * CLAUDE.md §2 "Accès réseau sur le thread principal"). `register()` is a hand-built, blocking
 * OkHttp call wrapped in `withContext(@IoDispatcher)`; without that wrapper it throws
 * `NetworkOnMainThreadException` whenever invoked from `viewModelScope.launch`
 * (`Dispatchers.Main.immediate`), which is exactly how `SetupViewModel` calls it via
 * `ProvisionMobileVpnUseCase`.
 *
 * This test drives [MobileProvisioningRepositoryImpl] directly rather than through
 * `ProvisionMobileVpnUseCase` (which additionally needs a real
 * `com.tradingplatform.app.vpn.WireGuardManager` and generates an on-device WireGuard keypair —
 * out of scope for a network-thread smoke test; per the CI task's own fallback: "if the use
 * case needs many deps, test the repository only"). It calls `register()` from
 * `runBlocking(Dispatchers.Main)`, reproducing the dispatcher `SetupViewModel` actually runs on.
 * If the `withContext(@IoDispatcher)` wrapper were ever removed from `register()`, this test
 * would crash with `NetworkOnMainThreadException` instead of completing successfully.
 *
 * `testClientOverride`/`testBaseUrlOverride` are `internal` test seams on the repository (see
 * its KDoc) that point `register()` at a plain-HTTP [MockWebServer] instead of the pinned
 * production HTTPS client. They are set here via reflection rather than direct assignment:
 * Kotlin's `internal` visibility is a *compile-time* friend-module check, and whether
 * `androidTest` is wired as a friend module of `main` is not guaranteed for every AGP/KGP
 * version — reflection sidesteps that entirely (the backing field is `public` at the bytecode
 * level regardless of the Kotlin-level `internal` modifier).
 */
@RunWith(AndroidJUnit4::class)
class SetupSmokeTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun register_runsOffTheMainThread_andSucceeds_whenInvokedFromDispatchersMain() {
        val responseBody = JSONObject().apply {
            put("vpn_peer_id", 42L)
            put("tunnel_ip", "10.42.0.77")
            put("dns", "10.42.0.1")
            put("allowed_ips", "0.0.0.0/0")
            put("server_pubkey", "c2VydmVyLXB1YmtleS1iYXNlNjQtdGVzdC1wYWRkaW5nPT0=")
            put("endpoint", "vps.example.com:51820")
        }.toString()
        server.enqueue(MockResponse().setResponseCode(200).setBody(responseBody))

        val repository = buildRepositoryPointedAt(server)

        // Reproduces exactly how SetupViewModel invokes this call (viewModelScope.launch runs
        // on Dispatchers.Main[.immediate]) — proves the withContext(@IoDispatcher) wrapper
        // inside register() actually moves the blocking OkHttp call off the main thread.
        val result = runBlocking(Dispatchers.Main) {
            repository.register(
                host = "127.0.0.1",
                provisioningId = "prov-123",
                wgPubkey = "d2ctcHVia2V5LWJhc2U2NC10ZXN0LXBhZGRpbmc9PQ==",
                pinProof = "deadbeef",
                nonce = "cafebabe",
                deviceLabel = "Test Device",
                fcmToken = null,
            )
        }

        assertTrue("expected register() to succeed, got $result", result.isSuccess)
        val provisioning = result.getOrThrow()
        assertEquals(42L, provisioning.vpnPeerId)
        assertEquals("10.42.0.77", provisioning.tunnelIp)
        assertEquals("vps.example.com:51820", provisioning.endpoint)

        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("POST", recorded?.method)
        assertTrue(
            "unexpected request path: ${recorded?.path}",
            recorded?.path.orEmpty().endsWith("/v1/me/mobile-provisioning/prov-123/register"),
        )
    }

    private fun buildRepositoryPointedAt(server: MockWebServer): MobileProvisioningRepositoryImpl {
        val repository = MobileProvisioningRepositoryImpl(io = Dispatchers.IO)
        val plainClient = OkHttpClient.Builder().build()

        setInternalField(repository, "testClientOverride", plainClient)
        setInternalField(repository, "testBaseUrlOverride", server.url("/").toString().trimEnd('/'))

        return repository
    }

    /**
     * Sets an `internal var` field via reflection — see the class KDoc for why direct
     * assignment isn't used.
     */
    private fun setInternalField(target: Any, fieldName: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(target, value)
    }
}
