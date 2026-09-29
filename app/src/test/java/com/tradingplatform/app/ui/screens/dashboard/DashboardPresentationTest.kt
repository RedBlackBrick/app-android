package com.tradingplatform.app.ui.screens.dashboard

import com.tradingplatform.app.domain.model.ActivityItem
import com.tradingplatform.app.domain.model.CircuitBreakerState
import com.tradingplatform.app.domain.model.NavCurve
import com.tradingplatform.app.domain.model.NavPoint
import com.tradingplatform.app.domain.model.NavSummary
import com.tradingplatform.app.domain.model.PerformanceMetrics
import com.tradingplatform.app.domain.model.PnlPeriod
import com.tradingplatform.app.domain.model.Portfolio
import com.tradingplatform.app.domain.model.PortfolioBrokerStatus
import com.tradingplatform.app.domain.model.PortfolioCircuitBreakerStatus
import com.tradingplatform.app.domain.model.PortfolioOverviewItem
import com.tradingplatform.app.domain.model.RiskStatus
import com.tradingplatform.app.ui.common.DataState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * Tests JVM de la logique de présentation pure du Dashboard : formatage des pourcentages,
 * KPI, bandeau de risque, broker, stratégies, carte « Mes portefeuilles », pied du héros,
 * découpage et libellés du flux d'activité.
 * Les attendus sont des valeurs littérales (jamais recalculées avec le code testé).
 */
class DashboardPresentationTest {

    /** Le groupement des milliers français est une espace insécable (U+202F ou U+00A0 selon le JDK). */
    private fun String.plain() = replace('\u202f', ' ').replace('\u00a0', ' ')

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** Métriques de performance cumulées : `winRate` et `maxDrawdown` sont des FRACTIONS. */
    private fun performance(
        maxDrawdown: Double? = 0.083,
        winRate: Double? = 0.62,
    ) = PerformanceMetrics(
        totalReturn = null,
        totalReturnPct = null,
        sharpeRatio = null,
        sortinoRatio = null,
        maxDrawdown = maxDrawdown,
        volatility = null,
        cagr = null,
        winRate = winRate,
        profitFactor = null,
        avgTradeReturn = null,
    )

    private fun risk(
        killSwitch: Boolean = false,
        violations: Int = 0,
        dailyLoss: Double? = null,
        partial: Boolean = false,
    ) = RiskStatus(
        killSwitchActive = killSwitch,
        killSwitchReason = null,
        unresolvedViolations = violations,
        dailyLossUsagePct = dailyLoss,
        drawdownCurrentPct = null,
        isPartial = partial,
    )

    private fun overviewItem(
        id: String,
        name: String,
        currency: String = "EUR",
        value: String = "10000.00",
        pnl: String? = "100.00",
        pct: Double? = 0.01,
    ) = PortfolioOverviewItem(
        portfolioId = id,
        name = name,
        currency = currency,
        currentValue = BigDecimal(value),
        periodPnl = pnl?.let { BigDecimal(it) },
        periodPnlPct = pct,
    )

    private fun nav(
        cash: String = "12000.00",
        unrealized: String = "3475.29",
    ) = NavSummary(
        currentValue = BigDecimal("103475.29"),
        cashBalance = BigDecimal(cash),
        totalRealizedPnl = BigDecimal.ZERO,
        totalUnrealizedPnl = BigDecimal(unrealized),
    )

    private fun breaker(
        enabled: Boolean = true,
        state: CircuitBreakerState = CircuitBreakerState.CLOSED,
        redisUnavailable: Boolean = false,
    ) = PortfolioCircuitBreakerStatus(
        portfolioId = "1",
        enabled = enabled,
        state = state,
        count = 4,
        threshold = 5,
        windowSeconds = 60,
        ttlSeconds = null,
        redisUnavailable = redisUnavailable,
    )

    private val t0: Instant = Instant.parse("2026-01-01T12:00:00Z")

    // ── Périodes ──────────────────────────────────────────────────────────────

    @Test
    fun `period labels are the French chip labels and all reads Tout`() {
        assertEquals("Jour", dashboardPeriodLabel(PnlPeriod.DAY))
        assertEquals("Sem.", dashboardPeriodLabel(PnlPeriod.WEEK))
        assertEquals("Mois", dashboardPeriodLabel(PnlPeriod.MONTH))
        assertEquals("Année", dashboardPeriodLabel(PnlPeriod.YEAR))
        assertEquals("Tout", dashboardPeriodLabel(PnlPeriod.ALL))
    }

    @Test
    fun `selector offers day week month and all in that order, the year is gone`() {
        assertEquals(
            listOf(PnlPeriod.DAY, PnlPeriod.WEEK, PnlPeriod.MONTH, PnlPeriod.ALL),
            DASHBOARD_PERIODS,
        )
        assertFalse(PnlPeriod.YEAR in DASHBOARD_PERIODS)
        assertEquals(listOf("Jour", "Sem.", "Mois", "Tout"), DASHBOARD_PERIODS.map { dashboardPeriodLabel(it) })
    }

