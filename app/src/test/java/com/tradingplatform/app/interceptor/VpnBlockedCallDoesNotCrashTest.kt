package com.tradingplatform.app.interceptor

import com.tradingplatform.app.data.api.interceptor.VpnRequiredInterceptor
import com.tradingplatform.app.vpn.SystemVpnMonitor
import com.tradingplatform.app.vpn.VpnNotConnectedException
import com.tradingplatform.app.vpn.VpnState
import com.tradingplatform.app.vpn.WireGuardManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Régression : sans VPN, un appel Retrofit `suspend` (donc `enqueue` OkHttp) doit se terminer en
 * ÉCHEC catchable — pas tuer le process.
 *
 * OkHttp relance sur le thread de son dispatcher toute exception d'intercepteur qui n'est pas une
 * `IOException` (après avoir notifié `onFailure` avec un wrapper « canceled due to … ») ; sur
 * Android l'exception non attrapée termine l'app. `VpnNotConnectedException` étend donc
 * `IOException` et arrive telle quelle dans le `Result.failure` du Repository.
 */
class VpnBlockedCallDoesNotCrashTest {

    private interface PingApi {
        @GET("v1/ping")
        suspend fun ping(): Response<Unit>
    }

    private lateinit var server: MockWebServer
    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        server.shutdown()
    }

    private fun api(vpnState: VpnState, systemVpnActive: Boolean): PingApi {
        val vpnManager = mockk<WireGuardManager>()
        every { vpnManager.state } returns MutableStateFlow(vpnState)
        val monitor = mockk<SystemVpnMonitor>(relaxed = true)
        every { monitor.active } returns MutableStateFlow(systemVpnActive)
        every { monitor.isActiveNow() } returns systemVpnActive
        val client = OkHttpClient.Builder()
            .addInterceptor(VpnRequiredInterceptor(vpnManager, monitor))
            .build()
        return Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .build()
            .create(PingApi::class.java)
    }

    @Test
    fun `a call blocked for lack of VPN fails with VpnNotConnectedException and kills no thread`() {
        val api = api(VpnState.Disconnected, systemVpnActive = false)

        val failure: Throwable? = try {
            runBlocking { api.ping() }
            null
        } catch (e: Throwable) {
            e
        }
        // Laisse le thread du dispatcher OkHttp terminer (l'exception non attrapée arriverait ici).
        Thread.sleep(300)

        assertTrue("échec attendu : VpnNotConnectedException, obtenu $failure", failure is VpnNotConnectedException)
        assertEquals(emptyList<Throwable>(), uncaught.toList())
        assertEquals("aucune requête ne doit partir sans VPN", 0, server.requestCount)
    }

    @Test
    fun `a call passes when a system VPN is active`() {
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200))
        val api = api(VpnState.Disconnected, systemVpnActive = true)

        val response = runBlocking { api.ping() }

        assertEquals(200, response.code())
        assertEquals(1, server.requestCount)
    }
}
