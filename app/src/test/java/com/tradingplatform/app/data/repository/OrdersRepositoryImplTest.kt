package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.api.OrdersApi
import com.tradingplatform.app.data.api.OrdersWriteApi
import com.tradingplatform.app.di.NetworkModule
import com.tradingplatform.app.domain.exception.HttpStatusException
import com.tradingplatform.app.domain.model.WriteOutcome
import com.tradingplatform.app.vpn.VpnNotConnectedException
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.IOException
import java.net.ConnectException
import java.util.concurrent.TimeUnit

/**
 * Annulation d'un ordre (`POST /v1/orders/{id}/cancel`), exercée avec la vraie interface Retrofit
 * `OrdersWriteApi` contre un MockWebServer. Le client OkHttp de test reprend le réglage du client
 * `@Named("write")` : `retryOnConnectionFailure(false)`.
 *
 * Règles vérifiées : 2xx → CONFIRMED ; 409 → échec explicite ; 5xx et coupure / timeout APRÈS envoi
 * → REQUESTED_UNCONFIRMED ; échec certain AVANT envoi (VPN absent, connexion refusée) → échec ;
 * et surtout : une seule requête part toujours (aucun rejeu).
 */
class OrdersRepositoryImplTest {

    private val server = MockWebServer()

    private val cancelledOrderJson = """
        {
          "id": 48211,
          "symbol": "AAPL",
          "side": "buy",
          "quantity": "10",
          "order_type": "LIMIT",
          "status": "cancelled",
          "filled_quantity": "0",
          "average_fill_price": null,
          "limit_price": "182.5",
          "stop_price": null,
          "portfolio_id": "7d1c1f0e-3b7e-4c2a-9a55-0f6d2e8b1c34",
          "broker_order_id": "b1a2c3d4",
          "created_at": "2026-09-29T13:31:02.000000Z",
          "updated_at": "2026-09-29T13:35:10.000000Z",
          "metadata": {"user_id": "12"}
        }
    """.trimIndent()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun writeClient(readTimeoutMs: Long = 10_000L): OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    private fun repository(
        client: OkHttpClient = writeClient(),
        baseUrl: HttpUrl = server.url("/"),
    ): OrdersRepositoryImpl {
        val writeApi = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(NetworkModule.provideMoshi()))
            .build()
            .create(OrdersWriteApi::class.java)
        return OrdersRepositoryImpl(mockk<OrdersApi>(), writeApi)
    }

    private fun repositoryWithApi(writeApi: OrdersWriteApi) =
        OrdersRepositoryImpl(mockk<OrdersApi>(), writeApi)

    @Test
    fun `cancel 200 returns CONFIRMED and sends one bodiless POST`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(cancelledOrderJson))

        val result = repository().cancelOrder(48211L)

        assertEquals(WriteOutcome.CONFIRMED, result.getOrNull())
        assertEquals(1, server.requestCount)
        val request = server.takeRequest(1, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("POST", request!!.method)
        assertEquals("/v1/orders/48211/cancel", request.path)
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun `cancel 200 with an unreadable body is still CONFIRMED`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>not json</html>"))

        val result = repository().cancelOrder(48211L)

        assertEquals(WriteOutcome.CONFIRMED, result.getOrNull())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `cancel 204 without a body is CONFIRMED`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = repository().cancelOrder(7L)

        assertEquals(WriteOutcome.CONFIRMED, result.getOrNull())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `cancel 409 fails with an explicit not-cancellable message and no replay`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(409).setBody(
                """{"success":false,"error_code":"HTTP_409","message":"Invalid state transition: filled -> cancelled. Allowed from filled: []"}""",
            ),
        )

        val result = repository().cancelOrder(48211L)

        val failure = result.exceptionOrNull()
        assertTrue("expected HttpStatusException, got $failure", failure is HttpStatusException)
        assertEquals(409, (failure as HttpStatusException).code)
        assertEquals("Ordre non annulable dans son état actuel", failure.message)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `cancel 500 Cancellation failed is REQUESTED_UNCONFIRMED and sent only once`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(500)
                .setBody("""{"success":false,"error_code":"HTTP_500","message":"Cancellation failed"}"""),
        )

        val result = repository().cancelOrder(48211L)

        assertEquals(WriteOutcome.REQUESTED_UNCONFIRMED, result.getOrNull())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `cancel 503 from the gateway is REQUESTED_UNCONFIRMED and sent only once`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))

        val result = repository().cancelOrder(48211L)

        assertEquals(WriteOutcome.REQUESTED_UNCONFIRMED, result.getOrNull())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `cancel 400 401 403 404 422 and 429 are failures carrying the HTTP code`() = runTest {
        val codes = listOf(400, 401, 403, 404, 422, 429)
        codes.forEach { code -> server.enqueue(MockResponse().setResponseCode(code)) }

        val repository = repository()
        codes.forEach { code ->
            val failure = repository.cancelOrder(48211L).exceptionOrNull()
            assertTrue("HTTP $code should be a failure, got $failure", failure is HttpStatusException)
            assertEquals(code, (failure as HttpStatusException).code)
        }
        assertEquals(codes.size, server.requestCount)
    }

    @Test
    fun `connection dropped after the request was sent is REQUESTED_UNCONFIRMED without replay`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        val result = repository().cancelOrder(48211L)

        assertEquals(WriteOutcome.REQUESTED_UNCONFIRMED, result.getOrNull())
        // Une seule requête est arrivée au serveur : ni la couche OkHttp (retryOnConnectionFailure
        // désactivé) ni le repository ne rejouent l'écriture.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `read timeout after the request was sent is REQUESTED_UNCONFIRMED without replay`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val result = repository(client = writeClient(readTimeoutMs = 300L)).cancelOrder(48211L)

        assertEquals(WriteOutcome.REQUESTED_UNCONFIRMED, result.getOrNull())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `connection refused before anything is sent is a failure not an unconfirmed outcome`() = runTest {
        val deadServer = MockWebServer()
        deadServer.start()
        val deadUrl = deadServer.url("/")
        deadServer.shutdown()

        val result = repository(baseUrl = deadUrl).cancelOrder(48211L)

        assertTrue(result.isFailure)
        assertTrue(
            "expected ConnectException, got ${result.exceptionOrNull()}",
            result.exceptionOrNull() is ConnectException,
        )
    }

    @Test
    fun `VPN not connected is a failure`() = runTest {
        val vpnError = VpnNotConnectedException()
        val writeApi = mockk<OrdersWriteApi>()
        coEvery { writeApi.cancelOrder(1L) } throws vpnError

        val result = repositoryWithApi(writeApi).cancelOrder(1L)

        assertSame(vpnError, result.exceptionOrNull())
    }

    @Test
    fun `VPN block wrapped by OkHttp in an IOException is unwrapped into a failure`() = runTest {
        val vpnError = VpnNotConnectedException()
        val wrapped = IOException("canceled due to $vpnError").apply { addSuppressed(vpnError) }
        val writeApi = mockk<OrdersWriteApi>()
        coEvery { writeApi.cancelOrder(1L) } throws wrapped

        val result = repositoryWithApi(writeApi).cancelOrder(1L)

        assertSame(vpnError, result.exceptionOrNull())
    }

    @Test
    fun `coroutine cancellation is rethrown and never wrapped`() = runTest {
        val writeApi = mockk<OrdersWriteApi>()
        coEvery { writeApi.cancelOrder(1L) } throws CancellationException("cancelled")

        try {
            repositoryWithApi(writeApi).cancelOrder(1L)
            fail("CancellationException should propagate")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
        }
    }
}
