package com.tradingplatform.app.domain.repository

import com.tradingplatform.app.domain.model.MobileProvisioningResult

/**
 * Single-use HTTPS call against the public 8443 endpoint that completes a
 * mobile-provisioning session.
 *
 * Pre-conditions: the phone has scanned the QR (which yielded
 * [com.tradingplatform.app.domain.model.SetupQrData]), generated a fresh
 * WireGuard keypair on-device, and computed `pin_proof` as
 * `HMAC-SHA256(claim_token, wg_pubkey)`.
 *
 * This repository is the only network call in the app that runs **before VPN
 * is up** — it bypasses [com.tradingplatform.app.data.api.interceptor.VpnRequiredInterceptor]
 * and the auth/CSRF interceptors by design. Cert pinning is preserved against
 * the Caddy Root CA so a hostile network cannot MITM the call.
 */
interface MobileProvisioningRepository {

    suspend fun register(
        host: String,
        provisioningId: String,
        wgPubkey: String,
        pinProof: String,
        nonce: String,
        deviceLabel: String?,
        fcmToken: String?,
    ): Result<MobileProvisioningResult>
}
