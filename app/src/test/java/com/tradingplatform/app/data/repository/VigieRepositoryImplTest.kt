package com.tradingplatform.app.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.squareup.moshi.Moshi
import com.tradingplatform.app.data.api.VigieApi
import com.tradingplatform.app.data.local.db.AppDatabase
import com.tradingplatform.app.data.local.db.CacheTtl
import com.tradingplatform.app.data.local.db.dao.VigieDao
import com.tradingplatform.app.data.model.VigieDto
import com.tradingplatform.app.data.model.VigieFamilyDto
import com.tradingplatform.app.data.model.VigieMostStressedFamilyDto
import com.tradingplatform.app.data.model.VigieRefugeDto
import com.tradingplatform.app.data.model.toDomain
import com.tradingplatform.app.domain.exception.HttpStatusException
import com.tradingplatform.app.domain.model.Vigie
import com.tradingplatform.app.domain.model.VigieLevel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.io.IOException

/**
 * [VigieRepositoryImpl] contre une VRAIE base Room en mémoire (Robolectric) — seul [VigieApi] est
 * mocké : fraîcheur `CacheTtl.VIGIE_MS`, repli sur la ligne périmée avec son vrai `syncedAt`,
 * ligne unique, `available = false` jamais persisté, payload illisible = pas de cache.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class VigieRepositoryImplTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: VigieDao
    private val api = mockk<VigieApi>()
    private val moshi = Moshi.Builder().build()
    private lateinit var repository: VigieRepositoryImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.vigieDao()
        repository = VigieRepositoryImpl(api, dao, moshi)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun sampleDto(
        level: String = "normal",
        score: Double? = 1.25,
        asOf: String = "2026-10-06",
    ) = VigieDto(
        available = true,
        asOf = asOf,
        computedAt = "2026-10-06T12:02:11+00:00",
        isStale = false,
        methodologyVersion = "1",
        score = score,
        level = level,
        levelRaw = level,
        coverage = 1.0,
        liveIndicators = 14,
        expectedIndicators = 14,
        liveFamilies = 5,
        expectedFamilies = 5,
        mostStressedFamily = VigieMostStressedFamilyDto("F1", "Taux et refuge obligataire", 1.9),
        families = listOf(VigieFamilyDto("F1", "Taux et refuge obligataire", 1.9, 4, 4, 1.0)),
        refuge = VigieRefugeDto(asOf = asOf, state = "none"),
    )

    /** Écrit directement une ligne (comme l'aurait fait une sync antérieure) à l'instant [syncedAt]. */
    private suspend fun seed(syncedAt: Long, payloadDto: VigieDto = sampleDto(), payload: String? = null) {
        val payloadJson = payload ?: moshi.adapter(VigieDto::class.java).toJson(payloadDto)
        dao.upsert(payloadDto.toDomain().toEntity(payloadJson, syncedAt))
    }

    private val now get() = System.currentTimeMillis()

    // ── fraîcheur ────────────────────────────────────────────────────────────

    @Test
    fun `a fresh cache is served without any network call`() = runTest {
        val syncedAt = now - 60_000L
        seed(syncedAt)

        val result = repository.getVigie(forceRefresh = false).getOrThrow()

        assertEquals(syncedAt, result.syncedAt)
        assertEquals(VigieLevel.NORMAL, result.value.level)
        coVerify(exactly = 0) { api.getVigie() }
    }

    @Test
    fun `forceRefresh bypasses a fresh cache and rewrites the single row`() = runTest {
        seed(now - 60_000L)
        coEvery { api.getVigie() } returns Response.success(sampleDto(level = "vigilance", score = 2.6))

        val result = repository.getVigie(forceRefresh = true).getOrThrow()

        assertEquals(VigieLevel.VIGILANCE, result.value.level)
        coVerify(exactly = 1) { api.getVigie() }
        val row = dao.get()!!
        assertEquals("vigilance", row.level)
        assertEquals(2.6, row.score!!, 0.0)
        assertEquals(result.syncedAt, row.syncedAt)
        assertEquals(1, row.id)
    }

    @Test
    fun `a stale cache triggers the network and replaces the row`() = runTest {
        seed(now - CacheTtl.VIGIE_MS - 60_000L)
        coEvery { api.getVigie() } returns Response.success(sampleDto(level = "alerte", score = 4.0))

        val result = repository.getVigie(forceRefresh = false).getOrThrow()

        assertEquals(VigieLevel.ALERTE, result.value.level)
        assertEquals("alerte", dao.get()!!.level)
        assertTrue(CacheTtl.isFresh(result.syncedAt, CacheTtl.VIGIE_MS))
    }

    @Test
    fun `an empty cache fetches and stores the scalar projection used by the widget`() = runTest {
        coEvery { api.getVigie() } returns Response.success(sampleDto())

        repository.getVigie(forceRefresh = false).getOrThrow()

        val row = dao.get()!!
        assertEquals("2026-10-06", row.asOf)
        assertEquals("normal", row.level)
        assertEquals("normal", row.levelRaw)
        assertEquals("none", row.refugeState)
        assertEquals(1.25, row.score!!, 0.0)
        assertEquals(14, row.liveIndicators)
        assertEquals(14, row.expectedIndicators)
        assertEquals("F1", row.mostStressedFamilyCode)
        assertEquals("Taux et refuge obligataire", row.mostStressedFamilyLabel)
        assertEquals(1.9, row.mostStressedFamilyScore!!, 0.0)
        assertFalse(row.isStale)
        assertNotNull(row.computedAt)
    }

    // ── repli hors ligne ─────────────────────────────────────────────────────

    @Test
    fun `network failure with a stale row returns it with its REAL syncedAt, not an error`() = runTest {
        val syncedAt = now - CacheTtl.VIGIE_MS - 3_600_000L
        seed(syncedAt)
        coEvery { api.getVigie() } throws IOException("VPN down")

        val result = repository.getVigie(forceRefresh = true)

        assertTrue(result.isSuccess)
        assertEquals(syncedAt, result.getOrThrow().syncedAt)
        assertEquals(VigieLevel.NORMAL, result.getOrThrow().value.level)
        // La ligne périmée n'a pas été touchée.
        assertEquals(syncedAt, dao.get()!!.syncedAt)
    }

    @Test
    fun `HTTP error with a cached row falls back to the cache`() = runTest {
        val syncedAt = now - CacheTtl.VIGIE_MS - 1_000L
        seed(syncedAt)
        coEvery { api.getVigie() } returns Response.error(500, "".toResponseBody(null))

        val result = repository.getVigie(forceRefresh = false)

        assertEquals(syncedAt, result.getOrThrow().syncedAt)
    }

    @Test
    fun `network failure without any cache is a failure`() = runTest {
        coEvery { api.getVigie() } throws IOException("VPN down")

        val result = repository.getVigie(forceRefresh = false)

        assertTrue(result.isFailure)
        assertNull(dao.get())
    }

    @Test
    fun `HTTP error without cache is an HttpStatusException failure and writes nothing`() = runTest {
        coEvery { api.getVigie() } returns Response.error(503, "".toResponseBody(null))

        val result = repository.getVigie(forceRefresh = false)

        assertTrue(result.exceptionOrNull() is HttpStatusException)
        assertNull(dao.get())
    }

    // ── available = false ────────────────────────────────────────────────────

    @Test
    fun `available false is a success with UNAVAILABLE and is never persisted`() = runTest {
        coEvery { api.getVigie() } returns Response.success(VigieDto(available = false))

        val result = repository.getVigie(forceRefresh = false).getOrThrow()

        assertFalse(result.value.available)
        assertEquals(VigieLevel.UNKNOWN, result.value.level)
        assertEquals(Vigie.UNAVAILABLE, result.value)
        assertNull(dao.get())
    }

    @Test
    fun `available false removes a previously cached row (the server is the truth)`() = runTest {
        seed(now - CacheTtl.VIGIE_MS - 1_000L)
        coEvery { api.getVigie() } returns Response.success(VigieDto(available = false))

        repository.getVigie(forceRefresh = false).getOrThrow()

        assertNull(dao.get())
    }

    // ── cache corrompu ───────────────────────────────────────────────────────

    @Test
    fun `an unreadable cached payload counts as no cache and is refetched`() = runTest {
        seed(syncedAt = now - 60_000L, payload = "{ ceci n'est pas du json")
        coEvery { api.getVigie() } returns Response.success(sampleDto(level = "crise", score = 6.0))

        val result = repository.getVigie(forceRefresh = false).getOrThrow()

        assertEquals(VigieLevel.CRISE, result.value.level)
        coVerify(exactly = 1) { api.getVigie() }
    }

    @Test
    fun `an unreadable cached payload plus a network failure is a failure, never an invented value`() = runTest {
        seed(syncedAt = now - 60_000L, payload = "{ ceci n'est pas du json")
        coEvery { api.getVigie() } throws IOException("VPN down")

        assertTrue(repository.getVigie(forceRefresh = false).isFailure)
    }

    // ── ligne unique ─────────────────────────────────────────────────────────

    @Test
    fun `repeated syncs keep a single row`() = runTest {
        coEvery { api.getVigie() } returns Response.success(sampleDto())

        repository.getVigie(forceRefresh = true)
        repository.getVigie(forceRefresh = true)
        repository.getVigie(forceRefresh = true)

        val count = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM vigie_snapshot").use {
            it.moveToFirst()
            it.getInt(0)
        }
        assertEquals(1, count)
    }

    // ── DAO : upsert + purge ─────────────────────────────────────────────────

    @Test
    fun `upsertAndPurge keeps the row it has just written, with the TTL cutoff`() = runTest {
        val syncedAt = now
        val entity = sampleDto().toDomain().toEntity("{}", syncedAt)

        dao.upsertAndPurge(entity, cutoffMillis = syncedAt - CacheTtl.VIGIE_MS)

        assertEquals(syncedAt, dao.get()?.syncedAt)
    }

    @Test
    fun `deleteOlderThan removes a row older than the cutoff and only that`() = runTest {
        val old = sampleDto().toDomain().toEntity("{}", syncedAt = 1_000L)
        dao.upsert(old)

        dao.deleteOlderThan(cutoffMillis = 500L)
        assertNotNull(dao.get())

        dao.deleteOlderThan(cutoffMillis = 2_000L)
        assertNull(dao.get())
    }
}
