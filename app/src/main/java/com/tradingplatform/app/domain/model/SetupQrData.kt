package com.tradingplatform.app.domain.model

import java.time.Instant

/**
 * Payload encoded in the onboarding QR served by the web `/download` page.
 *
 * The QR contains **no private material** — the WireGuard keypair is generated
 * on the phone during the `/register` call, and only the public key reaches the
 * server. [claimToken] is a one-time secret used as the HMAC key to prove
 * possession of the QR; [nonce] is consumed atomically server-side to prevent
 * replay.
 *
 * Versioning: [version] pins the payload schema. The current version is `1`.
 * Future schema bumps must be rejected at parse time so old apps don't blindly
 * accept new shapes.
 */
data class SetupQrData(
    val version: Int,
    val provisioningId: String,
    val claimToken: String,
    val nonce: String,
    val vpsEndpoint: String,
    val vpsPubkey: String,
    val dns: String,
    val expiresAt: Instant,
)