    @Test
    fun `period captions say what the pnl covers, all means since the creation`() {
        assertEquals("P&L du jour", dashboardPeriodCaption(PnlPeriod.DAY))
        assertEquals("P&L de la semaine", dashboardPeriodCaption(PnlPeriod.WEEK))
        assertEquals("P&L du mois", dashboardPeriodCaption(PnlPeriod.MONTH))
        assertEquals("P&L depuis la création", dashboardPeriodCaption(PnlPeriod.ALL))
    }

    // ── Devise ────────────────────────────────────────────────────────────────

    @Test
    fun `currency codes map to their symbol, unknown codes stay readable`() {
        assertEquals("€", currencySymbolFor("EUR"))
        assertEquals("€", currencySymbolFor(" eur "))
        assertEquals("\$", currencySymbolFor("USD"))
        assertEquals("£", currencySymbolFor("GBP"))
        assertEquals("CHF", currencySymbolFor("chf"))
        assertEquals("€", currencySymbolFor(null))
        assertEquals("€", currencySymbolFor(""))
    }

    @Test
    fun `active currency comes from the active portfolio and defaults to euro`() {
        val portfolios = listOf(Portfolio("1", "Principal", "EUR"), Portfolio("2", "US", "USD"))

        assertEquals("\$", activeCurrencySymbol(portfolios, "2"))
        assertEquals("€", activeCurrencySymbol(portfolios, "1"))
        assertEquals("€", activeCurrencySymbol(portfolios, "inconnu"))
        assertEquals("€", activeCurrencySymbol(emptyList(), "1"))
    }

    // ── formatPercent ─────────────────────────────────────────────────────────

    @Test
    fun `formatPercent converts a fraction with a comma and no space before the sign`() {
        assertEquals("4,50%", formatPercent(0.045))
        assertEquals("+4,50%", formatPercent(0.045, signed = true))
        assertEquals("-1,23%", formatPercent(-0.0123, signed = true))
        assertEquals("-1,23%", formatPercent(-0.0123))
    }

    @Test
    fun `formatPercent honours the number of decimals`() {
        assertEquals("70%", formatPercent(0.7, decimals = 0))
        assertEquals("67%", formatPercent(0.666, decimals = 0))
        assertEquals("71%", formatPercent(0.705, decimals = 0))
        assertEquals("8,3%", formatPercent(0.083, decimals = 1))
    }

    @Test
    fun `formatPercent never shows a signed zero`() {
        assertEquals("0,00%", formatPercent(0.0, signed = true))
        assertEquals("0,00%", formatPercent(-0.0, signed = true))
        // -0,004 % arrondi à 2 décimales = 0 → pas de « -0,00% ».
        assertEquals("0,00%", formatPercent(-0.00004, signed = true))
        assertEquals("+0,01%", formatPercent(0.00005, signed = true))
    }

    @Test
    fun `formatPercent returns the placeholder for missing or corrupted values`() {
        assertEquals("—", formatPercent(null))
        assertEquals("—", formatPercent(Double.NaN))
        assertEquals("—", formatPercent(Double.POSITIVE_INFINITY))
        assertEquals("—", formatPercent(10_000_000.0))
    }

    // ── spokenPercent ─────────────────────────────────────────────────────────

    @Test
    fun `spokenPercent says the sign in words`() {
        assertEquals("plus 4,50 pour cent", spokenPercent(0.045, signed = true))
        assertEquals("moins 1,20 pour cent", spokenPercent(-0.012, signed = true))
        assertEquals("8,30 pour cent", spokenPercent(0.083))
        assertEquals("70 pour cent", spokenPercent(0.7, decimals = 0))
        assertEquals("0,00 pour cent", spokenPercent(0.0, signed = true))
    }

    @Test
    fun `spokenPercent of a missing value is unavailable`() {
        assertEquals("indisponible", spokenPercent(null))
        assertEquals("indisponible", spokenPercent(Double.NaN, signed = true))
    }

    // ── percentTone ───────────────────────────────────────────────────────────

    @Test
    fun `percentTone follows the sign of the displayed value`() {
        assertEquals(PnlTone.POSITIVE, percentTone(0.045))
        assertEquals(PnlTone.NEGATIVE, percentTone(-0.045))
        assertEquals(PnlTone.NEUTRAL, percentTone(0.0))
        // Arrondi à zéro → neutre, comme le texte « 0,00% ».
        assertEquals(PnlTone.NEUTRAL, percentTone(-0.00004))
        assertEquals(PnlTone.NEUTRAL, percentTone(null))
    }

    // ── variationSpokenDescription ────────────────────────────────────────────

    @Test
    fun `variation description reads gain amount then percent`() {
        assertEquals(
            "P&L : Gain de 45,00 €, plus 4,50 pour cent",
            variationSpokenDescription(BigDecimal("45.00"), 0.045),
        )
    }

    @Test
    fun `variation description reads a loss with the words perte and moins`() {
        assertEquals(
            "P&L : Perte de 30,00 €, moins 1,20 pour cent",
            variationSpokenDescription(BigDecimal("-30.00"), -0.012),
        )
    }

