package com.tradingplatform.app.ui.screens.alerts

import com.tradingplatform.app.domain.model.InboxNotification
import com.tradingplatform.app.vpn.VpnNotConnectedException
import java.io.IOException
import java.time.Instant

/**
 * Logique pure (sans Compose ni Android) du segment « Serveur » de l'écran Alertes : catégorie et
 * libellé d'un type de notification, titre d'affichage, classification d'un échec de chargement.
 */

// ── Types de notification serveur ─────────────────────────────────────────────

/**
 * Regroupement d'affichage des types serveur. Miroir des catégories de préférences du backend
 * (`strategy_signal` / `risk_alert` / `system`) + [GENERIC] pour tout type inconnu (le backend
 * n'a pas d'enum fermé : la colonne `type` est une chaîne libre).
 */
internal enum class InboxCategory { RISK, STRATEGY, SYSTEM, GENERIC }

/** Libellé d'un type inconnu (ou vide). */
internal const val INBOX_GENERIC_LABEL = "Notification"

private fun normalizeInboxType(type: String): String = type.trim().lowercase()

/** Catégorie d'un type serveur ; tout type inconnu → [InboxCategory.GENERIC]. */
internal fun inboxCategory(type: String): InboxCategory = when (normalizeInboxType(type)) {
    "risk_alert" -> InboxCategory.RISK
    "signal_blocked",
    "strategy_degradation",
    "strategy_version_available",
    "catalyst_event",
    "spinoff_approaching",
    -> InboxCategory.STRATEGY
    "system",
    "network_degradation",
    "device_offline",
    "device_online",
    "device_unpaired",
    "device_paired",
    -> InboxCategory.SYSTEM
    else -> InboxCategory.GENERIC
}

/**
 * Libellé court d'un type serveur (ligne de méta « Risque · Il y a 5 min » et TalkBack) ;
 * tout type inconnu → [INBOX_GENERIC_LABEL].
 */
internal fun inboxTypeLabel(type: String): String = when (normalizeInboxType(type)) {
    "risk_alert" -> "Risque"
    "system" -> "Système"
    "network_degradation" -> "Réseau"
    "device_offline" -> "Appareil hors ligne"
    "device_online" -> "Appareil en ligne"
    "device_unpaired" -> "Appareil désappairé"
    "device_paired" -> "Appareil appairé"
    "signal_blocked" -> "Signal bloqué"
    "strategy_degradation" -> "Stratégie"
    "strategy_version_available" -> "Nouvelle version"
    "catalyst_event" -> "Catalyseur"
    "spinoff_approaching" -> "Spin-off"
    else -> INBOX_GENERIC_LABEL
}

/** Titre affiché : celui du serveur, sinon (absent ou vide) le libellé du type. */
internal fun inboxTitle(item: InboxNotification): String =
    item.title?.trim()?.takeIf { it.isNotEmpty() } ?: inboxTypeLabel(item.type)

/**
 * `false` quand le serveur n'a pas envoyé de date (le dépôt la remplace alors par
 * [Instant.EPOCH]) : l'UI masque l'horodatage plutôt que d'afficher 1970.
 */
internal fun inboxHasTimestamp(item: InboxNotification): Boolean = item.createdAt != Instant.EPOCH

// ── Échec de chargement ───────────────────────────────────────────────────────

internal const val INBOX_VPN_REQUIRED_TITLE = "Nécessite le VPN"
internal const val INBOX_VPN_REQUIRED_MESSAGE =
    "Les notifications du serveur ne sont disponibles que lorsque le tunnel VPN est actif."
internal const val INBOX_UNREACHABLE_MESSAGE =
    "Serveur injoignable. Vérifiez la connexion puis réessayez."
internal const val INBOX_GENERIC_ERROR_MESSAGE =
    "Impossible de charger les notifications du serveur."

private const val MAX_CAUSE_DEPTH = 8

private fun Throwable.causeChain(): Sequence<Throwable> =
    generateSequence(this) { it.cause }.take(MAX_CAUSE_DEPTH)

/**
 * État affiché après l'échec du chargement de la boîte serveur.
 *
 * `VpnNotConnectedException` étend `IOException` : elle est donc testée AVANT toute
 * `IOException` (sinon un tunnel absent serait présenté comme « serveur injoignable »). La chaîne
 * des causes est examinée (l'exception peut être enveloppée). Le message brut de l'exception
 * n'est jamais affiché (technique, souvent en anglais).
 */
internal fun inboxFailureState(error: Throwable): InboxUiState = when {
    error.causeChain().any { it is VpnNotConnectedException } -> InboxUiState.VpnRequired
    error.causeChain().any { it is IOException } -> InboxUiState.Error(INBOX_UNREACHABLE_MESSAGE)
    else -> InboxUiState.Error(INBOX_GENERIC_ERROR_MESSAGE)
}
