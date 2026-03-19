package com.tradingplatform.app.domain.repository

import com.tradingplatform.app.data.websocket.WsEvent
import kotlinx.coroutines.flow.Flow

/**
 * Interface domain pour l'accès aux flux WebSocket privés.
 *
 * Définie dans le domaine pour que les UseCases dépendent de cette abstraction
 * et non de l'implémentation [com.tradingplatform.app.data.repository.WsRepository].
 *
 * Expose uniquement les flux consommés par les UseCases — pas les événements
 * de bas niveau (connection state) qui relèvent de l'infrastructure.
 */
interface WsRepository {

    /** Toutes les mises à jour de portfolio reçues en temps réel. */
    val portfolioUpdates: Flow<WsEvent.PortfolioUpdate>

    /** Toutes les mises à jour de positions individuelles. */
    val positionUpdates: Flow<WsEvent.PositionUpdate>

    /** Notifications utilisateur (alertes, événements stratégie). */
    val notifications: Flow<WsEvent.Notification>
}
