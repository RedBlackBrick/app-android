package com.tradingplatform.app.data.api

import retrofit2.Response
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Écritures sur les ordres. Interface DÉDIÉE, construite sur le Retrofit `@Named("write")`
 * (`di/WriteNetworkModule.kt`, client OkHttp avec `retryOnConnectionFailure(false)`) : une écriture
 * n'est JAMAIS rejouée automatiquement. Fournie par `di/OrdersStrategiesModule.kt`.
 *
 * `POST /v1/orders/{order_id}/cancel` : pas de corps de requête, pas d'`Idempotency-Key` côté
 * backend (une seconde annulation donne 409). Le 200 renvoie l'objet Order (`status: "cancelled"`)
 * mais le corps est volontairement ignoré (`Response<Unit>`) : seul le code HTTP compte, et un
 * corps illisible ne doit jamais transformer un succès serveur en échec côté app.
 */
interface OrdersWriteApi {
    @POST("v1/orders/{order_id}/cancel")
    suspend fun cancelOrder(@Path("order_id") orderId: Long): Response<Unit>
}
