package com.tradingplatform.app.ui.screens.strategies

import com.tradingplatform.app.domain.exception.HttpStatusException
import com.tradingplatform.app.domain.model.PortfolioStrategyEntry
import com.tradingplatform.app.domain.model.WriteOutcome
import com.tradingplatform.app.domain.usecase.write.EvaluateWriteGateUseCase
import com.tradingplatform.app.ui.components.ConfirmAction
import com.tradingplatform.app.vpn.VpnNotConnectedException
import java.io.IOException

/*
 * Logique de présentation PURE de l'écran Stratégies (aucune dépendance Compose/Android) :
 * libellés, descriptions TalkBack, contenu de la confirmation d'écriture, messages de résultat.
 * Les composables et le ViewModel ne font que les utiliser — tout est testé en JVM
 * (StrategiesPresentationTest).
 *
 * Vocabulaire : on parle de « lien » (portefeuille-stratégie), jamais de « stratégie en pause » :
 * la pause ne concerne que le lien avec CE portefeuille, pas la stratégie elle-même.
 */

// ── Constantes de texte ───────────────────────────────────────────────────────

internal const val UNNAMED_STRATEGY = "Stratégie sans nom"

/** Rappel permanent : l'édition (allocation, paramètres, rattachement…) n'existe pas sur mobile. */
internal const val WEB_EDITING_NOTE = "Édition des stratégies : sur le web"

/**
 * Affiché quand au moins un lien est « en pause » : le backend ne distingue pas un lien mis en
 * pause d'un lien détaché sur le web (soft delete `is_active = false`, contrat §9.8).
 */
internal const val DETACHED_LINK_HINT =
    "Un lien détaché sur le web apparaît aussi comme « en pause »."

internal const val PAUSE_EFFECT =
    "Les nouveaux signaux de cette stratégie ne passeront plus d'ordres pour ce portefeuille."

internal const val REACTIVATE_EFFECT =
    "Les nouveaux signaux de cette stratégie pourront de nouveau passer des ordres pour ce portefeuille."

/** Avertissement OBLIGATOIRE de la confirmation de réactivation (contrat produit round 3). */
internal const val REACTIVATE_WARNING =
    "Un lien détaché sur le web est indiscernable d'un lien en pause : " +
        "la réactivation peut le remettre en service."

/** Libellé du portefeuille dans la confirmation quand son nom n'est pas (encore) connu. */
internal const val ACTIVE_PORTFOLIO_FALLBACK_LABEL = "Portefeuille actif"

internal const val PORTFOLIO_NOT_FOUND_MESSAGE = "Portefeuille introuvable"

internal const val LOAD_ERROR_MESSAGE = "Impossible de charger les stratégies — tirez pour réessayer."

internal const val LOAD_VPN_MESSAGE = "VPN requis — activez le tunnel pour charger les stratégies."

internal const val UNVERIFIED_STATE_LABEL = "État non vérifié — tirez pour actualiser"

internal const val REREAD_FAILED_MESSAGE =
    "Demande envoyée, mais l'état n'a pas pu être relu — tirez pour actualiser avant d'agir."

internal const val STATE_CHANGED_MESSAGE =
    "L'état de ce lien a changé — vérifiez la liste avant d'agir."

internal const val PREVIOUS_PORTFOLIO_MESSAGE =
    "Une demande était en cours pour le portefeuille précédent — vérifiez son état."

/** Longueur de la fin d'identifiant affichée pour une stratégie sans nom. */
private const val ID_SUFFIX_LENGTH = 6

/** Profondeur maximale de la chaîne des causes examinée (protection contre les cycles). */
private const val MAX_CAUSE_DEPTH = 8

// ── Noms, états, actions ──────────────────────────────────────────────────────

/**
 * Nom affiché d'un lien : le nom de la stratégie, ou « Stratégie sans nom (…abc123) » (fin de
 * l'identifiant) quand le catalogue ne le connaît pas ou que le nom est vide.
 */
internal fun strategyDisplayName(name: String?, strategyId: String): String {
    val trimmed = name?.trim()
    if (trimmed != null && trimmed.isNotEmpty()) return trimmed
    val suffix = strategyId.trim().takeLast(ID_SUFFIX_LENGTH)
    return if (suffix.isEmpty()) UNNAMED_STRATEGY else "$UNNAMED_STRATEGY (…$suffix)"
}

/** Texte de la pastille d'état (l'icône et le texte portent l'information, pas la seule couleur). */
internal fun linkStatusLabel(isActive: Boolean): String = if (isActive) "Active" else "En pause"

/** Libellé du bouton : « Réactiver ce lien » dit qu'il s'agit du lien (voir [DETACHED_LINK_HINT]). */
internal fun linkActionLabel(isActive: Boolean): String =
    if (isActive) "Mettre en pause" else "Réactiver ce lien"

/** Lecture TalkBack de l'état d'une ligne : « Stratégie X, active ». */
internal fun strategyStatusDescription(displayName: String, isActive: Boolean): String =
    "Stratégie $displayName, ${if (isActive) "active" else "en pause"}"

/** Lecture TalkBack du bouton d'une ligne. */
internal fun strategyActionDescription(displayName: String, isActive: Boolean): String =
    if (isActive) {
        "Mettre en pause la stratégie $displayName"
    } else {
        "Réactiver le lien de la stratégie $displayName"
    }

/** Résumé de l'en-tête : « 2 actives sur 3 » ; vide s'il n'y a aucun lien. */
internal fun strategiesSummary(entries: List<PortfolioStrategyEntry>): String {
    if (entries.isEmpty()) return ""
    val active = entries.count { it.isActive }
    return "$active ${if (active > 1) "actives" else "active"} sur ${entries.size}"
}

