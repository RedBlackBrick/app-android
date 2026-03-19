package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.websocket.PrivateWsClient
import com.tradingplatform.app.data.websocket.WsEvent
import com.tradingplatform.app.domain.repository.WsRepository as WsRepositoryInterface
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance

/**
 * Expose les flux WebSocket privés sous forme de [Flow] typés.
 *
 * Les abonnés n'ont pas à connaître le format brut des messages WS —
 * ils reçoivent directement les sous-types de [WsEvent].
 *
 * Ce repository ne fait PAS de cache Room : il s'agit de données live.
 * Les repositories métier (PortfolioRepositoryImpl, etc.) peuvent écouter ces
 * flows et mettre à jour Room localement s'ils le souhaitent.
 *
 * Instancié comme @Singleton via [WebSocketModule.provideWsRepository].
 */
class WsRepository(
    private val wsClient: PrivateWsClient,
) : WsRepositoryInterface {

    /** Toutes les mises à jour de portfolio reçues en temps réel. */
    override val portfolioUpdates: Flow<WsEvent.PortfolioUpdate> =
        wsClient.events.filterIsInstance()

    /** Toutes les mises à jour de positions individuelles. */
    override val positionUpdates: Flow<WsEvent.PositionUpdate> =
        wsClient.events.filterIsInstance()

    /** Toutes les mises à jour d'ordres. */
    val orderUpdates: Flow<WsEvent.OrderUpdate> =
        wsClient.events.filterIsInstance()

    /** Notifications utilisateur (alertes, événements stratégie). */
    override val notifications: Flow<WsEvent.Notification> =
        wsClient.events.filterIsInstance()

    /** Signaux de stratégie (pour information — ordres gérés côté serveur). */
    val strategySignals: Flow<WsEvent.StrategySignal> =
        wsClient.events.filterIsInstance()

    /** Événements catalyst (earnings, spinoff). */
    val catalystEvents: Flow<WsEvent.CatalystEvent> =
        wsClient.events.filterIsInstance()

    /** Changements d'état de la connexion (Connected / Disconnected). */
    val connectionEvents: Flow<WsEvent> = wsClient.events
}
