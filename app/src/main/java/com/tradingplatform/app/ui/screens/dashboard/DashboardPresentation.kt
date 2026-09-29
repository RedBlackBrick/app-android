package com.tradingplatform.app.ui.screens.dashboard

import com.tradingplatform.app.domain.model.ActivityItem
import com.tradingplatform.app.domain.model.CircuitBreakerState
import com.tradingplatform.app.domain.model.NavCurve
import com.tradingplatform.app.domain.model.NavSummary
import com.tradingplatform.app.domain.model.PerformanceMetrics
import com.tradingplatform.app.domain.model.PnlPeriod
import com.tradingplatform.app.domain.model.Portfolio
import com.tradingplatform.app.domain.model.PortfolioBrokerStatus
import com.tradingplatform.app.domain.model.PortfolioCircuitBreakerStatus
import com.tradingplatform.app.domain.model.PortfolioOverviewItem
import com.tradingplatform.app.domain.model.RiskStatus
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
 * dérivation des KPI, choix du bandeau de risque (une seule alerte, la plus grave), pastille broker,
 * entrée Stratégies, carte « Mes portefeuilles », découpage du flux d'activité.
 * Les composables ne font que rendre ces modèles — tout est testé en JVM
 * (DashboardPresentationTest).
 */

// ── Périodes ──────────────────────────────────────────────────────────────────

/**
 * Périodes proposées par le sélecteur du Dashboard (l'ordre est celui de l'affichage). « Année »
 * n'y figure plus : le backend ne sait pas calculer un P&L annuel (ALL = depuis la création).
 */
internal val DASHBOARD_PERIODS: List<PnlPeriod> =
    listOf(PnlPeriod.DAY, PnlPeriod.WEEK, PnlPeriod.MONTH, PnlPeriod.ALL)

/** Libellé court d'une période (« Sem. » : « Semaine » est tronqué sur 4 segments en 360 dp). */
internal fun dashboardPeriodLabel(period: PnlPeriod): String = when (period) {
    PnlPeriod.DAY -> "Jour"
    PnlPeriod.WEEK -> "Sem."
    PnlPeriod.MONTH -> "Mois"
    PnlPeriod.YEAR -> "Année"
    PnlPeriod.ALL -> "Tout"
}

/** Légende du P&L d'une période (carte « Mes portefeuilles »). « Tout » = depuis la création. */
internal fun dashboardPeriodCaption(period: PnlPeriod): String = when (period) {
    PnlPeriod.DAY -> "P&L du jour"
    PnlPeriod.WEEK -> "P&L de la semaine"
    PnlPeriod.MONTH -> "P&L du mois"
    PnlPeriod.YEAR -> "P&L de l'année"
    PnlPeriod.ALL -> "P&L depuis la création"
}

// ── Devise ────────────────────────────────────────────────────────────────────

/**
 * Symbole affiché pour un code devise de portefeuille (« EUR » → « € »). Code absent ou vide →
 * « € » (devise historique de l'app) ; code inconnu → le code lui-même en majuscules (« CHF »).
 */
internal fun currencySymbolFor(code: String?): String {
    val normalized = code?.trim()?.uppercase(Locale.ROOT).orEmpty()
    return when (normalized) {
        "", "EUR" -> "€"
        "USD" -> "\$"
        "GBP" -> "£"
        else -> normalized
    }
}

