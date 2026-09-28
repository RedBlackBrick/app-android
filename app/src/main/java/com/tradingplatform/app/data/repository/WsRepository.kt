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

    /**
     * Toutes les mises à jour de portfolio reçues en temps réel.
     *
     * Le payload backend (`ws_payload` dans `app/portfolio/consumer.py`) ne
     * porte pas de NAV/P&L jour — c'est un instantané de l'exécution qui vient
     * d'avoir lieu (symbol/side/quantity/price) plus les totaux résultants
     * (total_value/cash_balance/positions_value). [WsUpdate.PortfolioUpdate.nav]
     * est renseigné depuis `total_value` pour compatibilité arrière ;
     * dailyPnl/totalPnl ne sont jamais envoyés par ce canal et restent null.
     */
    override val portfolioUpdates: Flow<WsUpdate.PortfolioUpdate> =
        wsClient.events.filterIsInstance<WsEvent.PortfolioUpdate>().map { event ->
            val totalValue = event.data.optDoubleOrNull("total_value")
            WsUpdate.PortfolioUpdate(
                portfolioId = event.data.optString("portfolio_id", null),
                symbol = event.data.optString("symbol", null),
                side = event.data.optString("side", null),
                quantity = event.data.optDoubleOrNull("quantity"),
                price = event.data.optDoubleOrNull("price"),
                totalValue = totalValue,
                cashBalance = event.data.optDoubleOrNull("cash_balance"),
                positionsValue = event.data.optDoubleOrNull("positions_value"),
                nav = totalValue,
                dailyPnl = event.data.optDoubleOrNull("daily_pnl"),
                totalPnl = event.data.optDoubleOrNull("total_pnl"),
            )
        }

    /**
     * Toutes les mises à jour de positions individuelles.
     *
     * Le payload backend (`ws_position_payload` dans `app/portfolio/consumer.py`)
     * ne porte pas de `position_id` — [WsUpdate.PositionUpdate.positionId] reste
     * null tant que le backend ne l'ajoute pas. `last_price` est le nom de champ
     * actuel côté serveur ; `current_price` est conservé en fallback pour
     * tolérer un éventuel ancien payload. `is_active` absent est traité comme
     * `true` (position toujours ouverte).
     */
    override val positionUpdates: Flow<WsUpdate.PositionUpdate> =
        wsClient.events.filterIsInstance<WsEvent.PositionUpdate>().map { event ->
            WsUpdate.PositionUpdate(
                positionId = event.data.optString("position_id", null),
                symbol = event.data.optString("symbol", null),
                side = event.data.optString("side", null),
                quantity = event.data.optDoubleOrNull("quantity"),
                averagePrice = event.data.optDoubleOrNull("average_price"),
                lastPrice = event.data.optDoubleOrNull("last_price")
                    ?: event.data.optDoubleOrNull("current_price"),
                unrealizedPnl = event.data.optDoubleOrNull("unrealized_pnl"),
                realizedPnl = event.data.optDoubleOrNull("realized_pnl"),
                isActive = !event.data.has("is_active") || event.data.optBoolean("is_active", true),
            )
        }

    /** Notifications utilisateur (alertes, événements stratégie). */
    override val notifications: Flow<WsUpdate.Notification> =
        wsClient.events.filterIsInstance<WsEvent.Notification>().map { event ->
            WsUpdate.Notification(
                notifType = event.notifType,
                title = event.title,
                body = event.body,
            )
        }

    /**
     * Mises à jour d'ordres en temps réel — mappé vers le domain model.
     *
     * PR-5c FINDING / PR-2.5 fix: `_send_order_ws_update` (app/execution/consumer.py,
     * app/execution/exit_consumer.py) never sets a `fill_price` key — only `price`
     * (the requested/limit price at SUBMITTED time, or the executed price on fills,
     * depending on the call site). [WsUpdate.OrderUpdate.fillPrice] is populated
     * from `price` accordingly; `fill_price` is never sent so it is not read.
     */
    override val orderUpdates: Flow<WsUpdate.OrderUpdate> =
        wsClient.events.filterIsInstance<WsEvent.OrderUpdate>().map { event ->
            WsUpdate.OrderUpdate(
                orderId = event.data.optString("order_id", null),
                symbol = event.data.optString("symbol", null),
                side = event.data.optString("side", null),
                status = event.data.optString("status", null),
                quantity = event.data.optIntOrNull("quantity"),
                fillPrice = event.data.optDoubleOrNull("price"),
            )
        }

    /** Signaux de stratégie en temps réel — mappé vers le domain model. */
    override val strategySignals: Flow<WsUpdate.StrategySignal> =
        wsClient.events.filterIsInstance<WsEvent.StrategySignal>().map { event ->
            WsUpdate.StrategySignal(
                signalId = event.data.optString("signal_id", null),
                strategyId = event.data.optString("strategy_id", null),
                symbol = event.data.optString("symbol", null),
                action = event.data.optString("action", null),
                confidence = event.data.optDoubleOrNull("confidence"),
                strategyType = event.data.optString("strategy_type", null),
            )
        }

    /**
     * Événements catalyst (earnings, spinoff).
     *
     * PR-5c FINDING / PR-2.5 fix: `_forward_to_websocket` (app/events/catalyst/consumer.py)
     * sends `{"catalyst_type", "symbol", "strategy_id", "data"}` — never `event_type`,
     * and no top-level `title`/`description` (those keys don't exist on this channel).
     * [description] is synthesized from whichever fields exist in the nested `data`
     * object (`EarningsEventData` / `SpinoffEventData`, app/events/catalyst/schemas.py)
     * for the given `catalyst_type` — see [buildCatalystDescription].
     */
    override val catalystEvents: Flow<WsUpdate.CatalystEvent> =
        wsClient.events.filterIsInstance<WsEvent.CatalystEvent>().map { event ->
            val catalystType = event.data.optString("catalyst_type", null)
            WsUpdate.CatalystEvent(
                symbol = event.data.optString("symbol", null),
                catalystType = catalystType,
                strategyId = event.data.optString("strategy_id", null),
                description = buildCatalystDescription(catalystType, event.data.optJSONObject("data")),
            )
        }

    /** Changements d'état de la connexion (Connected / Disconnected). */
    val connectionEvents: Flow<WsEvent> = wsClient.events

    /** Etat de connexion WS prive expose a l'UI (F5). */
    override val connectionState: StateFlow<WsConnectionState> = wsClient.connectionState
}