/** Vrai s'il faut afficher [DETACHED_LINK_HINT] (au moins un lien « en pause »). */
internal fun shouldShowDetachedHint(entries: List<PortfolioStrategyEntry>): Boolean =
    entries.any { !it.isActive }

/** Libellé du bouton d'une ligne dont l'écriture est en cours. */
internal fun strategyBusyLabel(phase: StrategyWritePhase): String = when (phase) {
    StrategyWritePhase.SENDING -> "Envoi en cours…"
    StrategyWritePhase.VERIFYING -> "Vérification…"
}

/**
 * Une écriture peut-elle démarrer ? Non si une autre est en vol (envoi ou relecture : on ne relance
 * qu'après avoir vu l'état relu) ou si la liste est en cours de rafraîchissement.
 */
internal fun canStartStrategyWrite(write: StrategyWriteInFlight?, isRefreshing: Boolean): Boolean =
    write == null && !isRefreshing

// ── Confirmation ──────────────────────────────────────────────────────────────

/**
 * Contenu de la feuille de confirmation (récapitulatif → biométrie). Pas de motif : l'action est
 * réversible. La réactivation porte l'avertissement [REACTIVATE_WARNING].
 *
 * @param displayName nom affiché du lien ([strategyDisplayName]).
 * @param portfolioLabel nom du portefeuille actif, ou [ACTIVE_PORTFOLIO_FALLBACK_LABEL].
 * @param targetActive état demandé : `false` = mettre en pause, `true` = réactiver.
 */
internal fun strategyConfirmAction(
    displayName: String,
    portfolioLabel: String,
    targetActive: Boolean,
): ConfirmAction {
    val common = listOf(
        "Stratégie" to displayName,
        "Portefeuille" to portfolioLabel,
    )
    return if (targetActive) {
        ConfirmAction(
            title = "Réactiver ce lien ?",
            summaryLines = common,
            confirmLabel = "Réactiver ce lien",
            message = "$REACTIVATE_EFFECT $REACTIVATE_WARNING",
        )
    } else {
        ConfirmAction(
            title = "Mettre cette stratégie en pause ?",
            summaryLines = common,
            confirmLabel = "Mettre en pause",
            message = PAUSE_EFFECT,
        )
    }
}

// ── Messages de résultat ──────────────────────────────────────────────────────

/**
 * Message affiché dès que le serveur a accepté (ou semble avoir accepté) la demande, AVANT la
 * relecture : « effectuée » sur un 2xx, « demandée — vérification en cours » sinon (jamais
 * « effectuée » sur un état non confirmé).
 */
internal fun strategyWriteSuccessMessage(targetActive: Boolean, outcome: WriteOutcome): String {
    val noun = if (targetActive) "Réactivation" else "Mise en pause"
    return when (outcome) {
        WriteOutcome.CONFIRMED -> "$noun effectuée"
        WriteOutcome.REQUESTED_UNCONFIRMED -> "$noun demandée — vérification en cours"
    }
}

/**
 * Message après la RELECTURE : compare l'état demandé ([targetActive]) à l'état réel
 * ([actualActive], `null` = le lien n'est plus listé). `null` = rien à ajouter (un 2xx dont la
 * relecture concorde garde son message « effectuée »).
 */
internal fun strategyVerificationMessage(
    targetActive: Boolean,
    actualActive: Boolean?,
    outcome: WriteOutcome,
): String? {
    if (actualActive == null) return "Ce lien n'apparaît plus dans la liste — actualisez l'écran."
    if (actualActive != targetActive) {
        val stillState = if (actualActive) "actif" else "en pause"
        return "La demande n'apparaît pas appliquée : le lien est toujours $stillState."
    }
    if (outcome == WriteOutcome.CONFIRMED) return null
    return if (targetActive) {
        "Confirmé : le lien est de nouveau actif."
    } else {
        "Confirmé : le lien est en pause."
    }
}

/**
 * Message d'un échec CERTAIN de l'écriture (jamais rejouée). Les textes ne contiennent aucun
 * identifiant. Le repository ne renvoie un `failure` que pour un refus explicite ou un échec avant
 * envoi : tout autre cas (5xx, timeout après envoi) arrive en `REQUESTED_UNCONFIRMED`.
 */
internal fun strategyWriteFailureMessage(error: Throwable): String {
    if (hasVpnBlock(error)) return EvaluateWriteGateUseCase.MESSAGE_VPN_NOT_CONNECTED
    if (error is HttpStatusException) {
        return when (error.code) {
            401 -> "Session expirée — reconnectez-vous."
            403 -> "Action refusée par le serveur (droits insuffisants)."
            404 -> "Lien introuvable — il a peut-être été supprimé sur le web. Actualisez l'écran."
            409 -> "Conflit : l'état de ce lien a changé — actualisez avant de réessayer."
            422 -> "Demande refusée par le serveur — vérifiez les allocations sur le web."
            429 -> "Trop de demandes — patientez avant de réessayer."
            else -> "Échec de la demande (erreur HTTP ${error.code}) — actualisez l'écran pour vérifier l'état."
        }
    }
    if (error is IOException) return "Serveur injoignable — la demande n'a pas été envoyée."
    return "Échec de la demande — actualisez l'écran pour vérifier l'état."
}

/** Message d'un échec de lecture de la liste. */
internal fun strategiesLoadErrorMessage(error: Throwable): String =
    if (hasVpnBlock(error)) LOAD_VPN_MESSAGE else LOAD_ERROR_MESSAGE

/** `VpnNotConnectedException` est-elle [error] ou l'une de ses causes ? */
private fun hasVpnBlock(error: Throwable): Boolean {
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        if (current is VpnNotConnectedException) return true
        current = current.cause
        depth++
    }
    return false
}
