package com.tradingplatform.app.ui.screens.dashboard

import com.tradingplatform.app.domain.model.ActivityItem
import com.tradingplatform.app.domain.model.CircuitBreakerState
import com.tradingplatform.app.domain.model.NavSummary
import com.tradingplatform.app.domain.model.PnlPeriod
import com.tradingplatform.app.domain.model.PnlSummary
import com.tradingplatform.app.domain.model.PortfolioCircuitBreakerStatus
import com.tradingplatform.app.ui.common.DataState
import com.tradingplatform.app.ui.components.buildPnlDescription
import com.tradingplatform.app.ui.components.formatMoneyAmount
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.abs

/*
 * Logique de présentation PURE du Dashboard (aucune dépendance Compose/Android) : formatage,
 * dérivation des KPI, décision « afficher la tuile risque », découpage du flux d'activité.
 * Les composables ne font que rendre ces modèles — tout est testé en JVM
 * (DashboardPresentationTest).
 */

// ── Périodes ──────────────────────────────────────────────────────────────────

/** Périodes proposées par le sélecteur du Dashboard (l'ordre est celui de l'affichage). */
internal val DASHBOARD_PERIODS: List<PnlPeriod> =
    listOf(PnlPeriod.DAY, PnlPeriod.WEEK, PnlPeriod.MONTH, PnlPeriod.YEAR)

/** Libellé court d'une période (« Sem. » : « Semaine » est tronqué sur 4 segments en 360 dp). */
internal fun dashboardPeriodLabel(period: PnlPeriod): String = when (period) {
    PnlPeriod.DAY -> "Jour"
    PnlPeriod.WEEK -> "Sem."
    PnlPeriod.MONTH -> "Mois"
    PnlPeriod.YEAR -> "Année"
    PnlPeriod.ALL -> "Tout"
}

// ── Pourcentages ──────────────────────────────────────────────────────────────

/** Affiché à la place d'une valeur absente ou inexploitable. */
internal const val NO_VALUE = "—"

/**
 * Au-delà de cette magnitude (fraction : 1 000 000 = 100 000 000 %), la valeur est considérée
 * comme corrompue et n'est pas affichée (même esprit que la garde d'`AnimatedPnlText`).
 */
private const val MAX_DISPLAYABLE_FRACTION = 1_000_000.0

/** Sens d'une valeur de P&L / de rendement — pilote la couleur (jamais rouge/vert en dur). */
internal enum class PnlTone { POSITIVE, NEGATIVE, NEUTRAL }

/**
 * Fraction (0.045) → pourcentage arrondi à [decimals] décimales (4.50), ou `null` si la valeur
 * est absente, non finie ou hors garde. L'arrondi est fait en BigDecimal (HALF_UP) : le signe et
 * le ton sont donc toujours ceux de la valeur AFFICHÉE (jamais de « -0,00% »).
 */
private fun roundedPercent(fraction: Double?, decimals: Int): BigDecimal? {
    if (fraction == null || !fraction.isFinite() || abs(fraction) >= MAX_DISPLAYABLE_FRACTION) return null
    return BigDecimal.valueOf(fraction).movePointRight(2).setScale(decimals, RoundingMode.HALF_UP)
}

/**
 * Formate une fraction en pourcentage français : `0.045` → « 4,50% » ; « +4,50% » si [signed].
 * Séparateur décimal `,` fixe (Locale.FRENCH) ; `null` / non fini → [NO_VALUE].
 */
internal fun formatPercent(fraction: Double?, signed: Boolean = false, decimals: Int = 2): String {
    val pct = roundedPercent(fraction, decimals) ?: return NO_VALUE
    val sign = when {
        pct.signum() < 0 -> "-"
        pct.signum() > 0 && signed -> "+"
        else -> ""
    }
    return "$sign${String.format(Locale.FRENCH, "%.${decimals}f", pct.abs())}%"
}

/**
 * Forme parlée pour TalkBack : « plus 4,50 pour cent » / « moins 1,20 pour cent » / « 70 pour
 * cent ». Le signe est dit en toutes lettres (« -1,20 » est souvent lu comme « 1,20 »).
 */