/**
 * Builds a short human-readable description of a catalyst event from the nested
 * event-specific `data` object (`EarningsEventData` / `SpinoffEventData`,
 * app/events/catalyst/schemas.py). Neither shape carries a `title`/`description`
 * field — this composes one from the fields that do exist. Returns null when
 * [nested] is absent or [catalystType] is neither known shape.
 */
private fun buildCatalystDescription(catalystType: String?, nested: org.json.JSONObject?): String? {
    if (nested == null) return null
    return when (catalystType) {
        "earnings" -> {
            val quarter = nested.optString("fiscal_quarter", "").ifEmpty { null }
            val direction = nested.optString("eps_direction", "").ifEmpty { null }
            listOfNotNull(quarter, direction).joinToString(" — ").ifEmpty { null }
        }
        "spinoff" -> {
            val child = nested.optString("child_symbol", "").ifEmpty { null }
            val phase = nested.optString("phase", "").ifEmpty { null }
            listOfNotNull(child?.let { "vers $it" }, phase).joinToString(" — ").ifEmpty { null }
        }
        else -> null
    }
}

/**
 * Extension to safely extract a nullable Double from JSONObject.
 * Returns null if the key is missing or the value is not a number.
 */
private fun org.json.JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

/**
 * Extension to safely extract a nullable Int from JSONObject.
 * Returns null if the key is missing or the value is not a number.
 */
private fun org.json.JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } else null