    @Test
    fun `variation description tolerates a missing amount or percent`() {
        assertEquals("P&L : plus 4,50 pour cent", variationSpokenDescription(null, 0.045))
        assertEquals("P&L : Gain de 45,00 €", variationSpokenDescription(BigDecimal("45.00"), null))
        assertEquals("P&L indisponible", variationSpokenDescription(null, null))
    }

    // ── heroFooter ────────────────────────────────────────────────────────────

    @Test
    fun `footer is none when both sections are healthy or loading`() {
        assertEquals(
            HeroFooter.None,
            heroFooter(DataState(value = "nav", syncedAt = 10L), DataState(value = 1, syncedAt = 20L)),
        )
        assertEquals(
            HeroFooter.None,
            heroFooter(DataState<String>(isRefreshing = true), DataState<Int>(isRefreshing = true)),
        )
    }

    @Test
    fun `footer is the error banner when a section never loaded and failed`() {
        assertEquals(
            HeroFooter.Error("nav down"),
            heroFooter(
                DataState<String>(error = "nav down"),
                DataState(value = 1, syncedAt = 20L),
            ),
        )
        assertEquals(
            HeroFooter.Error("pnl down"),
            heroFooter(
                DataState(value = "nav", syncedAt = 10L),
                DataState<Int>(error = "pnl down"),
            ),
        )
    }

    @Test
    fun `footer prefers the NAV error when both sections failed without a value`() {
        assertEquals(
            HeroFooter.Error("nav down"),
            heroFooter(DataState<String>(error = "nav down"), DataState<Int>(error = "pnl down")),
        )
    }

    @Test
    fun `an error banner wins over a stale timestamp`() {
        assertEquals(
            HeroFooter.Error("pnl down"),
            heroFooter(
                DataState(value = "nav", error = "refresh failed", syncedAt = 10L),
                DataState<Int>(error = "pnl down"),
            ),
        )
    }

    @Test
    fun `footer is stale with the oldest sync when a value is shown despite an error`() {
        assertEquals(
            HeroFooter.Stale(10L),
            heroFooter(
                DataState(value = "nav", error = "e1", syncedAt = 10L),
                DataState(value = 1, error = "e2", syncedAt = 20L),
            ),
        )
        assertEquals(
            HeroFooter.Stale(20L),
            heroFooter(
                DataState(value = "nav", syncedAt = 10L),
                DataState(value = 1, error = "e2", syncedAt = 20L),
            ),
        )
    }

    // ── navCurveValues / navCurveSpokenDescription ────────────────────────────

    private fun curve(vararg values: String) = NavCurve(
        values.mapIndexed { i, v -> NavPoint(t0.plusSeconds(i * 60L), BigDecimal(v)) },
    )

    @Test
    fun `a nav curve with two points or more is drawn in time order`() {
        val values = navCurveValues(curve("100000.00", "101250.50", "104500.00"))

        assertEquals(3, values.size)
        assertEquals("100000.00", values.first().toPlainString())
        assertEquals("104500.00", values.last().toPlainString())
    }

    @Test
    fun `an absent empty or single point curve draws nothing`() {
        assertEquals(emptyList<BigDecimal>(), navCurveValues(null))
        assertEquals(emptyList<BigDecimal>(), navCurveValues(NavCurve(emptyList())))
        assertEquals(emptyList<BigDecimal>(), navCurveValues(curve("100000.00")))
    }

    @Test
    fun `the curve is described in formatted euros with its direction`() {
        assertEquals(
            "Courbe de la valeur liquidative, en hausse : de 100 000,00 € à 104 500,00 €",
            navCurveSpokenDescription(navCurveValues(curve("100000.00", "104500.00")))!!.plain(),
        )
        assertEquals(
            "Courbe de la valeur liquidative, en baisse : de 100 000,00 € à 99 000,50 €",
            navCurveSpokenDescription(navCurveValues(curve("100000.00", "99000.50")))!!.plain(),
        )
        assertEquals(
            "Courbe de la valeur liquidative, stable : de 500,00 \$ à 500,00 \$",
            navCurveSpokenDescription(navCurveValues(curve("500.00", "500.00")), "\$")!!.plain(),
        )
    }

    @Test
    fun `there is nothing to describe without a curve`() {
        assertNull(navCurveSpokenDescription(emptyList()))
        assertNull(navCurveSpokenDescription(listOf(BigDecimal("1.00"))))
    }

    // ── dashboardKpis ─────────────────────────────────────────────────────────

    @Test
    fun `kpis are cash, unrealized, win rate then drawdown from the performance metrics`() {
        val kpis = dashboardKpis(nav(), performance(winRate = 0.62, maxDrawdown = 0.083))

        assertEquals(
            listOf(
                DashboardKpiKind.CASH,
                DashboardKpiKind.UNREALIZED,
                DashboardKpiKind.WIN_RATE,
                DashboardKpiKind.MAX_DRAWDOWN,
            ),
            kpis.map { it.kind },
        )
        assertEquals(listOf("Liquidités", "Latent", "Win rate", "Drawdown max"), kpis.map { it.label })
        assertEquals(listOf("12 000 €", "+3 475 €", "62%", "8,30%"), kpis.map { it.value.plain() })
        assertEquals(
            listOf(PnlTone.NEUTRAL, PnlTone.POSITIVE, PnlTone.NEUTRAL, PnlTone.NEUTRAL),
            kpis.map { it.tone },
        )
    }