internal fun spokenPercent(fraction: Double?, signed: Boolean = false, decimals: Int = 2): String {
    val pct = roundedPercent(fraction, decimals) ?: return "indisponible"
    val sign = when {
        pct.signum() < 0 -> "moins "
        pct.signum() > 0 && signed -> "plus "
        else -> ""
    }
    return "$sign${String.format(Locale.FRENCH, "%.${decimals}f", pct.abs())} pour cent"
}

/** Ton d'un pourcentage d'après sa valeur arrondie (`0,00%` → neutre). */
internal fun percentTone(fraction: Double?, decimals: Int = 2): PnlTone {
    val pct = roundedPercent(fraction, decimals) ?: return PnlTone.NEUTRAL
    return when {
        pct.signum() > 0 -> PnlTone.POSITIVE
        pct.signum() < 0 -> PnlTone.NEGATIVE
        else -> PnlTone.NEUTRAL
    }
}

// ── Héros : description TalkBack et pied de carte ─────────────────────────────

/**
 * Description unique de la ligne de variation du héros, ex.
 * « P&L : Gain de 45,00 €, plus 4,50 pour cent ». Réutilise la forme verbose canonique des
 * montants (`buildPnlDescription`) : « Gain » / « Perte » plutôt qu'un signe.
 */
internal fun variationSpokenDescription(totalReturn: BigDecimal?, totalReturnPct: Double?): String {
    val parts = mutableListOf<String>()
    if (totalReturn != null) parts += buildPnlDescription(totalReturn, "€")
    if (roundedPercent(totalReturnPct, 2) != null) parts += spokenPercent(totalReturnPct, signed = true)
    return if (parts.isEmpty()) "P&L indisponible" else "P&L : ${parts.joinToString(", ")}"
}

/** Pied de la carte héros, commun à la NAV et au P&L. */
internal sealed interface HeroFooter {
    /** Rien à signaler. */
    data object None : HeroFooter

    /** Valeur affichée mais dernier refresh en échec → horodatage de la dernière sync. */
    data class Stale(val syncedAt: Long) : HeroFooter

    /** Section jamais chargée et en erreur → bandeau d'erreur avec « Réessayer ». */
    data class Error(val message: String) : HeroFooter
}

/**
 * Règle des anciens pieds de section, fusionnée pour la carte unique :
 * - une section sans valeur et en erreur → [HeroFooter.Error] (NAV prioritaire) ;
 * - sinon, des valeurs périmées (valeur + erreur) → [HeroFooter.Stale] avec la sync la plus
 *   ancienne (c'est la fraîcheur du plus vieux chiffre affiché qui compte) ;
 * - sinon [HeroFooter.None].
 */
internal fun heroFooter(nav: DataState<*>, pnl: DataState<*>): HeroFooter {
    val states = listOf(nav, pnl)
    for (state in states) {
        val error = state.error
        if (state.value == null && error != null) return HeroFooter.Error(error)
    }
    val stale = states.filter { it.value != null && it.error != null }
    return if (stale.isEmpty()) HeroFooter.None else HeroFooter.Stale(stale.minOf { it.syncedAt })
}

// ── Tuiles KPI ────────────────────────────────────────────────────────────────

internal enum class DashboardKpiKind { CASH, UNREALIZED, WIN_RATE, MAX_DRAWDOWN }

/** Nombre maximal de tuiles KPI : au-delà, la ligne devient illisible à largeur de téléphone. */
internal const val DASHBOARD_KPI_LIMIT = 3

/**
 * Une tuile KPI prête à afficher.
 *
 * @property spokenDescription phrase complète pour TalkBack (ex. « Liquidités : 12 000,00 € »).
 */
internal data class DashboardKpi(
    val kind: DashboardKpiKind,
    val label: String,
    val value: String,
    val spokenDescription: String,
    val tone: PnlTone,
)

/** Montant arrondi à l'euro pour une tuile (les centimes ne tiennent pas dans 1/3 de largeur). */
private fun wholeEuros(amount: BigDecimal): BigDecimal = amount.setScale(0, RoundingMode.HALF_UP)

/**
 * KPI du Dashboard, dans cet ordre : Liquidités, Latent (P&L non réalisé), Win rate, Drawdown max.
 * Le rendement n'y figure plus : il est déjà dans la ligne « P&L » du héros.
 *
 * Une tuile sans valeur exploitable est OMISE (pas de tuile « — » : l'écran reste concis) et la
 * ligne est plafonnée à [DASHBOARD_KPI_LIMIT] tuiles. Le drawdown n'apparaît que si la source
 * `/pnl` le fournit (`PnlSummary.maxDrawdown`) ; il ne passe donc la coupe que si une autre
 * tuile manque.
 */
