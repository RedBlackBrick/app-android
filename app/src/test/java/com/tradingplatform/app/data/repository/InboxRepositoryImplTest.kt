package com.tradingplatform.app.data.repository

import com.squareup.moshi.Moshi
import com.tradingplatform.app.data.api.NotificationApi
import com.tradingplatform.app.data.model.BigDecimalAdapter
import com.tradingplatform.app.data.model.InstantAdapter
import com.tradingplatform.app.domain.exception.HttpStatusException
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.time.Instant

/**
 * Boîte de réception serveur exercée via la vraie interface Retrofit contre un MockWebServer :
 * noms de champs REST (`type` / `body` / `read`), tableau nu, 204 sans corps sur les POST.
 */
class InboxRepositoryImplTest {

    private val server = MockWebServer()
    private lateinit var repository: InboxRepositoryImpl

    private val listJson = """
        [
          {
            "id": "0b9c3c1e-6f0e-4a55-9d0b-2f4a1f3f7a10",
            "user_id": 12,
            "type": "risk_alert",
            "title": "Drawdown à 12.4% — Growth EUR",
            "body": "Le portefeuille Growth EUR affiche un drawdown de 12.4%.",
            "data": {"alert_type": "risk_threshold_drawdown", "current_value": 12.4},
            "priority": "high",
            "read": false,
            "created_at": "2026-09-29T08:14:03.482113Z",
            "is_digested": false,
            "digest_parent_id": null,
            "duplicate_count": 1
          },
          {
            "id": "5a1d0f52-8c47-4d0e-a2b6-1e39c7f0b9d1",
            "user_id": 12,
            "type": "signal_blocked",
            "title": "Signal bloqué : AAPL",
            "body": "Un signal pour AAPL a été ignoré (marché fermé).",
            "data": {},
            "priority": "low",
            "read": true,
            "created_at": "2026-09-28T19:02:40.100000+00:00",
            "is_digested": false,
            "digest_parent_id": null,
            "duplicate_count": 3
          }
        ]
    """.trimIndent()

    @Before
    fun setUp() {
        server.start()
        val moshi = Moshi.Builder().add(BigDecimalAdapter()).add(InstantAdapter()).build()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
        repository = InboxRepositoryImpl(retrofit.create(NotificationApi::class.java))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `list maps the REST type, body and read fields`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(listJson))

        val items = repository.list().getOrThrow()

        assertEquals(2, items.size)
        val first = items[0]
        assertEquals("0b9c3c1e-6f0e-4a55-9d0b-2f4a1f3f7a10", first.id)
        assertEquals("risk_alert", first.type)
        assertEquals("Drawdown à 12.4% — Growth EUR", first.title)
        assertEquals("Le portefeuille Growth EUR affiche un drawdown de 12.4%.", first.body)
        assertFalse(first.read)
        assertEquals(Instant.parse("2026-09-29T08:14:03.482113Z"), first.createdAt)
        val second = items[1]
        assertEquals("signal_blocked", second.type)
        assertTrue(second.read)
        // Offset numérique `+00:00` (chaîne produite par `.isoformat()` côté backend).
        assertEquals(Instant.parse("2026-09-28T19:02:40.100Z"), second.createdAt)
    }

    @Test
    fun `list requests the default limit of 50 on GET v1 notifications`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

        val items = repository.list().getOrThrow()

        assertTrue(items.isEmpty())
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/notifications?limit=50", request.path)
    }

    @Test
    fun `list clamps the limit to the backend bounds`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

        repository.list(limit = 500).getOrThrow()
        repository.list(limit = 0).getOrThrow()

        assertEquals("/v1/notifications?limit=200", server.takeRequest().path)
        assertEquals("/v1/notifications?limit=1", server.takeRequest().path)
    }

    @Test
    fun `list keeps an unknown type as a free string and tolerates missing body and created_at`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""[{"id":"n-1","type":"future_type_xyz","title":"Titre","read":false}]"""),
        )

        val item = repository.list().getOrThrow().single()

        assertEquals("future_type_xyz", item.type)
        assertEquals("Titre", item.title)
        assertEquals("", item.body)
        assertEquals(Instant.EPOCH, item.createdAt)
    }

    @Test
    fun `list fails with the HTTP code on a 500`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message":"Failed to list notifications."}"""))

        val error = repository.list().exceptionOrNull()

        assertTrue(error is HttpStatusException)
        assertEquals(500, (error as HttpStatusException).code)
    }

    @Test
    fun `unreadCount reads unread_count`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"user_id": 12, "unread_count": 3}"""))

        assertEquals(3, repository.unreadCount().getOrThrow())
        assertEquals("/v1/notifications/unread-count", server.takeRequest().path)
    }

    @Test
    fun `markRead posts to the id path and accepts a 204 without body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = repository.markRead("0b9c3c1e-6f0e-4a55-9d0b-2f4a1f3f7a10")

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/notifications/0b9c3c1e-6f0e-4a55-9d0b-2f4a1f3f7a10/read", request.path)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `markRead fails with the HTTP code when the notification is unknown`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Notification not found: x"}"""))

        val error = repository.markRead("x").exceptionOrNull()

        assertTrue(error is HttpStatusException)
        assertEquals(404, (error as HttpStatusException).code)
    }

    @Test
    fun `markAllRead posts to read-all and accepts a 204 without body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = repository.markAllRead()

        assertTrue(result.isSuccess)
        assertNull(result.exceptionOrNull())
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/notifications/read-all", request.path)
    }
}