    @Test
    fun `win rate and drawdown are fractions, never read as already-multiplied percentages`() {
        val kpis = dashboardKpis(null, performance(winRate = 0.5, maxDrawdown = 0.125))

        assertEquals(listOf("50%", "12,50%"), kpis.map { it.value })
        assertEquals(
            listOf("Taux de réussite : 50 pour cent", "Drawdown maximum : 12,50 pour cent"),
            kpis.map { it.spokenDescription },
        )
    }

    @Test
    fun `a failed performance read omits the win rate and drawdown tiles`() {
        val kpis = dashboardKpis(nav(), null)

        assertEquals(listOf(DashboardKpiKind.CASH, DashboardKpiKind.UNREALIZED), kpis.map { it.kind })
    }

    @Test
    fun `win rate is omitted when there was no trade, the drawdown stays`() {
        val kpis = dashboardKpis(nav(), performance(winRate = null, maxDrawdown = 0.083))

        assertEquals(
            listOf(DashboardKpiKind.CASH, DashboardKpiKind.UNREALIZED, DashboardKpiKind.MAX_DRAWDOWN),
            kpis.map { it.kind },
        )
    }

    @Test
    fun `performance tiles show even when the nav is missing`() {
        val kpis = dashboardKpis(null, performance())

        assertEquals(listOf(DashboardKpiKind.WIN_RATE, DashboardKpiKind.MAX_DRAWDOWN), kpis.map { it.kind })
    }

    @Test
    fun `kpis follow the currency of the active portfolio`() {
        val kpis = dashboardKpis(nav(), null, currencySymbol = "\$")

        assertEquals(listOf("12 000 \$", "+3 475 \$"), kpis.map { it.value.plain() })
        assertEquals("Liquidités : 12 000,00 \$", kpis.first().spokenDescription.plain())
    }

    @Test
    fun `the return is not a tile any more, the hero already shows it`() {
        val kpis = dashboardKpis(nav(), performance())

        assertEquals(false, kpis.any { it.label == "Rendement" })
    }

    @Test
    fun `a negative unrealized pnl is red-toned and spoken as a loss`() {
        val kpi = dashboardKpis(nav(unrealized = "-1250.40"), null).first { it.kind == DashboardKpiKind.UNREALIZED }

        assertEquals("-1 250 €", kpi.value.plain())
        assertEquals(PnlTone.NEGATIVE, kpi.tone)
        assertEquals("Plus-value latente : Perte de 1 250,40 €", kpi.spokenDescription.plain())
    }

    @Test
    fun `an unrealized pnl that rounds to zero is neutral, never a red minus zero`() {
        val kpi = dashboardKpis(nav(unrealized = "-0.40"), null).first { it.kind == DashboardKpiKind.UNREALIZED }

        assertEquals("0 €", kpi.value)
        assertEquals(PnlTone.NEUTRAL, kpi.tone)
    }

    @Test
    fun `corrupted performance values are omitted`() {
        val kpis = dashboardKpis(
            null,
            performance(winRate = Double.POSITIVE_INFINITY, maxDrawdown = 1e9),
        )

        assertEquals(emptyList<DashboardKpi>(), kpis)
    }

    @Test
    fun `nothing loaded means no kpis`() {
        assertEquals(emptyList<DashboardKpi>(), dashboardKpis(null, null))
    }

    // ── riskTileModel ─────────────────────────────────────────────────────────

    @Test
    fun `risk tile is hidden when the status is unknown`() {
        assertNull(riskTileModel(null))
    }

    @Test
    fun `risk tile is hidden when the circuit breaker is closed`() {
        assertNull(riskTileModel(breaker(state = CircuitBreakerState.CLOSED)))
    }

    @Test
    fun `risk tile is hidden when the rule is disabled even if open or redis is down`() {
        assertNull(riskTileModel(breaker(enabled = false, state = CircuitBreakerState.CLOSED)))
        assertNull(riskTileModel(breaker(enabled = false, state = CircuitBreakerState.OPEN)))
        assertNull(riskTileModel(breaker(enabled = false, redisUnavailable = true)))
    }

    @Test
    fun `risk tile shows trading suspended when the circuit breaker is open`() {
        val model = riskTileModel(breaker(state = CircuitBreakerState.OPEN))

        assertNotNull(model)
        assertEquals(RiskTileKind.TRADING_SUSPENDED, model!!.kind)
        assertEquals("Risque", model.label)
        assertEquals("Trading suspendu", model.title)
        assertEquals(
            "Trading suspendu — circuit-breaker ouvert (4 sur 5 violations)",
            model.spokenDescription,
        )
    }

    @Test
    fun `risk tile shows an alert when redis is unavailable even if the breaker reads closed`() {
        val model = riskTileModel(breaker(state = CircuitBreakerState.CLOSED, redisUnavailable = true))

        assertNotNull(model)
        assertEquals(RiskTileKind.STATUS_UNAVAILABLE, model!!.kind)
        assertEquals("Statut indisponible", model.title)
        assertEquals(
            "Statut du risque indisponible — le serveur bloque les ordres par précaution",
            model.spokenDescription,
        )
    }

