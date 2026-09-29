package com.tradingplatform.app.ui.screens.devices

import com.tradingplatform.app.domain.model.BrokerGatewayStatus

/** Tonalité d'un statut — la couleur concrète est choisie côté UI (thème clair / sombre). */
internal enum class StatusTone { SUCCESS, WARNING, ERROR, NEUTRAL }

/** Libellé français + tonalité d'un statut de gateway ou de connexion broker. */
internal data class StatusDisplay(val label: String, val tone: StatusTone)

/**
 * Statut de la broker gateway d'un device, avec le vocabulaire RÉEL du backend
 * (`broker_gateway_status`) : `stopped`, `starting`, `running`, `error`, `configured`.
 *
 * - `running` → succès ; `starting` → avertissement ; `error` → erreur ;
 * - `stopped` / `configured` → neutre ;
 * - toute autre valeur (vocabulaire futur) → neutre, libellé brut capitalisé (jamais masqué).
 */
internal fun gatewayStatusDisplay(status: String): StatusDisplay {
    val normalized = status.trim().lowercase()
    return when (normalized) {
        "running" -> StatusDisplay("En marche", StatusTone.SUCCESS)
        "starting" -> StatusDisplay("Démarrage", StatusTone.WARNING)
        "error" -> StatusDisplay("Erreur", StatusTone.ERROR)
        "stopped" -> StatusDisplay("Arrêtée", StatusTone.NEUTRAL)
        "configured" -> StatusDisplay("Configurée", StatusTone.NEUTRAL)
        "" -> StatusDisplay("Inconnu", StatusTone.NEUTRAL)
        else -> StatusDisplay(normalized.replaceFirstChar { it.uppercase() }, StatusTone.NEUTRAL)
    }
}

/**
 * Résumé affiché pour la gateway d'un device : absente → « Non configurée », désactivée →
 * « Désactivée » (le statut brut n'est alors pas significatif), sinon [gatewayStatusDisplay].
 */
internal fun brokerGatewayDisplay(gateway: BrokerGatewayStatus?): StatusDisplay = when {
    gateway == null -> StatusDisplay("Non configurée", StatusTone.NEUTRAL)
    !gateway.enabled -> StatusDisplay("Désactivée", StatusTone.NEUTRAL)
    else -> gatewayStatusDisplay(gateway.status)
}

/** Nom lisible d'un `broker_code` : `interactive_brokers` → « Interactive brokers ». */
internal fun brokerDisplayName(code: String): String =
    code.trim().replace('_', ' ').replaceFirstChar { it.uppercase() }

/** Statut d'une connexion broker (`connection_status`) : libellé français + tonalité. */
internal fun brokerConnectionDisplay(status: String?): StatusDisplay {
    val normalized = status?.trim()?.lowercase().orEmpty()
    return when (normalized) {
        "connected" -> StatusDisplay("Connecté", StatusTone.SUCCESS)
        "active" -> StatusDisplay("Actif", StatusTone.SUCCESS)
        "disconnected" -> StatusDisplay("Déconnecté", StatusTone.ERROR)
        "inactive" -> StatusDisplay("Inactif", StatusTone.ERROR)
        "error" -> StatusDisplay("Erreur", StatusTone.ERROR)
        "deploying" -> StatusDisplay("Déploiement", StatusTone.WARNING)
        "pending" -> StatusDisplay("En attente", StatusTone.WARNING)
        "" -> StatusDisplay("Inconnu", StatusTone.NEUTRAL)
        else -> StatusDisplay(normalized.replaceFirstChar { it.uppercase() }, StatusTone.NEUTRAL)
    }
}
