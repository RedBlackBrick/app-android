package com.tradingplatform.app.data.repository

import androidx.datastore.preferences.core.Preferences
import app.cash.turbine.test
import com.tradingplatform.app.data.api.AuthApi
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.data.local.db.dao.PnlDao
import com.tradingplatform.app.data.local.db.dao.PositionDao
import com.tradingplatform.app.data.model.PortfolioDto
import com.tradingplatform.app.data.session.SessionManager
import com.tradingplatform.app.domain.model.Portfolio
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/**
 * Portefeuille actif multi-portefeuille : conservation de la sélection au refresh, purge des
 * caches `positions` + `pnl_snapshots` uniquement quand l'id change (et AVANT l'émission),
 * sérialisation refresh/select, remise à zéro en fin de session.
 *
 * Le DataStore est un stand-in en mémoire (`store`) : les assertions portent sur la valeur
 * réellement persistée sous la clé `auth_portfolio_id` (celle relue par les widgets).
 * Le scope applicatif est un `StandardTestDispatcher` : chaque effet asynchrone (restauration
 * disque, collecteurs de session) se déclenche explicitement par `runCurrent()`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PortfolioSelectionRepositoryImplTest {

    private val authApi = mockk<AuthApi>()
    private val positionDao = mockk<PositionDao>(relaxed = true)
    private val pnlDao = mockk<PnlDao>(relaxed = true)

    /** Contenu du stockage chiffré (clé brute → valeur). */
    private val store = mutableMapOf<String, String>()

    /** Chaque valeur écrite sous `auth_portfolio_id`, dans l'ordre. */
    private val writes = mutableListOf<String>()

    private val dataStore = mockk<EncryptedDataStore> {
        coEvery { readString(any<Preferences.Key<String>>()) } answers {
            store[firstArg<Preferences.Key<String>>().name]
        }
        coEvery { writeString(any<Preferences.Key<String>>(), any()) } answers {
            store[firstArg<Preferences.Key<String>>().name] = secondArg()
            writes += secondArg<String>()
        }
    }

    private val forcedLogout = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val keystoreCorruption = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val sessionManager = mockk<SessionManager> {
        every { forcedLogoutEvents } returns forcedLogout.asSharedFlow()
        every { keystoreCorruptionEvents } returns keystoreCorruption.asSharedFlow()
    }

    private var appScope: CoroutineScope? = null

    @After
    fun tearDown() {
        appScope?.cancel()
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun TestScope.createRepository(
        persistedId: String? = null,
        settle: Boolean = true,
    ): PortfolioSelectionRepositoryImpl {
        persistedId?.let { store["auth_portfolio_id"] = it }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        appScope = scope
        val repo = PortfolioSelectionRepositoryImpl(
            authApi = authApi,
            dataStore = dataStore,
            positionDao = positionDao,
            pnlDao = pnlDao,
            sessionManager = sessionManager,
            applicationScope = scope,
        )
        if (settle) runCurrent() // restauration asynchrone depuis le disque
        return repo
    }

    private fun listResponse(vararg ids: String): Response<List<PortfolioDto>> =
        Response.success(ids.map { PortfolioDto(id = it, name = "Portfolio $it", currency = "EUR") })

    private fun portfolio(id: String) = Portfolio(id = id, name = "Portfolio $id", currency = "EUR")

    // ── Initialisation depuis le DataStore ─────────────────────────────────────

    @Test
    fun `activePortfolioId is initialised from the persisted DataStore value`() = runTest {
        val repo = createRepository(persistedId = "B")

        assertEquals("B", repo.activePortfolioId.value)
        assertEquals(emptyList<Portfolio>(), repo.portfolios.value)
    }

    @Test
    fun `activePortfolioId stays null when nothing is persisted`() = runTest {
        val repo = createRepository()

        assertNull(repo.activePortfolioId.value)
    }

    @Test
    fun `a blank persisted id is ignored`() = runTest {
        val repo = createRepository(persistedId = "")

        assertNull(repo.activePortfolioId.value)
    }

    // ── refresh ────────────────────────────────────────────────────────────────

    @Test
    fun `refresh keeps the persisted selection when it still exists and purges nothing`() = runTest {
        coEvery { authApi.getPortfolios() } returns listResponse("A", "B", "C")
        val repo = createRepository(persistedId = "B")

        val result = repo.refresh()

        assertEquals(listOf(portfolio("A"), portfolio("B"), portfolio("C")), result.getOrThrow())
        assertEquals(listOf("A", "B", "C"), repo.portfolios.value.map { it.id })
        assertEquals("B", repo.activePortfolioId.value)
        assertEquals("B", store["auth_portfolio_id"])
        coVerify(exactly = 0) { positionDao.deleteAll() }
        coVerify(exactly = 0) { pnlDao.deleteAll() }
    }

    @Test
    fun `refresh falls back to the first portfolio when the previous selection disappeared`() = runTest {
        coEvery { authApi.getPortfolios() } returns listResponse("A", "B")
        val repo = createRepository(persistedId = "Z")

        repo.refresh()

        assertEquals("A", repo.activePortfolioId.value)
        assertEquals("A", store["auth_portfolio_id"])
        coVerify(exactly = 1) { positionDao.deleteAll() }
        coVerify(exactly = 1) { pnlDao.deleteAll() }
    }

    @Test
    fun `refresh selects the first portfolio when none was selected`() = runTest {
        coEvery { authApi.getPortfolios() } returns listResponse("A", "B")
        val repo = createRepository()

        repo.refresh()

        assertEquals("A", repo.activePortfolioId.value)
        assertEquals("A", store["auth_portfolio_id"])
    }

    @Test
    fun `refresh reads the persisted id from disk when the async restore has not run yet`() = runTest {
        coEvery { authApi.getPortfolios() } returns listResponse("A", "B")
        val repo = createRepository(persistedId = "B", settle = false)
        assertNull(repo.activePortfolioId.value)

        repo.refresh()
        runCurrent() // la restauration tardive ne doit pas écraser la sélection

        assertEquals("B", repo.activePortfolioId.value)
        coVerify(exactly = 0) { positionDao.deleteAll() }
    }

    @Test
    fun `refresh with an empty list fails with No portfolio found and changes nothing`() = runTest {
        val repo = createRepository(persistedId = "A")
        coEvery { authApi.getPortfolios() } returns listResponse("A", "B")
        repo.refresh()
        coEvery { authApi.getPortfolios() } returns listResponse()

        val failure = repo.refresh().exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("No portfolio found", failure?.message)
        assertEquals(listOf("A", "B"), repo.portfolios.value.map { it.id })
        assertEquals("A", repo.activePortfolioId.value)
        assertEquals(listOf("A"), writes) // seule l'écriture du premier refresh
    }

    @Test
    fun `refresh with an empty list on first call leaves the selection unknown`() = runTest {
        coEvery { authApi.getPortfolios() } returns listResponse()
        val repo = createRepository()

        val result = repo.refresh()

        assertEquals("No portfolio found", result.exceptionOrNull()?.message)
        assertNull(repo.activePortfolioId.value)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `refresh surfaces an HTTP error and persists nothing`() = runTest {
        coEvery { authApi.getPortfolios() } returns Response.error(500, "".toResponseBody(null))
        val repo = createRepository()

        val result = repo.refresh()

        assertEquals("Get portfolios failed: HTTP 500", result.exceptionOrNull()?.message)
        assertNull(repo.activePortfolioId.value)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `refresh surfaces a network exception as a failure`() = runTest {
        coEvery { authApi.getPortfolios() } throws IOException("timeout")
        val repo = createRepository()

        val failure = repo.refresh().exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals("timeout", failure?.message)
    }

    // ── select ─────────────────────────────────────────────────────────────────

    @Test
    fun `select changes the active id, persists it and purges positions and pnl exactly once`() = runTest {
        coEvery { authApi.getPortfolios() } returns listResponse("A", "B")
        val repo = createRepository(persistedId = "A")
        repo.refresh()

        repo.activePortfolioId.test {
            assertEquals("A", awaitItem())

            val result = repo.select("B")

            assertTrue(result.isSuccess)
            assertEquals("B", awaitItem())
            expectNoEvents()
        }
        assertEquals("B", store["auth_portfolio_id"])
        coVerify(exactly = 1) { positionDao.deleteAll() }
        coVerify(exactly = 1) { pnlDao.deleteAll() }
    }

    @Test
    fun `select purges the caches before the new active id is emitted`() = runTest {
        coEvery { authApi.getPortfolios() } returns listResponse("A", "B")
        val repo = createRepository(persistedId = "A")
        repo.refresh()
        val activeIdSeenDuringPurge = mutableListOf<String?>()
        coEvery { positionDao.deleteAll() } answers { activeIdSeenDuringPurge += repo.activePortfolioId.value }
        coEvery { pnlDao.deleteAll() } answers { activeIdSeenDuringPurge += repo.activePortfolioId.value }

        repo.select("B")

        assertEquals(listOf<String?>("A", "A"), activeIdSeenDuringPurge)
        assertEquals("B", repo.activePortfolioId.value)
    }

    @Test
    fun `select of the already active id is a no-op`() = runTest {
        coEvery { authApi.getPortfolios() } returns listResponse("A", "B")
        val repo = createRepository(persistedId = "A")
        repo.refresh()

        val result = repo.select("A")

        assertTrue(result.isSuccess)
        assertEquals("A", repo.activePortfolioId.value)
        assertEquals(listOf("A"), writes) // rien de plus que l'écriture du refresh
        coVerify(exactly = 0) { positionDao.deleteAll() }
        coVerify(exactly = 0) { pnlDao.deleteAll() }
    }

    @Test
    fun `select of an unknown id fails and leaves everything untouched`() = runTest {
        coEvery { authApi.getPortfolios() } returns listResponse("A", "B")
        val repo = createRepository(persistedId = "A")
        repo.refresh()

        val failure = repo.select("Z").exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("A", repo.activePortfolioId.value)
        assertEquals("A", store["auth_portfolio_id"])
        assertEquals(listOf("A"), writes)
        coVerify(exactly = 0) { positionDao.deleteAll() }
        coVerify(exactly = 0) { pnlDao.deleteAll() }
    }

    @Test
    fun `select before the first refresh fails because the list is unknown`() = runTest {
        val repo = createRepository(persistedId = "A")

        val result = repo.select("A")

        assertTrue(result.isFailure)
        assertEquals("A", repo.activePortfolioId.value)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `a failing purge aborts the switch and keeps the previous portfolio`() = runTest {
        coEvery { authApi.getPortfolios() } returns listResponse("A", "B")
        val repo = createRepository(persistedId = "A")
        repo.refresh()
        coEvery { positionDao.deleteAll() } throws IOException("disk full")

        val failure = repo.select("B").exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals("A", repo.activePortfolioId.value)
        assertEquals("A", store["auth_portfolio_id"])
        assertEquals(listOf("A"), writes)
    }

    @Test
    fun `concurrent selects of the same id purge only once`() = runTest {
        coEvery { authApi.getPortfolios() } returns listResponse("A", "B")
        val repo = createRepository(persistedId = "A")
        repo.refresh()
        val gate = CompletableDeferred<Unit>()
        coEvery { positionDao.deleteAll() } coAnswers { gate.await() }

        val first = async { repo.select("B") }
        val second = async { repo.select("B") }
        runCurrent() // le premier tient le verrou, bloqué dans la purge ; le second l'attend
        gate.complete(Unit)
        val results = awaitAll(first, second)

        assertTrue(results.all { it.isSuccess })
        assertEquals("B", repo.activePortfolioId.value)
        assertEquals(listOf("A", "B"), writes)
        coVerify(exactly = 1) { positionDao.deleteAll() }
        coVerify(exactly = 1) { pnlDao.deleteAll() }
    }

    // ── Fin de session ─────────────────────────────────────────────────────────

    @Test
    fun `a forced logout clears the in-memory selection and the portfolio list`() = runTest {
        coEvery { authApi.getPortfolios() } returns listResponse("A", "B")
        val repo = createRepository(persistedId = "B")
        repo.refresh()
        assertEquals("B", repo.activePortfolioId.value)

        forcedLogout.emit(Unit)
        runCurrent()

        assertNull(repo.activePortfolioId.value)
        assertEquals(emptyList<Portfolio>(), repo.portfolios.value)
    }

    @Test
    fun `a keystore corruption clears the in-memory selection and the portfolio list`() = runTest {
        coEvery { authApi.getPortfolios() } returns listResponse("A", "B")
        val repo = createRepository(persistedId = "B")
        repo.refresh()

        keystoreCorruption.emit(Unit)
        runCurrent()

        assertNull(repo.activePortfolioId.value)
        assertEquals(emptyList<Portfolio>(), repo.portfolios.value)
    }

    @Test
    fun `after logout the next refresh starts again from the first portfolio when the key was cleared`() = runTest {
        coEvery { authApi.getPortfolios() } returns listResponse("A", "B")
        val repo = createRepository(persistedId = "B")
        repo.refresh()

        forcedLogout.emit(Unit)
        runCurrent()
        store.clear() // ce que fait EncryptedDataStore.clearSession()
        repo.refresh()

        assertEquals("A", repo.activePortfolioId.value)
    }

    @Test
    fun `after a logout that keeps the persisted key the same account keeps its selection`() = runTest {
        coEvery { authApi.getPortfolios() } returns listResponse("A", "B")
        val repo = createRepository(persistedId = "B")
        repo.refresh()

        forcedLogout.emit(Unit)
        runCurrent()
        repo.refresh() // nouvelle session, clé PORTFOLIO_ID toujours présente

        assertEquals("B", repo.activePortfolioId.value)
        coVerify(exactly = 0) { positionDao.deleteAll() }
    }

    @Test
    fun `a refresh in flight when the session ends is discarded and persists nothing`() = runTest {
        val repo = createRepository()
        val gate = CompletableDeferred<Unit>()
        coEvery { authApi.getPortfolios() } coAnswers {
            gate.await()
            listResponse("A", "B")
        }

        val refresh = async { repo.refresh() }
        runCurrent() // le refresh est suspendu sur la requête
        forcedLogout.emit(Unit)
        runCurrent() // le collecteur incrémente l'époque de session
        gate.complete(Unit)
        val result = refresh.await()

        assertEquals("Session ended during portfolio refresh", result.exceptionOrNull()?.message)
        assertNull(repo.activePortfolioId.value)
        assertEquals(emptyList<Portfolio>(), repo.portfolios.value)
        assertTrue(writes.isEmpty())
    }
}
