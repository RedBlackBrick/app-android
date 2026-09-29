package com.tradingplatform.app.ui.screens.dashboard

import com.tradingplatform.app.domain.model.ActivityItem
import com.tradingplatform.app.domain.model.CircuitBreakerState
import com.tradingplatform.app.domain.model.NavSummary
import com.tradingplatform.app.domain.model.PnlPeriod
import com.tradingplatform.app.domain.model.PnlSummary
import com.tradingplatform.app.domain.model.PortfolioCircuitBreakerStatus
import com.tradingplatform.app.ui.common.DataState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * Tests JVM de la logique de présentation pure du Dashboard : formatage des pourcentages,
 * KPI, tuile risque, pied du héros, découpage et libellés du flux d'activité.
 * Les attendus sont des valeurs littérales (jamais recalculées avec le code testé).
 */
class DashboardPresentationTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private fun pnl(
        totalReturn: BigDecimal? = BigDecimal("4500.00"),
        totalReturnPct: Double? = 0.045,
        maxDrawdown: Double? = null,
        winRate: Double? = 0.7,
    ) = PnlSummary(
        totalReturn = totalReturn,
        totalReturnPct = totalReturnPct,
        sharpeRatio = null,
        sortinoRatio = null,
        maxDrawdown = maxDrawdown,
        volatility = null,
        cagr = null,
        winRate = winRate,
        profitFactor = null,
        avgTradeReturn = null,
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
    fun `period labels are the historical French chip labels`() {
        assertEquals("Jour", dashboardPeriodLabel(PnlPeriod.DAY))
        assertEquals("Sem.", dashboardPeriodLabel(PnlPeriod.WEEK))
        assertEquals("Mois", dashboardPeriodLabel(PnlPeriod.MONTH))
        assertEquals("Année", dashboardPeriodLabel(PnlPeriod.YEAR))
        assertEquals("Tout", dashboardPeriodLabel(PnlPeriod.ALL))
    }

    @Test
    fun `selector offers day week month year in that order and not all`() {
        assertEquals(
            listOf(PnlPeriod.DAY, PnlPeriod.WEEK, PnlPeriod.MONTH, PnlPeriod.YEAR),
            DASHBOARD_PERIODS,
        )
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

    // ── dashboardKpis ─────────────────────────────────────────────────────────

    @Test
    fun `kpis are cash then unrealized then win rate, capped at three`() {
        val kpis = dashboardKpis(nav(), pnl(winRate = 0.7, maxDrawdown = 0.083))

        assertEquals(
            listOf(DashboardKpiKind.CASH, DashboardKpiKind.UNREALIZED, DashboardKpiKind.WIN_RATE),
            kpis.map { it.kind },
        )
        assertEquals(listOf("Liquidités", "Latent", "Win rate"), kpis.map { it.label })
        assertEquals(listOf("12 000 €", "+3 475 €", "70%"), kpis.map { it.value.replace('\u202f', ' ').replace('\u00a0', ' ') })
        assertEquals(
            listOf(PnlTone.NEUTRAL, PnlTone.POSITIVE, PnlTone.NEUTRAL),
            kpis.map { it.tone },
        )
    }

    @Test
    fun `the return is not a tile any more, the hero already shows it`() {
        val kpis = dashboardKpis(nav(), pnl(totalReturnPct = 0.045, winRate = 0.7))

        assertEquals(false, kpis.any { it.label == "Rendement" })
    }

    @Test
    fun `a negative unrealized pnl is red-toned and spoken as a loss`() {
        val kpi = dashboardKpis(nav(unrealized = "-1250.40"), null).first { it.kind == DashboardKpiKind.UNREALIZED }

        assertEquals("-1 250 €", kpi.value.replace('\u202f', ' ').replace('\u00a0', ' '))
        assertEquals(PnlTone.NEGATIVE, kpi.tone)
        assertEquals(
            "Plus-value latente : Perte de 1 250,40 €",
            kpi.spokenDescription.replace('\u202f', ' ').replace('\u00a0', ' '),
        )
    }

    @Test
    fun `an unrealized pnl that rounds to zero is neutral, never a red minus zero`() {
        val kpi = dashboardKpis(nav(unrealized = "-0.40"), null).first { it.kind == DashboardKpiKind.UNREALIZED }

        assertEquals("0 €", kpi.value)
        assertEquals(PnlTone.NEUTRAL, kpi.tone)
    }

    @Test
    fun `the drawdown only shows when a tile is missing, as the real pnl endpoint has none`() {
        val withoutNav = dashboardKpis(null, pnl(winRate = null, maxDrawdown = 0.083))

        assertEquals(listOf(DashboardKpiKind.MAX_DRAWDOWN), withoutNav.map { it.kind })
        assertEquals("8,30%", withoutNav.single().value)
    }

    @Test
    fun `win rate is omitted when there was no trade in the period`() {
        val kpis = dashboardKpis(nav(), pnl(winRate = null))

        assertEquals(listOf(DashboardKpiKind.CASH, DashboardKpiKind.UNREALIZED), kpis.map { it.kind })
    }

    @Test
    fun `corrupted pnl values are omitted`() {
        val kpis = dashboardKpis(
            null,
            pnl(totalReturnPct = Double.NaN, winRate = Double.POSITIVE_INFINITY, maxDrawdown = 1e9),
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
