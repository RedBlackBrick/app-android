package com.tradingplatform.app.domain.repository

import com.tradingplatform.app.domain.model.Order
import com.tradingplatform.app.domain.model.Page
import com.tradingplatform.app.domain.model.WriteOutcome

interface OrdersRepository {
    suspend fun listActiveOrders(portfolioId: String): Result<List<Order>>

    /**
     * Paginated terminal order history. [Page.total] carries the backend
     * `count` field so callers can derive `hasMore` without guessing from
     * whether the page came back full.
     */
    suspend fun listOrderHistory(
        portfolioId: String,
        limit: Int = 100,
        offset: Int = 0,
    ): Result<Page<Order>>

    /**
     * Demande l'annulation d'un ordre (`POST /v1/orders/{id}/cancel`). Écriture : **jamais rejouée**
     * (ni retry OkHttp, ni ici).
     *
     * - 2xx → `success(CONFIRMED)` ;
     * - 5xx (le backend convertit beaucoup d'erreurs en 500 « Cancellation failed ») ou
     *   timeout / IOException après envoi → `success(REQUESTED_UNCONFIRMED)` : l'appelant doit
     *   relire l'état de l'ordre ;
     * - 409 (ordre dans un état non annulable) → `failure(HttpStatusException(409, …))` ;
     * - VPN absent, échec avant envoi, 400/401/403/404/422… → `failure`.
     *
     * Un `CONFIRMED` ne garantit rien côté broker : le backend marque l'ordre annulé localement
     * même si le broker refuse ou ignore l'annulation.
     */
    suspend fun cancelOrder(orderId: Long): Result<WriteOutcome>
}
