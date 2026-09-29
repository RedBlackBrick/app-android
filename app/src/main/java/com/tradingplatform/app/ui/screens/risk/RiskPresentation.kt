package com.tradingplatform.app.ui.screens.risk

import com.tradingplatform.app.domain.exception.HttpStatusException
import com.tradingplatform.app.domain.model.RiskStatus
import com.tradingplatform.app.domain.usecase.write.EvaluateWriteGateUseCase
import com.tradingplatform.app.ui.components.ConfirmAction
import com.tradingplatform.app.ui.screens.dashboard.NO_VALUE
import com.tradingplatform.app.ui.screens.dashboard.formatPercent
import com.tradingplatform.app.ui.screens.dashboard.spokenPercent
import com.tradingplatform.app.vpn.VpnNotConnectedException
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/*
 * Logique de présentation PURE de l'écran Risque (aucune dépendance Compose/Android) : libellés,
 * niveaux d'alerte, récapitulatif de confirmation du kill switch et messages de résultat. Tout est
 * testé en JVM (RiskPresentationTest).
 */

// ── Seuils et constantes ──────────────────────────────────────────────────────

/**
 * Part de la limite de perte journalière (en % entier) à partir de laquelle l'écran passe en
 * avertissement. Même seuil (0.8 en fraction) que le bandeau de risque de l'Accueil.
 */
internal const val DAILY_LOSS_WARNING_PERCENT = 80

/** Le backend plafonne la lecture des violations non résolues à 100 : au-delà on affiche « 100+ ». */
internal const val VIOLATIONS_DISPLAY_CAP = 100

/** Au-delà de cette magnitude (fraction), la valeur est considérée corrompue (comme `formatPercent`). */
private const val MAX_DISPLAYABLE_FRACTION = 1_000_000.0

/** Niveau d'un indicateur de risque : pilote la couleur ET un libellé (jamais la couleur seule). */
internal enum class RiskLevel { OK, WARNING, CRITICAL, UNKNOWN }

// ── Messages ──────────────────────────────────────────────────────────────────

internal const val KILL_SWITCH_ALREADY_ACTIVE_MESSAGE =
    "Le kill switch est déjà actif — aucune action nécessaire."
internal const val KILL_SWITCH_REASON_REQUIRED_MESSAGE =
    "Motif obligatoire — la suspension n'a pas été envoyée."
internal const val RISK_PORTFOLIO_MISSING_MESSAGE = "Aucun portefeuille sélectionné."
internal const val RISK_LOAD_DEFAULT_ERROR = "Lecture du risque impossible pour le moment."

// ── Kill switch ───────────────────────────────────────────────────────────────

/**
 * Titre de la carte d'état. Si une lecture a échoué ([RiskStatus.isPartial]) et qu'aucun kill switch
 * n'est détecté, on ne prétend pas qu'il est inactif : l'état réel est inconnu.
 */
internal fun killSwitchHeadline(status: RiskStatus): String = when {
    status.killSwitchActive -> "Trading suspendu — kill switch actif"
    status.isPartial -> "Kill switch non vérifié — données partielles"
    else -> "Aucun kill switch actif"
}

/** « Motif : … » si le serveur en fournit un (texte libre, y compris pour un kill switch système). */
internal fun killSwitchReasonLabel(reason: String?): String? =
    reason?.trim()?.takeIf { it.isNotEmpty() }?.let { "Motif : $it" }

/**
 * Récapitulatif de la confirmation d'activation. Ce que le kill switch fait et ne fait PAS est dit
 * en clair : il bloque les NOUVEAUX ordres (7 jours), n'annule aucun ordre ouvert, n'empêche pas les
 * sorties, et se lève uniquement depuis le web. Motif obligatoire, bouton final destructif.
 */
internal fun killSwitchConfirmAction(portfolioName: String?): ConfirmAction = ConfirmAction(
    title = "Suspendre le trading ?",
    summaryLines = listOf(
        "Portefeuille" to (portfolioName?.takeIf { it.isNotBlank() } ?: "Portefeuille actif"),
        "Effet" to "Nouveaux ordres bloqués pendant 7 jours",
        "Ordres ouverts" to "Non annulés",
        "Sorties" to "Non bloquées (stops, clôtures)",
        "Levée" to "Sur le web uniquement",
    ),
    confirmLabel = "Suspendre le trading",
    destructive = true,
    requireReason = true,
    reasonLabel = "Motif",
)

/**
 * Message affiché APRÈS la relecture de l'état serveur (jamais avant : l'écran n'annonce l'état
 * final que si le serveur le confirme).
 *
 * @param rereadActive kill switch actif d'après la relecture ; `null` si la relecture a échoué.
 */
internal fun killSwitchOutcomeMessage(rereadActive: Boolean?): String = when (rereadActive) {
    true -> "Suspension du trading confirmée — kill switch actif."
    false -> "Suspension demandée — non confirmée par le serveur. Actualisez avant de réessayer."
    null -> "Suspension demandée — relecture impossible. Actualisez pour vérifier l'état avant de réessayer."
}

/** Message quand l'utilisateur a changé de portefeuille pendant l'écriture : on ne relit pas l'ancien. */
internal fun killSwitchOtherPortfolioMessage(portfolioName: String?): String {
    val target = portfolioName?.takeIf { it.isNotBlank() }?.let { "« $it »" } ?: "l'autre portefeuille"
    return "Suspension demandée pour $target — vérifiez l'état de ce portefeuille."
}