internal fun dashboardKpis(nav: NavSummary?, pnl: PnlSummary?): List<DashboardKpi> = buildList {
    if (nav != null) {
        val cash = wholeEuros(nav.cashBalance)
        add(
            DashboardKpi(
                kind = DashboardKpiKind.CASH,
                label = "Liquidités",
                value = formatMoneyAmount(cash, "€", decimals = 0),
                spokenDescription = "Liquidités : ${formatMoneyAmount(nav.cashBalance, "€")}",
                tone = PnlTone.NEUTRAL,
            ),
        )
        val unrealized = wholeEuros(nav.totalUnrealizedPnl)
        add(
            DashboardKpi(
                kind = DashboardKpiKind.UNREALIZED,
                label = "Latent",
                // Le ton et le signe suivent la valeur AFFICHÉE (jamais de « -0 € » rouge).
                value = (if (unrealized.signum() > 0) "+" else "") + formatMoneyAmount(unrealized, "€", decimals = 0),
                spokenDescription = "Plus-value latente : ${buildPnlDescription(nav.totalUnrealizedPnl, "€")}",
                tone = when {
                    unrealized.signum() > 0 -> PnlTone.POSITIVE
                    unrealized.signum() < 0 -> PnlTone.NEGATIVE
                    else -> PnlTone.NEUTRAL
                },
            ),
        )
    }
    if (pnl != null) {
        val winRate = pnl.winRate
        if (roundedPercent(winRate, 0) != null) {
            add(
                DashboardKpi(
                    kind = DashboardKpiKind.WIN_RATE,
                    label = "Win rate",
                    value = formatPercent(winRate, decimals = 0),
                    spokenDescription = "Taux de réussite : ${spokenPercent(winRate, decimals = 0)}",
                    tone = PnlTone.NEUTRAL,
                ),
            )
        }
        val maxDrawdown = pnl.maxDrawdown
        if (roundedPercent(maxDrawdown, 2) != null) {
            add(
                DashboardKpi(
                    kind = DashboardKpiKind.MAX_DRAWDOWN,
                    label = "Drawdown max",
                    value = formatPercent(maxDrawdown),
                    spokenDescription = "Drawdown maximum : ${spokenPercent(maxDrawdown)}",
                    tone = PnlTone.NEUTRAL,
                ),
            )
        }
    }
}.take(DASHBOARD_KPI_LIMIT)

// ── Tuile risque (circuit-breaker) ────────────────────────────────────────────

internal enum class RiskTileKind { TRADING_SUSPENDED, STATUS_UNAVAILABLE }

internal data class RiskTileModel(
    val kind: RiskTileKind,
    val label: String,
    val title: String,
    val spokenDescription: String,
)

/**
 * Décide si la tuile risque s'affiche, et avec quel contenu. Seuls deux cas sont dignes d'attention :
 * - circuit-breaker OUVERT → « Trading suspendu » ;
 * - Redis indisponible (le backend échoue en fermé : les ordres sont bloqués sans compteur connu).
 *
 * Statut inconnu (`null`), règle désactivée ou circuit fermé → `null` (tuile masquée).
 */
internal fun riskTileModel(status: PortfolioCircuitBreakerStatus?): RiskTileModel? {
    if (status == null || !status.enabled) return null
    return when {
        status.redisUnavailable -> RiskTileModel(
            kind = RiskTileKind.STATUS_UNAVAILABLE,
            label = "Risque",
            title = "Statut indisponible",
            spokenDescription = "Statut du risque indisponible — le serveur bloque les ordres par précaution",
        )
        status.state == CircuitBreakerState.OPEN -> RiskTileModel(
            kind = RiskTileKind.TRADING_SUSPENDED,
            label = "Risque",
            title = "Trading suspendu",
            spokenDescription =
                "Trading suspendu — circuit-breaker ouvert (${status.count} sur ${status.threshold} violations)",
        )
        else -> null
    }
}

// ── Flux d'activité ───────────────────────────────────────────────────────────

/** Nombre d'éléments d'activité montrés sur le Dashboard. */
internal const val DASHBOARD_ACTIVITY_LIMIT = 3