    @Test
    fun `redis unavailable takes precedence over an open breaker`() {
        val model = riskTileModel(breaker(state = CircuitBreakerState.OPEN, redisUnavailable = true))

        assertEquals(RiskTileKind.STATUS_UNAVAILABLE, model!!.kind)
    }

    // ── riskBannerModel ───────────────────────────────────────────────────────

    @Test
    fun `no risk data and no breaker means no banner`() {
        assertNull(riskBannerModel(null, null))
        assertNull(riskBannerModel(risk(), breaker()))
    }

    @Test
    fun `an active kill switch shows the suspended banner`() {
        val model = riskBannerModel(risk(killSwitch = true), null)

        assertNotNull(model)
        assertEquals(RiskBannerKind.KILL_SWITCH, model!!.kind)
        assertEquals("Trading suspendu — kill switch actif", model.title)
        assertEquals("Trading suspendu : kill switch actif", model.spokenDescription)
        assertEquals(true, model.isBlocking)
        assertEquals(false, model.isPartial)
    }

    @Test
    fun `unresolved violations are counted with the right plural`() {
        val many = riskBannerModel(risk(violations = 3), null)
        assertEquals(RiskBannerKind.VIOLATIONS, many!!.kind)
        assertEquals("3 violations de risque non résolues", many.title)
        assertEquals(false, many.isBlocking)

        assertEquals("1 violation de risque non résolue", riskBannerModel(risk(violations = 1), null)!!.title)
        assertEquals(
            "100+ violations de risque non résolues",
            riskBannerModel(risk(violations = 100), null)!!.title,
        )
    }

    @Test
    fun `a daily loss at 80 percent of the limit or more shows a warning, below it does not`() {
        val model = riskBannerModel(risk(dailyLoss = 0.85), null)

        assertEquals(RiskBannerKind.DAILY_LOSS, model!!.kind)
        assertEquals("Perte du jour à 85% de la limite", model.title)
        assertEquals("Perte du jour à 85 pour cent de la limite", model.spokenDescription)
        assertEquals(false, model.isBlocking)

        assertEquals("Perte du jour à 80% de la limite", riskBannerModel(risk(dailyLoss = 0.8), null)!!.title)
        assertEquals("Perte du jour à 112% de la limite", riskBannerModel(risk(dailyLoss = 1.12), null)!!.title)
        assertNull(riskBannerModel(risk(dailyLoss = 0.79), null))
        assertNull(riskBannerModel(risk(dailyLoss = null), null))
        assertNull(riskBannerModel(risk(dailyLoss = Double.NaN), null))
    }

    @Test
    fun `an open circuit breaker alone shows the banner instead of the old tile`() {
        val model = riskBannerModel(null, breaker(state = CircuitBreakerState.OPEN))

        assertEquals(RiskBannerKind.CIRCUIT_BREAKER_OPEN, model!!.kind)
        assertEquals("Trading suspendu — circuit-breaker ouvert", model.title)
        assertEquals(
            "Trading suspendu — circuit-breaker ouvert (4 sur 5 violations)",
            model.spokenDescription,
        )
        assertEquals(true, model.isBlocking)
    }

    @Test
    fun `redis unavailable shows the unavailable status banner`() {
        val model = riskBannerModel(risk(), breaker(redisUnavailable = true))

        assertEquals(RiskBannerKind.STATUS_UNAVAILABLE, model!!.kind)
        assertEquals("Statut du risque indisponible", model.title)
        assertEquals(true, model.isBlocking)
    }

    @Test
    fun `only the most serious risk is shown, never two banners`() {
        val open = breaker(state = CircuitBreakerState.OPEN)

        // kill switch > circuit-breaker > violations > perte du jour
        assertEquals(
            RiskBannerKind.KILL_SWITCH,
            riskBannerModel(risk(killSwitch = true, violations = 5, dailyLoss = 0.95), open)!!.kind,
        )
        assertEquals(
            RiskBannerKind.CIRCUIT_BREAKER_OPEN,
            riskBannerModel(risk(violations = 5, dailyLoss = 0.95), open)!!.kind,
        )
        assertEquals(
            RiskBannerKind.VIOLATIONS,
            riskBannerModel(risk(violations = 2, dailyLoss = 0.95), breaker())!!.kind,
        )
        assertEquals(
            RiskBannerKind.DAILY_LOSS,
            riskBannerModel(risk(dailyLoss = 0.95), breaker())!!.kind,
        )
    }

    @Test
    fun `partial risk data adds a discreet mention but never invents an alert`() {
        val model = riskBannerModel(risk(killSwitch = true, partial = true), null)

        assertEquals(true, model!!.isPartial)
        assertEquals("Trading suspendu : kill switch actif, données partielles", model.spokenDescription)
        assertEquals("Trading suspendu — kill switch actif", model.title)

        assertNull(riskBannerModel(risk(partial = true), null))
    }

    // ── brokerPillModel ───────────────────────────────────────────────────────

