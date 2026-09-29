package com.tradingplatform.app.data.model

import com.tradingplatform.app.data.local.db.entity.PnlSnapshotEntity
import com.tradingplatform.app.domain.model.NavPoint
import com.tradingplatform.app.domain.model.PnlPeriod
import com.tradingplatform.app.domain.model.PnlSummary
import com.tradingplatform.app.domain.model.Portfolio
import com.tradingplatform.app.domain.model.PortfolioBrokerStatus
import com.tradingplatform.app.domain.model.PortfolioOverviewItem
import com.tradingplatform.app.domain.util.parseInstantOrNull
import java.math.BigDecimal
import java.math.MathContext

/**
 * Mappers DTO → domaine des lectures « vue d'ensemble » (batch P&L, dashboard/overview,
 * value-history, broker-connection). Les domain models `*Pct` sont des FRACTIONS.
 */

/** Decimal backend (chaîne) → BigDecimal ; `null` si absent ou illisible (jamais d'exception). */
internal fun parseDecimal(raw: String?): BigDecimal? = raw?.trim()?.toBigDecimalOrNull()

/**
 * P&L de la période en FRACTION. `batch/pnl.pnl_pct` est DÉJÀ une fraction (`delta / previous`,
 * pas x100) : aucune division par 100 ici, contrairement à `/pnl.total_pnl_percent`.
 * Si `pnl_pct` est absent ou non fini, dérivé de `pnl_amount / previous_value` ; `null` si impossible.
 */
internal fun BatchPnlItemDto.pnlFraction(): Double? {
    pnlPct?.takeIf { it.isFinite() }?.let { return it }
    val delta = parseDecimal(pnlAmount) ?: return null
    val previous = parseDecimal(previousValue) ?: return null
    if (previous.signum() == 0) return null
    return delta.divide(previous, MathContext.DECIMAL64).toDouble()
}

/**
 * Élément `batch/pnl` → ligne de « Mes portefeuilles ». Nom et devise viennent de la liste des
 * portefeuilles ([portfolio]). `null` si l'id était inconnu du backend (`currency_code` null,
 * montants à « 0 » sans signification) ou si la valeur courante est illisible.
 */
internal fun BatchPnlItemDto.toOverviewItem(portfolio: Portfolio): PortfolioOverviewItem? {
    if (currencyCode == null) return null
    val current = parseDecimal(currentValue) ?: return null
    return PortfolioOverviewItem(
        portfolioId = portfolio.id,
        name = portfolio.name,
        currency = portfolio.currency,
        currentValue = current,
        periodPnl = parseDecimal(pnlAmount),
        periodPnlPct = pnlFraction(),
    )
}

/**
 * Élément `batch/pnl` → [PnlSummary] de la période. Ni ratios de risque ni win rate
 * (`winning_trades` de `/pnl` n'est pas un taux de réussite, contrat §9) ni compteurs de trades :
 * `batch/pnl` ne les fournit pas. `null` si le montant est absent ou illisible.
 */
internal fun BatchPnlItemDto.toPnlSummary(): PnlSummary? {
    val amount = parseDecimal(pnlAmount) ?: return null
    return PnlSummary(
        totalReturn = amount,
        totalReturnPct = pnlFraction(),
        sharpeRatio = null,
        sortinoRatio = null,
        maxDrawdown = null,
        volatility = null,
        cagr = null,
        winRate = null,
        profitFactor = null,
        avgTradeReturn = null,
    )
}

/**
 * Élément `batch/pnl` → ligne `pnl_snapshots` de [period] (lue par `PnlWidget` : `totalPnl` et
 * `totalPnlPercent` uniquement). Le schéma Room impose des colonnes non nulles que `batch/pnl` ne
 * fournit pas (P&L réalisé/latent, compteurs de trades) : elles sont écrites à « 0 » — une valeur
 * de vie entière de `/pnl` n'aurait aucun sens dans une ligne de période. `null` si le montant est
 * absent ou illisible.
 */
internal fun BatchPnlItemDto.toEntity(period: PnlPeriod, syncedAt: Long): PnlSnapshotEntity? {
    val amount = parseDecimal(pnlAmount) ?: return null
    return PnlSnapshotEntity(
        period = period.toApiString(),
        realizedPnl = "0",
        unrealizedPnl = "0",
        totalPnl = amount.toPlainString(),
        totalPnlPercent = pnlFraction() ?: 0.0,
        tradesCount = 0,
        winningTrades = 0,
        losingTrades = 0,
        syncedAt = syncedAt,
    )
}

/**
 * Portefeuille de `dashboard/overview` → ligne pour les périodes ALL/YEAR : P&L = valeur courante
 * − capital initial ; pourcentage (FRACTION) = P&L / capital initial, `null` si le capital initial
 * est absent ou nul. Nom et devise viennent de la liste ([portfolio]). `null` si la valeur
 * courante est illisible. (`today_pnl_pct` n'est pas utilisé : c'est un pourcentage 24 h glissantes.)
 */
internal fun DashboardPortfolioDto.toOverviewItem(portfolio: Portfolio): PortfolioOverviewItem? {
    val current = parseDecimal(currentValue) ?: return null
    val initial = parseDecimal(initialCapital)
    val pnl = initial?.let { current.subtract(it) }
    val fraction = if (initial != null && pnl != null && initial.signum() != 0) {
        pnl.divide(initial, MathContext.DECIMAL64).toDouble()
    } else {
        null
    }
    return PortfolioOverviewItem(
        portfolioId = portfolio.id,
        name = portfolio.name,
        currency = portfolio.currency,
        currentValue = current,
        periodPnl = pnl,
        periodPnlPct = fraction,
    )
}

/** Snapshot `value-history` → point de courbe ; `null` si l'horodatage ou la valeur est illisible. */
internal fun ValueHistoryPointDto.toNavPointOrNull(): NavPoint? {
    val at = recordedAt.parseInstantOrNull() ?: return null
    val value = parseDecimal(totalValue) ?: return null
    return NavPoint(at = at, value = value)
}

internal fun PortfolioBrokerConnectionDto.toDomain(): PortfolioBrokerStatus =
    PortfolioBrokerStatus(brokerCode = brokerCode, connectionStatus = connectionStatus)