/** Les [limit] éléments les plus récents, du plus récent au plus ancien. */
internal fun recentActivity(
    items: List<ActivityItem>,
    limit: Int = DASHBOARD_ACTIVITY_LIMIT,
): List<ActivityItem> = items.sortedByDescending { it.timestamp }.take(limit)

/**
 * Temps relatif en français : « maintenant », « il y a 2m », « il y a 1h », « il y a 3j ».
 * [now] est injectable pour les tests.
 */
internal fun formatRelativeTime(timestamp: Instant, now: Instant = Instant.now()): String {
    val seconds = Duration.between(timestamp, now).seconds
    return when {
        seconds < 10 -> "maintenant"
        seconds < 60 -> "il y a ${seconds}s"
        seconds < 3600 -> "il y a ${seconds / 60}m"
        seconds < 86400 -> "il y a ${seconds / 3600}h"
        else -> "il y a ${seconds / 86400}j"
    }
}

/** Couleur du point d'une ligne d'activité — mappée sur le thème par le composable. */
internal enum class ActivityDot { SUCCESS, PRIMARY, WARNING, OFFLINE, INFO, TERTIARY }

/** Ligne d'activité prête à afficher ; [description] est la phrase TalkBack (sans l'heure). */
internal data class ActivityRowModel(
    val dot: ActivityDot,
    val label: String,
    val subtext: String,
    val description: String,
)

internal fun activityRowModel(item: ActivityItem): ActivityRowModel = when (item) {
    is ActivityItem.OrderFilled -> {
        val sideLabel = when (item.side.lowercase()) {
            "buy" -> "Achat"
            "sell" -> "Vente"
            else -> item.side
        }
        val qtyText = item.quantity?.toString() ?: "?"
        ActivityRowModel(
            dot = ActivityDot.SUCCESS,
            label = "$sideLabel $qtyText × ${item.symbol}",
            subtext = item.status,
            description = "Ordre : $sideLabel de $qtyText ${item.symbol}, statut ${item.status}",
        )
    }
    is ActivityItem.Signal -> {
        val pct = String.format(Locale.FRENCH, "%.0f", item.confidence * 100)
        ActivityRowModel(
            dot = ActivityDot.PRIMARY,
            label = "Signal : ${item.symbol} (${item.action.uppercase()} $pct%)",
            subtext = item.strategyType,
            description = "Signal de stratégie : ${item.action} ${item.symbol} avec confiance $pct pourcent",
        )
    }
    is ActivityItem.RiskAlert -> ActivityRowModel(
        dot = when (item.severity.lowercase()) {
            "error", "critical" -> ActivityDot.OFFLINE
            else -> ActivityDot.WARNING
        },
        label = "Alerte : ${item.title}",
        subtext = item.body,
        description = "Alerte ${item.severity} : ${item.title}. ${item.body}",
    )
    is ActivityItem.PortfolioChange -> {
        val sideLabel = when (item.side?.lowercase()) {
            "buy", "long" -> "Achat"
            "sell", "short" -> "Vente"
            else -> item.side
        }
        val parts = mutableListOf<String>()
        if (item.symbol != null && sideLabel != null) {
            val qtyText = item.quantity?.let { String.format(Locale.FRENCH, "%.2f", it) } ?: "?"
            parts.add("$sideLabel $qtyText × ${item.symbol}")
        }
        if (item.totalValue != null) {
            parts.add("Valeur totale ${String.format(Locale.FRENCH, "%.2f", item.totalValue)}")
        }
        if (item.dailyPnl != null) {
            parts.add("P&L jour ${String.format(Locale.FRENCH, "%+.2f", item.dailyPnl)}")
        }
        ActivityRowModel(
            dot = ActivityDot.INFO,
            label = if (parts.isNotEmpty()) "Portfolio : ${parts.joinToString(" | ")}" else "Portfolio mis à jour",
            subtext = "Mise à jour portfolio",
            description = "Mise à jour portfolio : " +
                parts.joinToString(", ").ifEmpty { "valeurs mises à jour" },
        )
    }
    is ActivityItem.CatalystEvent -> ActivityRowModel(
        dot = ActivityDot.TERTIARY,
        label = "Catalyst : ${item.symbol} — ${item.title}",
        subtext = item.eventType.replaceFirstChar { it.uppercase() },
        description = "Événement catalyseur : ${item.title} pour ${item.symbol}, type ${item.eventType}",
    )
}
