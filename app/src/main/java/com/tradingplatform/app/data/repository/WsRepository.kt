package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.websocket.PrivateWsClient
import com.tradingplatform.app.data.websocket.WsEvent
import com.tradingplatform.app.domain.model.WsConnectionState
import com.tradingplatform.app.domain.model.WsUpdate
import com.tradingplatform.app.domain.repository.WsRepository as WsRepositoryInterface
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map

/**
 * Expose les flux WebSocket privés sous forme de [Flow] typés.
 *
 * Les abonnés n'ont pas à connaître le format brut des messages WS —
 * ils reçoivent directement les domain models ([WsUpdate] subtypes).
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
    override val portfolioUpdates: Flow<WsUpdate.PortfolioUpdate> =
        wsClient.events.filterIsInstance<WsEvent.PortfolioUpdate>().map { event ->
            WsUpdate.PortfolioUpdate(
                portfolioId = event.data.optString("portfolio_id", null),
                nav = event.data.optDoubleOrNull("nav"),
                dailyPnl = event.data.optDoubleOrNull("daily_pnl"),
                totalPnl = event.data.optDoubleOrNull("total_pnl"),
            )
        }

    /** Toutes les mises à jour de positions individuelles. */
    override val positionUpdates: Flow<WsUpdate.PositionUpdate> =
        wsClient.events.filterIsInstance<WsEvent.PositionUpdate>().map { event ->
            WsUpdate.PositionUpdate(
                positionId = event.data.optString("position_id", null),
                symbol = event.data.optString("symbol", null),
                unrealizedPnl = event.data.optDoubleOrNull("unrealized_pnl"),
                currentPrice = event.data.optDoubleOrNull("current_price"),
            )
        }

    /** Toutes les mises à jour d'ordres (data-layer only — non exposé au domaine). */
    val orderUpdates: Flow<WsEvent.OrderUpdate> =
        wsClient.events.filterIsInstance()

    /** Notifications utilisateur (alertes, événements stratégie). */
    override val notifications: Flow<WsUpdate.Notification> =
        wsClient.events.filterIsInstance<WsEvent.Notification>().map { event ->
            WsUpdate.Notification(
                notifType = event.notifType,
                title = event.title,
                body = event.body,
            )
        }

    /** Signaux de stratégie (pour information — ordres gérés côté serveur). */
    val strategySignals: Flow<WsEvent.StrategySignal> =
        wsClient.events.filterIsInstance()

    /** Événements catalyst (earnings, spinoff). */
    val catalystEvents: Flow<WsEvent.CatalystEvent> =
        wsClient.events.filterIsInstance()

    /** Changements d'état de la connexion (Connected / Disconnected). */
    val connectionEvents: Flow<WsEvent> = wsClient.events

    /** Etat de connexion WS prive expose a l'UI (F5). */
    override val connectionState: StateFlow<WsConnectionState> = wsClient.connectionState
}

/**
 * Extension to safely extract a nullable Double from JSONObject.
 * Returns null if the key is missing or the value is not a number.
 */
private fun org.json.JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null
