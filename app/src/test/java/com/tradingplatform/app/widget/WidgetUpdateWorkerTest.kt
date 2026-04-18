package com.tradingplatform.app.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.data.local.db.dao.AlertDao
import com.tradingplatform.app.data.local.db.dao.QuoteDao
import com.tradingplatform.app.domain.model.PnlPeriod
import com.tradingplatform.app.domain.model.PnlSummary
import com.tradingplatform.app.domain.model.Position
import com.tradingplatform.app.domain.model.PositionStatus
import com.tradingplatform.app.domain.model.Quote
import com.tradingplatform.app.domain.usecase.market.GetDefaultQuoteSymbolUseCase
import com.tradingplatform.app.domain.usecase.market.GetQuoteUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPnlUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPositionsUseCase
import com.tradingplatform.app.vpn.VpnState
import com.tradingplatform.app.vpn.WireGuardManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import android.app.Application
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.Instant

/**
 * Tests unitaires pour [WidgetUpdateWorker].
 *
 * Utilise TestListenableWorkerBuilder pour tester le Worker sans WorkManager réel.
 * Les dépendances sont mockées avec Mockk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WidgetUpdateWorkerTest {

    private val vpnManager = mockk<WireGuardManager>()
    private val dataStore = mockk<EncryptedDataStore>()
    private val getPositionsUseCase = mockk<GetPositionsUseCase>()
    private val getPnlUseCase = mockk<GetPnlUseCase>()
    private val getQuoteUseCase = mockk<GetQuoteUseCase>()
    private val getDefaultQuoteSymbolUseCase = mockk<GetDefaultQuoteSymbolUseCase>()
    private val alertDao = mockk<AlertDao>(relaxed = true)
    private val quoteDao = mockk<QuoteDao>(relaxed = true)

    // ── Fake data ──────────────────────────────────────────────────────────────

    private val fakePosition = Position(
        id = 1,
        symbol = "AAPL",
        quantity = BigDecimal("10"),
        avgPrice = BigDecimal("150.00"),
        currentPrice = BigDecimal("175.00"),
        unrealizedPnl = BigDecimal("250.00"),
        unrealizedPnlPercent = 16.67,
        status = PositionStatus.OPEN,
        openedAt = Instant.now(),
    )

    private val fakePnl = PnlSummary(
        totalReturn = BigDecimal("4500.00"),
        totalReturnPct = 0.045,
        sharpeRatio = 1.2,
        sortinoRatio = 1.5,
        maxDrawdown = -0.05,
        volatility = 0.12,
        cagr = 0.09,
        winRate = 0.7,
        profitFactor = 2.3,
        avgTradeReturn = BigDecimal("450.00"),
    )

    private val fakeQuote = Quote(
        symbol = "AAPL",
        price = BigDecimal("175.50"),
        bid = BigDecimal("175.48"),
        ask = BigDecimal("175.52"),
        volume = 35_000_000L,
        change = BigDecimal("2.30"),
        changePercent = 1.33,
        timestamp = Instant.now(),
        source = "yahoo",
    )

    @Before
    fun setUp() {
        // Defaults
        coEvery { dataStore.readString(DataStoreKeys.PORTFOLIO_ID) } returns "1"
        coEvery { getPositionsUseCase(any()) } returns Result.success(listOf(fakePosition))
        coEvery { getPnlUseCase(any(), any()) } returns Result.success(fakePnl)
        coEvery { quoteDao.getAllSymbols() } returns listOf("AAPL")
        coEvery { getQuoteUseCase(any()) } returns Result.success(fakeQuote)
        coEvery { getDefaultQuoteSymbolUseCase() } returns "AAPL"
    }

    // ── Factory helper ─────────────────────────────────────────────────────────

    /**
     * Construit un [WidgetUpdateWorker] avec des dépendances injectées manuellement.
     *
     * [TestListenableWorkerBuilder] ne supporte pas l'injection Hilt directement.
     * On utilise une [androidx.work.WorkerFactory] pour injecter les mocks.
     */
    private fun buildWorker(): WidgetUpdateWorker {
        return TestListenableWorkerBuilder<WidgetUpdateWorker>(
            ApplicationProvider.getApplicationContext()
        ).setWorkerFactory(
            object : androidx.work.WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: androidx.work.WorkerParameters,
                ): ListenableWorker {
                    return WidgetUpdateWorker(
                        context = appContext,
                        workerParams = workerParameters,
                        vpnManager = vpnManager,
                        dataStore = dataStore,
                        getPositionsUseCase = getPositionsUseCase,
                        getPnlUseCase = getPnlUseCase,
                        getQuoteUseCase = getQuoteUseCase,
                        getDefaultQuoteSymbolUseCase = getDefaultQuoteSymbolUseCase,
                        alertDao = alertDao,
                        quoteDao = quoteDao,
                    )
                }
            }
        ).build()
    }

    // ── Tests VPN ──────────────────────────────────────────────────────────────

    @Test
    fun `doWork returns success when VPN is disconnected without syncing`() = runTest {
        every { vpnManager.state } returns MutableStateFlow(VpnState.Disconnected)

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        // Aucune sync réseau ne doit être déclenchée
        coVerify(exactly = 0) { getPositionsUseCase(any()) }
        coVerify(exactly = 0) { getPnlUseCase(any(), any()) }
        coVerify(exactly = 0) { getQuoteUseCase(any()) }
    }

    @Test
    fun `doWork returns success when VPN is in connecting state`() = runTest {
        every { vpnManager.state } returns MutableStateFlow(VpnState.Connecting)

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { getPositionsUseCase(any()) }
    }

    @Test
    fun `doWork returns success when VPN is in error state`() = runTest {
        every { vpnManager.state } returns MutableStateFlow(VpnState.Error("Connection failed"))

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { getPositionsUseCase(any()) }
    }

    // ── Tests portfolioId manquant ─────────────────────────────────────────────

    @Test
    fun `doWork returns success when portfolioId is not found in DataStore`() = runTest {
        every { vpnManager.state } returns MutableStateFlow(VpnState.Connected())
        coEvery { dataStore.readString(DataStoreKeys.PORTFOLIO_ID) } returns null

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { getPositionsUseCase(any()) }
    }

    // ── Tests sync portfolio ───────────────────────────────────────────────────

    @Test
    fun `doWork syncs portfolio and quotes when VPN is connected`() = runTest {
        every { vpnManager.state } returns MutableStateFlow(VpnState.Connected())

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) { getPositionsUseCase("1") }
        coVerify(exactly = 1) { getPnlUseCase("1", PnlPeriod.DAY) }
        coVerify(exactly = 1) { getQuoteUseCase("AAPL") }
    }

    @Test
    fun `doWork returns success when only positions sync fails (partial failure does not retry)`() = runTest {
        // Règle après refactor : Result.retry() uniquement si TOUTES les sections IO
        // échouent. Un échec isolé (positions) ne déclenche pas un re-run complet —
        // le cycle 15min re-tentera de toute façon. Évite de re-sync PnL+quotes en boucle.
        every { vpnManager.state } returns MutableStateFlow(VpnState.Connected())
        coEvery { getPositionsUseCase(any()) } returns Result.failure(java.io.IOException("Timeout"))

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun `doWork returns success when only PnL sync fails (partial failure does not retry)`() = runTest {
        every { vpnManager.state } returns MutableStateFlow(VpnState.Connected())
        coEvery { getPnlUseCase(any(), any()) } returns Result.failure(java.io.IOException("Network error"))

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun `doWork returns success when only quotes sync fails (partial failure does not retry)`() = runTest {
        every { vpnManager.state } returns MutableStateFlow(VpnState.Connected())
        coEvery { getQuoteUseCase(any()) } returns Result.failure(java.io.IOException("Timeout"))

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun `doWork returns retry when ALL sections fail with IOException`() = runTest {
        // Seul cas qui justifie Result.retry() : toutes les sections IO ont échoué,
        // suggérant un problème réseau transitoire global.
        every { vpnManager.state } returns MutableStateFlow(VpnState.Connected())
        coEvery { getPositionsUseCase(any()) } returns Result.failure(java.io.IOException("Timeout"))
        coEvery { getPnlUseCase(any(), any()) } returns Result.failure(java.io.IOException("Timeout"))
        coEvery { getQuoteUseCase(any()) } returns Result.failure(java.io.IOException("Timeout"))

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun `doWork continues quotes sync even when portfolio sync fails with IOException`() = runTest {
        every { vpnManager.state } returns MutableStateFlow(VpnState.Connected())
        coEvery { getPositionsUseCase(any()) } returns Result.failure(java.io.IOException("Portfolio timeout"))
        // Quotes should still be attempted

        buildWorker().doWork()

        // Quotes doit être tenté malgré l'échec portfolio
        coVerify(exactly = 1) { getQuoteUseCase(any()) }
    }

    @Test
    fun `doWork returns success not retry when VpnNotConnectedException occurs during sync`() = runTest {
        every { vpnManager.state } returns MutableStateFlow(VpnState.Connected())
        coEvery { getPositionsUseCase(any()) } returns Result.failure(
            com.tradingplatform.app.vpn.VpnNotConnectedException()
        )

        val result = buildWorker().doWork()

        // VpnNotConnectedException ne doit pas déclencher de retry
        assertEquals(ListenableWorker.Result.success(), result)
    }

    // ── Tests purge alertes ────────────────────────────────────────────────────

    @Test
    fun `doWork purges expired alerts when VPN is connected`() = runTest {
        every { vpnManager.state } returns MutableStateFlow(VpnState.Connected())

        buildWorker().doWork()

        // La purge des alertes doit toujours être appelée (locale, pas de réseau)
        // Depuis R6, purgeExpired() est une @Transaction qui combine deleteOlderThan + keepOnlyLatest500
        coVerify(exactly = 1) { alertDao.purgeExpired(any()) }
    }

    // ── Tests comportement non-admin ───────────────────────────────────────────

    @Test
    fun `doWork returns success with default quote symbol when quote cache is empty`() = runTest {
        every { vpnManager.state } returns MutableStateFlow(VpnState.Connected())
        coEvery { quoteDao.getAllSymbols() } returns emptyList()

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        // Doit utiliser le symbole par défaut (AAPL)
        coVerify(exactly = 1) { getQuoteUseCase("AAPL") }
    }

    @Test
    fun `doWork uses user-configured default symbol when cache is empty`() = runTest {
        every { vpnManager.state } returns MutableStateFlow(VpnState.Connected())
        coEvery { quoteDao.getAllSymbols() } returns emptyList()
        coEvery { getDefaultQuoteSymbolUseCase() } returns "TSLA"
        coEvery { getQuoteUseCase("TSLA") } returns Result.success(fakeQuote.copy(symbol = "TSLA"))

        buildWorker().doWork()

        // La résolution du symbole passe par GetDefaultQuoteSymbolUseCase
        // (préférence utilisateur EncryptedDataStore > watchlist > AppDefaults)
        coVerify(exactly = 1) { getDefaultQuoteSymbolUseCase() }
        coVerify(exactly = 1) { getQuoteUseCase("TSLA") }
        coVerify(exactly = 0) { getQuoteUseCase("AAPL") }
    }

    @Test
    fun `doWork does not call default symbol resolver when cache is populated`() = runTest {
        every { vpnManager.state } returns MutableStateFlow(VpnState.Connected())
        coEvery { quoteDao.getAllSymbols() } returns listOf("AAPL", "MSFT")

        buildWorker().doWork()

        // Pas besoin d'appeler le resolver si la watchlist cache a déjà des symboles
        coVerify(exactly = 0) { getDefaultQuoteSymbolUseCase() }
    }

    @Test
    fun `doWork syncs all cached symbols`() = runTest {
        every { vpnManager.state } returns MutableStateFlow(VpnState.Connected())
        coEvery { quoteDao.getAllSymbols() } returns listOf("AAPL", "TSLA", "MSFT")
        coEvery { getQuoteUseCase(any()) } returns Result.success(fakeQuote)

        buildWorker().doWork()

        coVerify(exactly = 1) { getQuoteUseCase("AAPL") }
        coVerify(exactly = 1) { getQuoteUseCase("TSLA") }
        coVerify(exactly = 1) { getQuoteUseCase("MSFT") }
    }
}
