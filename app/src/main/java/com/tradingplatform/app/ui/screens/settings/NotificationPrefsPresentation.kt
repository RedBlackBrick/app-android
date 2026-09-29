package com.tradingplatform.app.ui.screens.settings

import com.tradingplatform.app.domain.exception.HttpStatusException
import com.tradingplatform.app.domain.model.NotifCategory
import com.tradingplatform.app.domain.model.QuietHours
import com.tradingplatform.app.domain.model.RiskAlertThresholds
import com.tradingplatform.app.domain.usecase.write.EvaluateWriteGateUseCase
import com.tradingplatform.app.ui.components.ConfirmAction
import com.tradingplatform.app.vpn.VpnNotConnectedException
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode

/*
 * Logique de présentation PURE de l'écran « Notifications » (aucune dépendance Compose/Android) :
 * libellés des catégories, récapitulatif de confirmation, messages de résultat, résumé en lecture
 * seule des heures calmes et des seuils. Testée en JVM (NotificationPrefsPresentationTest).
 */

internal const val PREFS_LOAD_DEFAULT_ERROR = "Lecture des préférences impossible pour le moment."

// ── Catégories ────────────────────────────────────────────────────────────────

internal fun categoryTitle(category: NotifCategory): String = when (category) {
    NotifCategory.STRATEGY_SIGNAL -> "Signaux de stratégie"
    NotifCategory.RISK_ALERT -> "Alertes de risque"
    NotifCategory.SYSTEM -> "Système"
}

internal fun categoryDescription(category: NotifCategory): String = when (category) {
    NotifCategory.STRATEGY_SIGNAL -> "Signaux bloqués, dégradation et nouvelles versions de stratégie."
    NotifCategory.RISK_ALERT -> "Seuils de risque dépassés (drawdown, VaR, concentration)."
    NotifCategory.SYSTEM -> "État des appareils et du réseau, messages système."
}

/** État de l'interrupteur en toutes lettres (TalkBack : jamais la seule position du curseur). */
internal fun pushStateLabel(enabled: Boolean): String = if (enabled) "Activée" else "Désactivée"

// ── Confirmation et messages ──────────────────────────────────────────────────

/** Changement de push demandé, en attente de confirmation biométrique. */
data class PushChange(val category: NotifCategory, val enabled: Boolean)

/** Récapitulatif de la confirmation (sans motif : `ConfirmActionSheet` enchaîne sur la biométrie). */
internal fun pushChangeConfirmAction(category: NotifCategory, enabled: Boolean): ConfirmAction =
    ConfirmAction(
        title = if (enabled) "Activer les notifications push ?" else "Désactiver les notifications push ?",
        summaryLines = listOf(
            "Catégorie" to categoryTitle(category),
            "Notification push" to pushStateLabel(enabled),
            "Portée" to "Tous vos appareils",
        ),
        confirmLabel = if (enabled) "Activer le push" else "Désactiver le push",
    )

/**
 * Message affiché APRÈS la relecture des préférences. L'état annoncé est celui que le serveur
 * renvoie, jamais celui qu'on espérait.
 *
 * @param rereadEnabled valeur de `push` relue pour la catégorie ; `null` si la relecture a échoué.
 */
internal fun pushOutcomeMessage(
    category: NotifCategory,
    requestedEnabled: Boolean,
    rereadEnabled: Boolean?,
): String = when (rereadEnabled) {
    requestedEnabled -> {
        val state = if (requestedEnabled) "activées" else "désactivées"
        "Notifications push $state : ${categoryTitle(category)}."
    }
    null -> "Modification demandée — relecture impossible. Actualisez pour vérifier avant de réessayer."
    else -> "Modification demandée, mais le serveur affiche encore l'état précédent. " +
        "Actualisez avant de réessayer."
}

/** Message d'un échec d'écriture (`Result.failure`) : la préférence n'a pas changé (ou état à vérifier). */
internal fun pushFailureMessage(error: Throwable): String = when {
    error is VpnNotConnectedException -> EvaluateWriteGateUseCase.MESSAGE_VPN_NOT_CONNECTED
    error is HttpStatusException -> when (error.code) {
        401 -> EvaluateWriteGateUseCase.MESSAGE_NOT_LOGGED_IN
        409 -> error.message?.takeIf { it.isNotBlank() }
            ?: "Préférences modifiées entre-temps — actualisez puis réessayez."
        else -> "Modification refusée par le serveur (HTTP ${error.code}) — préférence inchangée."
    }
    else -> "Modification impossible pour le moment. Actualisez pour vérifier l'état avant de réessayer."
}

/** Message d'échec de LECTURE des préférences. */
internal fun prefsLoadErrorMessage(error: Throwable): String = when (error) {
    is VpnNotConnectedException -> "VPN requis — activez le tunnel puis réessayez."
    is HttpStatusException -> "Le serveur a répondu par une erreur (HTTP ${error.code})."
    is IOException -> "Serveur injoignable — vérifiez la connexion puis réessayez."
    else -> PREFS_LOAD_DEFAULT_ERROR
}

// ── Heures calmes et seuils (lecture seule) ───────────────────────────────────

/** « Désactivées » ou « Actives de 22:00 à 08:00 ». */
internal fun quietHoursSummary(quietHours: QuietHours): String =
    if (quietHours.enabled) "Actives de ${quietHours.start} à ${quietHours.end}" else "Désactivées"

/**
 * Fraction → pourcentage lisible, sans zéros inutiles : `0.1` → « 10% », `0.125` → « 12,5% ».
 * Arrondi à 1 décimale (HALF_UP), virgule décimale française fixe.
 */
internal fun formatThresholdPercent(fraction: Double): String {
    val pct = BigDecimal.valueOf(fraction)
        .movePointRight(2)
        .setScale(1, RoundingMode.HALF_UP)
        .stripTrailingZeros()
    return pct.toPlainString().replace('.', ',') + "%"
}

/**
 * Seuils configurés, en lignes « libellé → valeur » ; un seuil `null` (désactivé) est omis. Liste
 * vide = aucun seuil configuré.
 */
internal fun thresholdLines(thresholds: RiskAlertThresholds): List<Pair<String, String>> = buildList {
    thresholds.varPctOfMax?.let { add("VaR (part du maximum)" to formatThresholdPercent(it)) }
    thresholds.drawdownWarnPct?.let { add("Alerte de drawdown" to formatThresholdPercent(it)) }
    thresholds.positionConcentrationPct?.let { add("Concentration d'une position" to formatThresholdPercent(it)) }
    thresholds.suppressedSignalsPerDay?.let { add("Signaux ignorés par jour" to it.toString()) }
}
