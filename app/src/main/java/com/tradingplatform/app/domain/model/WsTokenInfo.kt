package com.tradingplatform.app.domain.model

import java.time.Instant

/**
 * Token WebSocket et son expiration.
 *
 * Utilisé par [com.tradingplatform.app.data.websocket.PrivateWsClient] pour planifier
 * le refresh proactif du token avant que le serveur ne ferme la connexion (code 4001).
 *
 * @param token JWT avec claim `type=websocket`, obtenu via `POST /v1/auth/ws-token`.
 * @param expiresAt Instant d'expiration du token (champ `expires_at` de la réponse API).
 */
data class WsTokenInfo(
    val token: String,
    val expiresAt: Instant,
)