/** Symbole de la devise du portefeuille actif ([activeId]) ; « € » tant que la liste est inconnue. */
internal fun activeCurrencySymbol(portfolios: List<Portfolio>, activeId: String): String =
    currencySymbolFor(portfolios.firstOrNull { it.id == activeId }?.currency)

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
internal fun variationSpokenDescription(
    totalReturn: BigDecimal?,
    totalReturnPct: Double?,
    currencySymbol: String = "€",
): String {
    val parts = mutableListOf<String>()
    if (totalReturn != null) parts += buildPnlDescription(totalReturn, currencySymbol)
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

// ── Courbe de NAV (héros) ─────────────────────────────────────────────────────

/**
 * Valeurs de la courbe de NAV à tracer, du plus ancien au plus récent. Moins de 2 points (courbe
 * absente, vide ou réduite à un point) → liste vide : le héros ne dessine alors AUCUNE courbe
 * (jamais un tracé plat ou inventé).
 */
internal fun navCurveValues(curve: NavCurve?): List<BigDecimal> {
    val values = curve?.points?.map { it.value }.orEmpty()
    return if (values.size >= 2) values else emptyList()
}

/**
 * Phrase TalkBack de la courbe : sens et bornes en euros formatés (jamais un BigDecimal brut),
 * ex. « Courbe de la valeur liquidative, en hausse : de 100 000,00 € à 104 500,00 € ». `null`
 * quand il n'y a pas de courbe à décrire.
 */
internal fun navCurveSpokenDescription(values: List<BigDecimal>, currencySymbol: String = "€"): String? {
    if (values.size < 2) return null
    val first = values.first()
    val last = values.last()
    val trend = when {
        last > first -> "en hausse"
        last < first -> "en baisse"
        else -> "stable"
    }
    return "Courbe de la valeur liquidative, $trend : de ${formatMoneyAmount(first, currencySymbol)} " +
        "à ${formatMoneyAmount(last, currencySymbol)}"
}

// ── Tuiles KPI ────────────────────────────────────────────────────────────────

internal enum class DashboardKpiKind { CASH, UNREALIZED, WIN_RATE, MAX_DRAWDOWN }

/** Nombre maximal de tuiles KPI : deux colonnes × deux lignes (lisible à 360 dp, police 130 %). */
internal const val DASHBOARD_KPI_LIMIT = 4

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

/** Montant arrondi à l'euro pour une tuile (les centimes n'ont pas leur place dans un KPI de coup d'œil). */
private fun wholeEuros(amount: BigDecimal): BigDecimal = amount.setScale(0, RoundingMode.HALF_UP)

/**
 * KPI du Dashboard, dans cet ordre : Liquidités, Latent (P&L non réalisé) — issus de la NAV —, puis
 * Win rate et Drawdown max — issus des métriques de performance ([PerformanceMetrics], cumul depuis
 * la création : `winRate` et `maxDrawdown` sont des FRACTIONS). Le rendement n'y figure pas : il est
 * déjà dans la ligne « P&L » du héros.
 *
 * Une tuile sans valeur exploitable est OMISE (pas de tuile « — » : l'écran reste concis) — en
 * particulier quand la lecture de performance a échoué (`performance == null`) ou qu'il n'y a eu
 * aucun trade (`winRate == null`). La liste est plafonnée à [DASHBOARD_KPI_LIMIT] tuiles.
 */
internal fun dashboardKpis(
    nav: NavSummary?,
    performance: PerformanceMetrics?,
    currencySymbol: String = "€",
): List<DashboardKpi> = buildList {
    if (nav != null) {
        val cash = wholeEuros(nav.cashBalance)
        add(
            DashboardKpi(
                kind = DashboardKpiKind.CASH,
                label = "Liquidités",
                value = formatMoneyAmount(cash, currencySymbol, decimals = 0),
                spokenDescription = "Liquidités : ${formatMoneyAmount(nav.cashBalance, currencySymbol)}",
                tone = PnlTone.NEUTRAL,
            ),
        )
        val unrealized = wholeEuros(nav.totalUnrealizedPnl)
        add(
            DashboardKpi(
                kind = DashboardKpiKind.UNREALIZED,
                label = "Latent",
                // Le ton et le signe suivent la valeur AFFICHÉE (jamais de « -0 € » rouge).
                value = (if (unrealized.signum() > 0) "+" else "") +
                    formatMoneyAmount(unrealized, currencySymbol, decimals = 0),
                spokenDescription =
                    "Plus-value latente : ${buildPnlDescription(nav.totalUnrealizedPnl, currencySymbol)}",
                tone = when {
                    unrealized.signum() > 0 -> PnlTone.POSITIVE
                    unrealized.signum() < 0 -> PnlTone.NEGATIVE
                    else -> PnlTone.NEUTRAL
                },
            ),
        )
    }
    if (performance != null) {
        val winRate = performance.winRate
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
        val maxDrawdown = performance.maxDrawdown
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

// ── Bandeau de risque (une seule alerte, la plus grave) ───────────────────────

/**
 * Nature du bandeau de risque. L'ordre de déclaration EST l'ordre de gravité décroissante : quand
 * plusieurs situations coexistent, [riskBannerModel] n'affiche que la première.
 */
internal enum class RiskBannerKind {
    KILL_SWITCH,
    CIRCUIT_BREAKER_OPEN,
    STATUS_UNAVAILABLE,
    VIOLATIONS,
    DAILY_LOSS,
}

/** Part de la limite de perte journalière à partir de laquelle le bandeau apparaît (80 %). */
internal const val DAILY_LOSS_ALERT_FRACTION = 0.8

/** Le backend plafonne le compte de violations à 100 : au-delà on affiche « 100+ ». */
internal const val VIOLATIONS_DISPLAY_CAP = 100

/**
 * Bandeau de risque prêt à afficher.
 *
 * @property isBlocking le trading est bloqué (kill switch, circuit-breaker, statut inconnu = le
 *   serveur bloque par précaution) → ton « erreur » ; sinon simple avertissement.
 * @property isPartial une des lectures de risque a échoué : mention discrète « Données partielles ».
 * @property spokenDescription phrase TalkBack complète (jamais de pourcentage numérique brut).
 */
internal data class RiskBannerModel(
    val kind: RiskBannerKind,
    val title: String,
    val spokenDescription: String,
    val isBlocking: Boolean,
    val isPartial: Boolean,
)

/**
 * Décide quel bandeau de risque afficher (au plus UN), en fusionnant la situation de risque
 * ([risk] : kill switch, violations, perte du jour) et le circuit-breaker ([breaker], déjà traité
 * par [riskTileModel]) — l'écran ne montre jamais deux alertes risque, seulement la plus grave :
 *
 * 1. kill switch actif → « Trading suspendu — kill switch actif » ;
 * 2. circuit-breaker ouvert → « Trading suspendu — circuit-breaker ouvert » ; ou statut Redis
 *    indisponible → « Statut du risque indisponible » ;
 * 3. violations non résolues > 0 → « 3 violations de risque non résolues » ;
 * 4. perte du jour ≥ [DAILY_LOSS_ALERT_FRACTION] de la limite → « Perte du jour à 85% de la limite ».
 *
 * Rien de tout cela (ou aucune donnée) → `null` : le bandeau est absent.
 */
internal fun riskBannerModel(
    risk: RiskStatus?,
    breaker: PortfolioCircuitBreakerStatus?,
): RiskBannerModel? {
    val partial = risk?.isPartial == true
    fun banner(kind: RiskBannerKind, title: String, spoken: String, blocking: Boolean) = RiskBannerModel(
        kind = kind,
        title = title,
        spokenDescription = if (partial) "$spoken, données partielles" else spoken,
        isBlocking = blocking,
        isPartial = partial,
    )

    if (risk?.killSwitchActive == true) {
        return banner(
            RiskBannerKind.KILL_SWITCH,
            "Trading suspendu — kill switch actif",
            "Trading suspendu : kill switch actif",
            blocking = true,
        )
    }

    val tile = riskTileModel(breaker)
    if (tile != null) {
        return when (tile.kind) {
            RiskTileKind.TRADING_SUSPENDED -> banner(
                RiskBannerKind.CIRCUIT_BREAKER_OPEN,
                "Trading suspendu — circuit-breaker ouvert",
                tile.spokenDescription,
                blocking = true,
            )
            RiskTileKind.STATUS_UNAVAILABLE -> banner(
                RiskBannerKind.STATUS_UNAVAILABLE,
                "Statut du risque indisponible",
                tile.spokenDescription,
                blocking = true,
            )
        }
    }

    val violations = risk?.unresolvedViolations ?: 0
    if (violations > 0) {
        val count = if (violations >= VIOLATIONS_DISPLAY_CAP) "$VIOLATIONS_DISPLAY_CAP+" else "$violations"
        val text = if (violations == 1) {
            "$count violation de risque non résolue"
        } else {
            "$count violations de risque non résolues"
        }
        return banner(RiskBannerKind.VIOLATIONS, text, text, blocking = false)
    }

    val usage = risk?.dailyLossUsagePct
    if (usage != null && usage >= DAILY_LOSS_ALERT_FRACTION && roundedPercent(usage, 0) != null) {
        return banner(
            RiskBannerKind.DAILY_LOSS,
            "Perte du jour à ${formatPercent(usage, decimals = 0)} de la limite",
            "Perte du jour à ${spokenPercent(usage, decimals = 0)} de la limite",
            blocking = false,
        )
    }
    return null
}

// ── Broker (pastille, jamais une « santé en direct ») ─────────────────────────

internal enum class BrokerTone { NEUTRAL, WARNING }

/** Pastille broker prête à afficher ; [spokenDescription] est la phrase TalkBack. */
internal data class BrokerPillModel(
    val label: String,
    val tone: BrokerTone,
    val spokenDescription: String,
)

/**
 * Pastille « Broker : <code> ». `connection_status` est un statut de CONFIGURATION côté backend,
 * pas une santé en direct : on ne dit donc jamais « connecté » / « en ligne ». Seuls les états
 * dégradés sont signalés (ton avertissement + libellé) :
 * `error` → « connexion en erreur », `revoked` → « accès révoqué », `inactive` → « inactif »,
 * `maintenance` → « en maintenance ». Tout autre statut (active, pending, inconnu) → code seul.
 *
 * Aucune connexion (`status == null`), ou ni code ni statut dégradé à montrer → `null`.
 */
internal fun brokerPillModel(status: PortfolioBrokerStatus?): BrokerPillModel? {
    if (status == null) return null
    val code = status.brokerCode?.trim()?.takeIf { it.isNotEmpty() }
    val detail = when (status.connectionStatus?.trim()?.lowercase(Locale.ROOT)) {
        "error" -> "connexion en erreur"
        "revoked" -> "accès révoqué"
        "inactive" -> "inactif"
        "maintenance" -> "en maintenance"
        else -> null
    }
    val label = when {
        code != null && detail != null -> "Broker : $code — $detail"
        code != null -> "Broker : $code"
        detail != null -> "Broker : $detail"
        else -> return null
    }
    return if (detail != null) {
        BrokerPillModel(label, BrokerTone.WARNING, "Attention, $label")
    } else {
        BrokerPillModel(label, BrokerTone.NEUTRAL, label)
    }
}

// ── Entrée « Stratégies » ─────────────────────────────────────────────────────

/** Ligne d'entrée vers l'écran Stratégies (« Stratégies — 3 actives »). */
internal data class StrategiesEntryModel(
    val label: String,
    val value: String,
    val spokenDescription: String,
)

/**
 * « Stratégies — N actives ». Nombre inconnu (`null` : lecture non faite ou en échec) → `null` : la
 * ligne est absente plutôt que de montrer un faux « 0 ».
 */
internal fun strategiesEntryModel(activeCount: Int?): StrategiesEntryModel? {
    if (activeCount == null || activeCount < 0) return null
    val value = when (activeCount) {
        0 -> "aucune active"
        1 -> "1 active"
        else -> "$activeCount actives"
    }
    return StrategiesEntryModel(
        label = "Stratégies",
        value = value,
        spokenDescription = "Stratégies : $value",
    )
}

// ── Carte « Mes portefeuilles » ───────────────────────────────────────────────

/** Une ligne de la carte « Mes portefeuilles » (valeur + P&L de la période sélectionnée). */
internal data class PortfolioRowModel(
    val portfolioId: String,
    val name: String,
    val value: String,
    /** « +4 500,00 € · +4,50% » ; `null` quand la source ne fournit ni montant ni pourcentage. */
    val pnl: String?,
    val tone: PnlTone,
    val isActive: Boolean,
    val spokenDescription: String,
)

/** Ligne « Total » — uniquement quand tous les portefeuilles sont dans la même devise. */
internal data class PortfolioTotalModel(
    val value: String,
    val pnl: String?,
    val tone: PnlTone,
    val spokenDescription: String,
)

internal data class PortfolioOverviewModel(
    val rows: List<PortfolioRowModel>,
    val total: PortfolioTotalModel?,
)

/**
 * La carte « Mes portefeuilles » n'existe que pour un compte à plusieurs portefeuilles, et
 * seulement avec des données (ou en chargement initial) : sans donnée, elle est absente.
 */
internal fun shouldShowPortfolioOverview(portfolioCount: Int, hasValue: Boolean, isLoading: Boolean): Boolean =
    portfolioCount >= 2 && (hasValue || isLoading)

/** Montant signé arrondi au centime (« +4 500,00 € », « -300,00 € », « 0,00 € »). */
private fun signedMoney(rounded: BigDecimal, symbol: String): String =
    (if (rounded.signum() > 0) "+" else "") + formatMoneyAmount(rounded, symbol)

private fun toneOf(rounded: BigDecimal): PnlTone = when {
    rounded.signum() > 0 -> PnlTone.POSITIVE
    rounded.signum() < 0 -> PnlTone.NEGATIVE
    else -> PnlTone.NEUTRAL
}

/**
 * Construit les lignes de la carte à partir de [items] (P&L de la période déjà choisie côté
 * appelant). La ligne de [activeId] est marquée `isActive`. Le total n'existe que si toutes les
 * lignes ont la même devise (aucune conversion) ; son P&L n'existe que si TOUS les P&L de ligne
 * sont connus. Les pourcentages sont des FRACTIONS (0.045 = 4,50%).
 */
internal fun portfolioOverviewModel(items: List<PortfolioOverviewItem>, activeId: String): PortfolioOverviewModel {
    val rows = items.map { item ->
        val symbol = currencySymbolFor(item.currency)
        val rounded = item.periodPnl?.setScale(2, RoundingMode.HALF_UP)
        val hasPct = roundedPercent(item.periodPnlPct, 2) != null
        val pnlParts = listOfNotNull(
            rounded?.let { signedMoney(it, symbol) },
            if (hasPct) formatPercent(item.periodPnlPct, signed = true) else null,
        )
        val tone = when {
            rounded != null -> toneOf(rounded)
            hasPct -> percentTone(item.periodPnlPct)
            else -> PnlTone.NEUTRAL
        }
        val isActive = item.portfolioId == activeId
        val spoken = buildList {
            add(if (isActive) "${item.name}, portefeuille actif" else item.name)
            add("valeur ${formatMoneyAmount(item.currentValue, symbol)}")
            if (rounded != null) add(buildPnlDescription(rounded, symbol))
            if (hasPct) add(spokenPercent(item.periodPnlPct, signed = true))
        }.joinToString(", ")
        PortfolioRowModel(
            portfolioId = item.portfolioId,
            name = item.name,
            value = formatMoneyAmount(item.currentValue, symbol),
            pnl = pnlParts.takeIf { it.isNotEmpty() }?.joinToString(" · "),
            tone = tone,
            isActive = isActive,
            spokenDescription = spoken,
        )
    }

    val currencies = items.map { currencySymbolFor(it.currency) }.distinct()
    val total = if (items.size >= 2 && currencies.size == 1) {
        val symbol = currencies.single()
        val totalValue = items.fold(BigDecimal.ZERO) { acc, item -> acc + item.currentValue }
        val allPnl = items.map { it.periodPnl }
        val totalPnl = if (allPnl.all { it != null }) {
            allPnl.fold(BigDecimal.ZERO) { acc, pnl -> acc + pnl!! }.setScale(2, RoundingMode.HALF_UP)
        } else {
            null
        }
        PortfolioTotalModel(
            value = formatMoneyAmount(totalValue, symbol),
            pnl = totalPnl?.let { signedMoney(it, symbol) },
            tone = totalPnl?.let { toneOf(it) } ?: PnlTone.NEUTRAL,
            spokenDescription = buildList {
                add("Total, valeur ${formatMoneyAmount(totalValue, symbol)}")
                if (totalPnl != null) add(buildPnlDescription(totalPnl, symbol))
            }.joinToString(", "),
        )
    } else {
        null
    }
    return PortfolioOverviewModel(rows = rows, total = total)
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