/**
 * Message d'un échec d'écriture (`Result.failure`). Les échecs certains (VPN, 4xx, motif invalide)
 * disent qu'aucun kill switch n'a été activé ; un échec d'origine inconnue invite à vérifier l'état
 * avant de réessayer (jamais de retry automatique).
 */
internal fun killSwitchFailureMessage(error: Throwable): String = when {
    error is VpnNotConnectedException -> EvaluateWriteGateUseCase.MESSAGE_VPN_NOT_CONNECTED
    error is IllegalArgumentException -> "Motif invalide — saisissez un motif de 1 à 500 caractères."
    error is HttpStatusException -> when (error.code) {
        401 -> EvaluateWriteGateUseCase.MESSAGE_NOT_LOGGED_IN
        403 -> "Suspension refusée — ce portefeuille n'est pas accessible avec ce compte."
        409 -> error.message?.takeIf { it.isNotBlank() }
            ?: "Conflit lors de la suspension — actualisez l'état avant de réessayer."
        else -> "Suspension refusée par le serveur (HTTP ${error.code}) — aucun kill switch activé."
    }
    else -> "Suspension impossible pour le moment. Actualisez pour vérifier l'état avant de réessayer."
}

/** Message d'échec de LECTURE de l'état de risque. */
internal fun riskLoadErrorMessage(error: Throwable): String = when (error) {
    is VpnNotConnectedException -> "VPN requis — activez le tunnel puis réessayez."
    is HttpStatusException -> "Le serveur a répondu par une erreur (HTTP ${error.code})."
    is IOException -> "Serveur injoignable — vérifiez la connexion puis réessayez."
    else -> RISK_LOAD_DEFAULT_ERROR
}

// ── Violations ────────────────────────────────────────────────────────────────

/** Valeur courte de la ligne « Violations non résolues » (« 0 », « 3 », « 100+ »). */
internal fun violationsValue(count: Int): String =
    if (count >= VIOLATIONS_DISPLAY_CAP) "$VIOLATIONS_DISPLAY_CAP+" else count.coerceAtLeast(0).toString()

/** Phrase complète pour TalkBack. */
internal fun violationsLabel(count: Int): String = when {
    count <= 0 -> "Aucune violation non résolue"
    count == 1 -> "1 violation non résolue"
    count >= VIOLATIONS_DISPLAY_CAP -> "$VIOLATIONS_DISPLAY_CAP violations non résolues ou plus"
    else -> "$count violations non résolues"
}

internal fun violationsLevel(count: Int): RiskLevel = if (count > 0) RiskLevel.WARNING else RiskLevel.OK

// ── Perte du jour vs limite ───────────────────────────────────────────────────

/**
 * Pourcentage entier arrondi (HALF_UP, comme `formatPercent`) de la part de limite consommée, ou
 * `null` si la valeur est absente / non finie / hors garde. Niveau et libellé partent de CE nombre :
 * un « 80% » affiché est toujours en avertissement.
 */
private fun roundedUsagePercent(usage: Double?): Int? {
    if (usage == null || !usage.isFinite() || abs(usage) >= MAX_DISPLAYABLE_FRACTION) return null
    return BigDecimal.valueOf(usage).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toInt()
}

internal fun dailyLossLevel(usage: Double?): RiskLevel {
    val pct = roundedUsagePercent(usage) ?: return RiskLevel.UNKNOWN
    return when {
        pct >= 100 -> RiskLevel.CRITICAL
        pct >= DAILY_LOSS_WARNING_PERCENT -> RiskLevel.WARNING
        else -> RiskLevel.OK
    }
}

/** Progression de la barre, bornée à 0..1 (une perte au-delà de la limite remplit la barre). */
internal fun dailyLossProgress(usage: Double?): Float {
    val pct = roundedUsagePercent(usage) ?: return 0f
    return (pct / 100f).coerceIn(0f, 1f)
}

/** « 85% » ; « — » si inconnue. */
internal fun dailyLossValue(usage: Double?): String =
    if (roundedUsagePercent(usage) == null) NO_VALUE else formatPercent(usage, decimals = 0)

/** Libellé de niveau en toutes lettres (la couleur ne porte jamais l'information seule). */
internal fun riskLevelLabel(level: RiskLevel): String = when (level) {
    RiskLevel.OK -> "Dans la limite"
    RiskLevel.WARNING -> "Proche de la limite"
    RiskLevel.CRITICAL -> "Limite atteinte"
    RiskLevel.UNKNOWN -> "Limite non disponible"
}

/** Description TalkBack de toute la ligne « Perte du jour ». */
internal fun dailyLossSpoken(usage: Double?): String {
    val level = dailyLossLevel(usage)
    return if (level == RiskLevel.UNKNOWN) {
        "Perte du jour : ${riskLevelLabel(level).lowercase()}"
    } else {
        "Perte du jour : ${spokenPercent(usage, decimals = 0)} de la limite. ${riskLevelLabel(level)}"
    }
}

// ── Drawdown ──────────────────────────────────────────────────────────────────

/** « -12,40% » ; « — » si inconnu. La fraction est négative ou nulle (−0.124 = −12,4 %). */
internal fun drawdownValue(drawdown: Double?): String = formatPercent(drawdown)

internal fun drawdownSpoken(drawdown: Double?): String =
    "Drawdown courant : ${spokenPercent(drawdown)}"
