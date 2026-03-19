package com.tradingplatform.app.di

import com.tradingplatform.app.data.repository.WsRepository
import com.tradingplatform.app.data.websocket.PrivateWsClient
import com.tradingplatform.app.domain.repository.WsRepository as WsRepositoryInterface
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Fournit les composants WebSocket privés.
 *
 * [PrivateWsClient] utilise le client OkHttp principal (pas @Named("bare")) car :
 * - Le certificat pinning s'applique au handshake HTTP Upgrade
 * - Le [VpnRequiredInterceptor] garantit que le WS passe par WireGuard
 * - L'auth WS est faite via le premier message JSON (pas un header) — OkHttp
 *   injecte quand même le header Authorization au handshake, le serveur l'ignore.
 *
 * [PrivateWsClient] est annoté `@Singleton @Inject constructor` — Hilt résout
 * ses dépendances directement depuis le graph (OkHttpClient principal,
 * AuthRepository, CoroutineScope, @Named("base_url") String).
 * Ce module fournit [WsRepository] (implémentation) bindé à l'interface domain
 * [WsRepositoryInterface] — les UseCases dépendent de l'interface, pas de l'implémentation.
 */
@Module
@InstallIn(SingletonComponent::class)
object WebSocketModule {

    @Provides
    @Singleton
    fun provideWsRepository(
        wsClient: PrivateWsClient,
    ): WsRepositoryInterface = WsRepository(wsClient)
}