    @Test
    fun `no broker connection means no pill`() {
        assertNull(brokerPillModel(null))
    }

    @Test
    fun `a healthy configured broker shows its code only, never connected or online`() {
        val model = brokerPillModel(PortfolioBrokerStatus("alpaca", "active"))

        assertEquals("Broker : alpaca", model!!.label)
        assertEquals(BrokerTone.NEUTRAL, model.tone)
        assertEquals("Broker : alpaca", model.spokenDescription)
        assertFalse(model.label.contains("connecté", ignoreCase = true))
        assertFalse(model.label.contains("en ligne", ignoreCase = true))
    }

    @Test
    fun `error revoked and inactive statuses become a warning with an explicit label`() {
        fun pill(status: String) = brokerPillModel(PortfolioBrokerStatus("alpaca", status))!!

        assertEquals("Broker : alpaca — connexion en erreur", pill("error").label)
        assertEquals("Broker : alpaca — accès révoqué", pill("revoked").label)
        assertEquals("Broker : alpaca — inactif", pill("inactive").label)
        assertEquals("Broker : alpaca — en maintenance", pill("maintenance").label)
        assertEquals("Broker : alpaca — accès révoqué", pill("REVOKED").label)
        assertEquals(BrokerTone.WARNING, pill("error").tone)
        assertEquals(BrokerTone.WARNING, pill("revoked").tone)
        assertEquals(BrokerTone.WARNING, pill("inactive").tone)
        assertEquals("Attention, Broker : alpaca — accès révoqué", pill("revoked").spokenDescription)
    }

    @Test
    fun `pending and unknown statuses stay neutral`() {
        assertEquals(BrokerTone.NEUTRAL, brokerPillModel(PortfolioBrokerStatus("ibkr", "pending"))!!.tone)
        assertEquals("Broker : ibkr", brokerPillModel(PortfolioBrokerStatus(" ibkr ", "whatever"))!!.label)
        assertEquals("Broker : ibkr", brokerPillModel(PortfolioBrokerStatus("ibkr", null))!!.label)
    }

    @Test
    fun `a status without a broker code only shows when it is degraded`() {
        assertNull(brokerPillModel(PortfolioBrokerStatus(null, "active")))
        assertNull(brokerPillModel(PortfolioBrokerStatus("  ", null)))
        val degraded = brokerPillModel(PortfolioBrokerStatus(null, "revoked"))
        assertEquals("Broker : accès révoqué", degraded!!.label)
        assertEquals(BrokerTone.WARNING, degraded.tone)
    }

    // ── strategiesEntryModel ──────────────────────────────────────────────────

    @Test
    fun `the strategies entry counts active strategies`() {
        assertEquals("3 actives", strategiesEntryModel(3)!!.value)
        assertEquals("1 active", strategiesEntryModel(1)!!.value)
        assertEquals("aucune active", strategiesEntryModel(0)!!.value)
        assertEquals("Stratégies", strategiesEntryModel(3)!!.label)
        assertEquals("Stratégies : 3 actives", strategiesEntryModel(3)!!.spokenDescription)
    }

    @Test
    fun `the strategies entry is absent when the count is unknown`() {
        assertNull(strategiesEntryModel(null))
        assertNull(strategiesEntryModel(-1))
    }

    // ── portfolioOverviewModel ────────────────────────────────────────────────

    private val principal = overviewItem("1", "Principal", value = "103475.29", pnl = "4500.00", pct = 0.045)
    private val us = overviewItem("2", "Actions US", value = "25000.00", pnl = "-300.00", pct = -0.012)

    @Test
    fun `each portfolio row shows its value and the period pnl with amount and percent`() {
        val model = portfolioOverviewModel(listOf(principal, us), activeId = "1")

        assertEquals(listOf("Principal", "Actions US"), model.rows.map { it.name })
        assertEquals(listOf("103 475,29 €", "25 000,00 €"), model.rows.map { it.value.plain() })
        assertEquals(listOf("+4 500,00 € · +4,50%", "-300,00 € · -1,20%"), model.rows.map { it.pnl!!.plain() })
        assertEquals(listOf(PnlTone.POSITIVE, PnlTone.NEGATIVE), model.rows.map { it.tone })
    }

    @Test
    fun `only the active portfolio row is highlighted`() {
        val model = portfolioOverviewModel(listOf(principal, us), activeId = "2")

        assertEquals(listOf(false, true), model.rows.map { it.isActive })
        assertEquals(listOf("1", "2"), model.rows.map { it.portfolioId })
    }

    @Test
    fun `a row is spoken with its name, value, gain or loss and percent, never a raw number`() {
        val model = portfolioOverviewModel(listOf(principal, us), activeId = "1")

        assertEquals(
            "Principal, portefeuille actif, valeur 103 475,29 €, Gain de 4 500,00 €, plus 4,50 pour cent",
            model.rows[0].spokenDescription.plain(),
        )
        assertEquals(
            "Actions US, valeur 25 000,00 €, Perte de 300,00 €, moins 1,20 pour cent",
            model.rows[1].spokenDescription.plain(),
        )
    }

