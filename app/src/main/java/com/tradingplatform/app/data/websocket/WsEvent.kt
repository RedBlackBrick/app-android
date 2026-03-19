package com.tradingplatform.app.data.websocket

import org.json.JSONObject

/**
 * Événements émis par [PrivateWsClient] sur le canal privé `/ws/private`.
 *
 * Chaque message serveur porte un champ `type` discriminant et un champ `data`.
 * Le payload `data` est exposé brut (JSONObject) pour rester découplé du schéma
 * serveur — les Repository ou UseCase aval le désérialiseront selon leurs besoins.
 *
 * Types connus (cf. TP2 private_handler.py) :
 *   portfolio_update, position_update, order_update, notification,
 *   strategy_signal, catalyst_event, ping, token_refreshed, error
 */
sealed class WsEvent {

    /** Mise à jour du portfolio (NAV, P&L global). */
    data class PortfolioUpdate(val data: JSONObject) : WsEvent()

    /** Mise à jour d'une position individuelle. */
    data class PositionUpdate(val data: JSONObject) : WsEvent()

    /** Mise à jour d'un ordre (statut, fill). */
    data class OrderUpdate(val data: JSONObject) : WsEvent()

    /**
     * Notification utilisateur (alerte trading, événement stratégie, etc.).
     * Le champ `type` du payload data (ex: "info", "warning") est extrait pour
     * faciliter le filtrage sans re-parser le JSONObject entier.
     */
    data class Notification(
        val notifType: String,
        val title: String,
        val body: String,
        val data: JSONObject,
    ) : WsEvent()

    /** Signal de stratégie (utilisé pour information, pas pour les ordres). */
    data class StrategySignal(val data: JSONObject) : WsEvent()

    /** Événement catalyst (earnings, spinoff). */
    data class CatalystEvent(val data: JSONObject) : WsEvent()

    /** Connexion établie et authentifiée. */
    data object Connected : WsEvent()

    /** Connexion fermée (normale ou suite à une erreur). */
    data class Disconnected(val reason: String?) : WsEvent()
}
