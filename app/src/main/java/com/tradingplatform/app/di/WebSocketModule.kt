package com.tradingplatform.app.di

import com.tradingplatform.app.data.repository.PublicWsRepositoryImpl
import com.tradingplatform.app.data.repository.WsRepository
import com.tradingplatform.app.data.websocket.PrivateWsClient
import com.tradingplatform.app.data.websocket.PublicWsClient
import com.tradingplatform.app.domain.repository.PublicWsRepository
import com.tradingplatform.app.domain.repository.WsRepository as WsRepositoryInterface
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Fournit les composants WebSocket privé et public.
 *
 * ## Canal privé (`/ws/private`)
 * [PrivateWsClient] utilise le client OkHttp principal (pas @Named("bare")) car :
 * - Le certificat pinning s'applique au handshake HTTP Upgrade
 * - Le [VpnRequiredInterceptor] garantit que le WS passe par WireGuard
 * - L'auth WS est faite via le premier message JSON (pas un header)
 *
 * ## Canal public (`/ws/public`)
 * [PublicWsClient] utilise également le client OkHttp principal car :
 * - Le certificat pinning reste obligatoire même pour le canal non authentifié
 * - Le VpnRequiredInterceptor garantit que le WS passe par WireGuard (toujours requis)
 * - Pas d'intercepteur Auth — le canal public n'envoie aucun header Authorization
 *
 * Les deux clients sont `@Singleton @Inject constructor` — Hilt résout leurs
 * dépendances directement depuis le graph.
 * Ce module fournit les implémentations bindées aux interfaces domain.
 */
@Module
@InstallIn(SingletonComponent::class)
object WebSocketModule {

    @Provides
    @Singleton
    fun provideWsRepository(
        wsClient: PrivateWsClient,
    ): WsRepositoryInterface = WsRepository(wsClient)

    @Provides
    @Singleton
    fun providePublicWsRepository(
        wsClient: PublicWsClient,
    ): PublicWsRepository = PublicWsRepositoryImpl(wsClient)
}