    @Test
    fun `the total sums values and pnl when every portfolio has the same currency`() {
        val model = portfolioOverviewModel(listOf(principal, us), activeId = "1")

        val total = model.total!!
        assertEquals("128 475,29 €", total.value.plain())
        assertEquals("+4 200,00 €", total.pnl!!.plain())
        assertEquals(PnlTone.POSITIVE, total.tone)
        assertEquals("Total, valeur 128 475,29 €, Gain de 4 200,00 €", total.spokenDescription.plain())
    }

    @Test
    fun `there is no total when currencies differ, and each row keeps its own symbol`() {
        val usd = overviewItem("2", "Actions US", currency = "USD", value = "25000.00", pnl = "-300.00", pct = -0.012)
        val model = portfolioOverviewModel(listOf(principal, usd), activeId = "1")

        assertNull(model.total)
        assertEquals("25 000,00 \$", model.rows[1].value.plain())
        assertEquals("-300,00 \$ · -1,20%", model.rows[1].pnl!!.plain())
    }

    @Test
    fun `there is no total for a single portfolio`() {
        assertNull(portfolioOverviewModel(listOf(principal), activeId = "1").total)
    }

    @Test
    fun `the total keeps its value but drops the pnl when one portfolio has none`() {
        val unknown = overviewItem("3", "Nouveau", value = "1000.00", pnl = null, pct = null)
        val model = portfolioOverviewModel(listOf(principal, unknown), activeId = "1")

        assertEquals("104 475,29 €", model.total!!.value.plain())
        assertNull(model.total!!.pnl)
        assertEquals(PnlTone.NEUTRAL, model.total!!.tone)
        assertEquals("Total, valeur 104 475,29 €", model.total!!.spokenDescription.plain())
        assertNull(model.rows[1].pnl)
        assertEquals("Nouveau, valeur 1 000,00 €", model.rows[1].spokenDescription.plain())
    }

    @Test
    fun `a pnl that rounds to zero is neutral, never a signed zero`() {
        val flat = overviewItem("3", "Plat", pnl = "-0.004", pct = -0.00004)
        val row = portfolioOverviewModel(listOf(flat, principal), activeId = "1").rows[0]

        assertEquals("0,00 € · 0,00%", row.pnl)
        assertEquals(PnlTone.NEUTRAL, row.tone)
    }

    @Test
    fun `a row with only a percent shows the percent with its own tone`() {
        val row = portfolioOverviewModel(
            listOf(overviewItem("3", "Pct", pnl = null, pct = 0.02), principal),
            activeId = "1",
        ).rows[0]

        assertEquals("+2,00%", row.pnl)
        assertEquals(PnlTone.POSITIVE, row.tone)
    }

    @Test
    fun `the portfolios card only exists for several portfolios and with data or a first load`() {
        assertEquals(false, shouldShowPortfolioOverview(portfolioCount = 1, hasValue = true, isLoading = false))
        assertEquals(false, shouldShowPortfolioOverview(portfolioCount = 0, hasValue = true, isLoading = true))
        assertEquals(false, shouldShowPortfolioOverview(portfolioCount = 2, hasValue = false, isLoading = false))
        assertEquals(true, shouldShowPortfolioOverview(portfolioCount = 2, hasValue = true, isLoading = false))
        assertEquals(true, shouldShowPortfolioOverview(portfolioCount = 3, hasValue = false, isLoading = true))
    }

    @Test
    fun `the hero variation is spoken in the currency of the active portfolio`() {
        assertEquals(
            "P&L : Gain de 45,00 \$, plus 4,50 pour cent",
            variationSpokenDescription(BigDecimal("45.00"), 0.045, "\$"),
        )
    }

    // ── recentActivity ────────────────────────────────────────────────────────

    private fun catalyst(title: String, at: Instant) = ActivityItem.CatalystEvent(
        symbol = "AAPL",
        eventType = "earnings",
        title = title,
        timestamp = at,
    )

    @Test
    fun `recent activity keeps the 3 newest items, newest first`() {
        val items = listOf(
            catalyst("b", t0.plusSeconds(20)),
            catalyst("e", t0.plusSeconds(50)),
            catalyst("a", t0.plusSeconds(10)),
            catalyst("d", t0.plusSeconds(40)),
            catalyst("c", t0.plusSeconds(30)),
        )

        assertEquals(
            listOf("e", "d", "c"),
            recentActivity(items).map { (it as ActivityItem.CatalystEvent).title },
        )
    }

    @Test
    fun `recent activity honours an explicit limit`() {
        val items = listOf(catalyst("a", t0), catalyst("b", t0.plusSeconds(5)))

        assertEquals(
            listOf("b"),
            recentActivity(items, limit = 1).map { (it as ActivityItem.CatalystEvent).title },
        )
    }

    @Test
    fun `recent activity returns everything when there are fewer items than the limit`() {
        val items = listOf(catalyst("a", t0), catalyst("b", t0.plusSeconds(5)))

        assertEquals(2, recentActivity(items).size)
        assertEquals(emptyList<ActivityItem>(), recentActivity(emptyList()))
    }

    // ── formatRelativeTime ────────────────────────────────────────────────────

