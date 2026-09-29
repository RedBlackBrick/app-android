package com.tradingplatform.app.domain.model

import java.math.BigDecimal

/**
 * Une ligne de « Mes portefeuilles » : valeur courante + P&L de la période demandée.
 *
 * [currency] est le code devise du portefeuille (« EUR », « USD »). [periodPnl] est en devise du
 * portefeuille ; [periodPnlPct] est une **FRACTION** (0.045 = 4,5 %), jamais un pourcentage.
 * Les deux sont `null` quand la source ne les fournit pas (ex. capital initial nul).
 */
data class PortfolioOverviewItem(
    val portfolioId: String,
    val name: String,
    val currency: String,
    val currentValue: BigDecimal,
    val periodPnl: BigDecimal?,
    val periodPnlPct: Double?,
)
