package com.tradingplatform.app.domain.repository

import com.tradingplatform.app.domain.model.WsConnectionState
import com.tradingplatform.app.domain.model.WsUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface domain pour l'accès aux flux WebSocket privés.
 *
 * Définie dans le domaine pour que les UseCases dépendent de cette abstraction
 * et non de l'implémentation data-layer.
 *
 * Expose les flux consommes par les UseCases et l'etat de connexion (F5).
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

    /**
     * Etat de la connexion WebSocket privee (F5).
     *
     * Expose a l'UI pour afficher un indicateur discret (dot colore).
     * Le ViewModel applique un debounce de 2s avant de propager [WsConnectionState.Disconnected]
     * pour eviter le flicker lors de transitions rapides.
     */
    val connectionState: StateFlow<WsConnectionState>
}