    @Test
    fun `relative time switches unit at 10 seconds, 1 minute, 1 hour and 1 day`() {
        fun at(secondsAgo: Long) = formatRelativeTime(t0, now = t0.plusSeconds(secondsAgo))

        assertEquals("maintenant", at(0))
        assertEquals("maintenant", at(9))
        assertEquals("il y a 10s", at(10))
        assertEquals("il y a 59s", at(59))
        assertEquals("il y a 1m", at(60))
        assertEquals("il y a 59m", at(3_599))
        assertEquals("il y a 1h", at(3_600))
        assertEquals("il y a 23h", at(86_399))
        assertEquals("il y a 1j", at(86_400))
        assertEquals("il y a 3j", at(3 * 86_400L))
    }

    @Test
    fun `relative time of a future timestamp is now`() {
        assertEquals("maintenant", formatRelativeTime(t0.plusSeconds(30), now = t0))
    }

    // ── activityRowModel ──────────────────────────────────────────────────────

    @Test
    fun `order row translates the side and shows the status`() {
        val model = activityRowModel(
            ActivityItem.OrderFilled("o1", "AAPL", "buy", "filled", 10, t0),
        )

        assertEquals(ActivityDot.SUCCESS, model.dot)
        assertEquals("Achat 10 × AAPL", model.label)
        assertEquals("filled", model.subtext)
        assertEquals("Ordre : Achat de 10 AAPL, statut filled", model.description)
    }

    @Test
    fun `order row without quantity shows a question mark and unknown sides are kept`() {
        val model = activityRowModel(
            ActivityItem.OrderFilled("o1", "AAPL", "hold", "pending", null, t0),
        )

        assertEquals("hold ? × AAPL", model.label)
        assertEquals(
            "Vente 5 × MSFT",
            activityRowModel(ActivityItem.OrderFilled("o2", "MSFT", "SELL", "filled", 5, t0)).label,
        )
    }

    @Test
    fun `signal row shows the action in capitals and the confidence as a percentage`() {
        val model = activityRowModel(ActivityItem.Signal("MSFT", "sell", 0.8, "momentum", t0))

        assertEquals(ActivityDot.PRIMARY, model.dot)
        assertEquals("Signal : MSFT (SELL 80%)", model.label)
        assertEquals("momentum", model.subtext)
        assertEquals("Signal de stratégie : sell MSFT avec confiance 80 pourcent", model.description)
    }

    @Test
    fun `risk alert dot is offline for error and critical, warning otherwise`() {
        fun dot(severity: String) =
            activityRowModel(ActivityItem.RiskAlert("Marge", "Appel de marge", severity, t0)).dot

        assertEquals(ActivityDot.OFFLINE, dot("critical"))
        assertEquals(ActivityDot.OFFLINE, dot("ERROR"))
        assertEquals(ActivityDot.WARNING, dot("warning"))
        assertEquals(ActivityDot.WARNING, dot("info"))

        val model = activityRowModel(ActivityItem.RiskAlert("Marge", "Appel de marge", "critical", t0))
        assertEquals("Alerte : Marge", model.label)
        assertEquals("Appel de marge", model.subtext)
        assertEquals("Alerte critical : Marge. Appel de marge", model.description)
    }

    @Test
    fun `portfolio change row lists the executed trade and the total value`() {
        val model = activityRowModel(
            ActivityItem.PortfolioChange(
                totalValue = 1234.5,
                symbol = "AAPL",
                side = "buy",
                quantity = 1.5,
                price = 190.0,
                dailyPnl = null,
                timestamp = t0,
            ),
        )

        assertEquals(ActivityDot.INFO, model.dot)
        assertEquals("Portfolio : Achat 1,50 × AAPL | Valeur totale 1234,50", model.label)
        assertEquals("Mise à jour portfolio", model.subtext)
        assertEquals("Mise à jour portfolio : Achat 1,50 × AAPL, Valeur totale 1234,50", model.description)
    }

    @Test
    fun `portfolio change row shows the signed daily pnl when present`() {
        fun label(dailyPnl: Double) = activityRowModel(
            ActivityItem.PortfolioChange(null, null, null, null, null, dailyPnl, t0),
        ).label

        assertEquals("Portfolio : P&L jour -12,50", label(-12.5))
        assertEquals("Portfolio : P&L jour +3,00", label(3.0))
    }

    @Test
    fun `portfolio change row without data falls back to a generic message`() {
        val model = activityRowModel(
            ActivityItem.PortfolioChange(null, null, null, null, null, null, t0),
        )

        assertEquals("Portfolio mis à jour", model.label)
        assertEquals("Mise à jour portfolio : valeurs mises à jour", model.description)
    }

    @Test
    fun `catalyst row capitalises the event type`() {
        val model = activityRowModel(catalyst("Résultats T3", t0))

        assertEquals(ActivityDot.TERTIARY, model.dot)
        assertEquals("Catalyst : AAPL — Résultats T3", model.label)
        assertEquals("Earnings", model.subtext)
        assertEquals("Événement catalyseur : Résultats T3 pour AAPL, type earnings", model.description)
    }
}
