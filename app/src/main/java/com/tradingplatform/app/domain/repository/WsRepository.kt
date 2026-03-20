package com.tradingplatform.app.domain.repository

import com.tradingplatform.app.domain.model.WsUpdate
import kotlinx.coroutines.flow.Flow

/**
 * Interface domain pour l'accès aux flux WebSocket privés.
 *
 * Définie dans le domaine pour que les UseCases dépendent de cette abstraction
 * et non de l'implémentation data-layer.
 *
 * Expose uniquement les flux consommés par les UseCases — pas les événements
 * de bas niveau (connection state) qui relèvent de l'infrastructure.
 *
 * All types are domain models ([WsUpdate] subtypes) — no data-layer dependency.
 */
interface WsRepository {

    /** Toutes les mises à jour de portfolio reçues en temps réel. */
    val portfolioUpdates: Flow<WsUpdate.PortfolioUpdate>

    /** Toutes les mises à jour de positions individuelles. */
    val positionUpdates: Flow<WsUpdate.PositionUpdate>

    /** Notifications utilisateur (alertes, événements stratégie). */
    val notifications: Flow<WsUpdate.Notification>
}
