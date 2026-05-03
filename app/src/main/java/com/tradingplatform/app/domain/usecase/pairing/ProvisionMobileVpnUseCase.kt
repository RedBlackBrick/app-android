package com.tradingplatform.app.domain.usecase.pairing

import android.os.Build
import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.domain.model.MobileProvisioningResult
import com.tradingplatform.app.domain.model.SetupQrData
import com.tradingplatform.app.domain.repository.MobileProvisioningRepository
import com.tradingplatform.app.vpn.WireGuardConfig
import com.tradingplatform.app.vpn.WireGuardManager
import com.tradingplatform.app.vpn.WireGuardPeer
import com.wireguard.crypto.KeyPair
import timber.log.Timber
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject

/**
 * End-to-end orchestration for the mobile-provisioning flow.
 *
 * Given a parsed [SetupQrData] (the QR payload, no private material), this use
 * case:
 *
 *   1. generates a fresh WireGuard keypair **on-device** (the private key
 *      never leaves the phone);
 *   2. computes `pin_proof = HMAC-SHA256(claim_token, wg_pubkey)`;
 *   3. POSTs `/v1/me/mobile-provisioning/{id}/register` over public 8443
 *      via [MobileProvisioningRepository] (no VPN yet at this point);
 *   4. persists the WG configuration in [EncryptedDataStore];
 *   5. asks [WireGuardManager] to bring the tunnel up.
 *
 * Failure of any step rolls back nothing — the server-side session is consumed
 * once nonce is delivered, so a retry must produce a fresh QR. The caller
 * (SetupViewModel) surfaces the error to the user with that message.
 */
class ProvisionMobileVpnUseCase @Inject constructor(
    private val repository: MobileProvisioningRepository,
    private val wireGuardManager: WireGuardManager,
    private val dataStore: EncryptedDataStore,
) {
    suspend operator fun invoke(setupData: SetupQrData): Result<Unit> = runCatching {
        // 1. On-device keypair. wireguard-android's KeyPair() generates a
        //    Curve25519 key via Android's SecureRandom — no SecureRandom seed
        //    handling needed on our side.
        val keyPair = KeyPair()
        val privateKey = keyPair.privateKey.toBase64()  // never logged
        val publicKey = keyPair.publicKey.toBase64()
        Timber.i(
            "ProvisionMobileVpnUseCase: keypair generated, pubkey=${publicKey.take(8)}…",
        )

        // 2. HMAC pin_proof.
        val pinProof = hmacSha256Hex(setupData.claimToken, publicKey)

        // 3. Register call. The host comes from the QR (vps_endpoint host
        //    part); we trust it because cert pinning binds the response to
        //    the Caddy Root CA — a malicious QR pointing at an attacker host
        //    would fail TLS pinning, not silently succeed.
        val host = setupData.vpsEndpoint.substringBeforeLast(":")
        val deviceLabel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val fcmToken = dataStore.readString(DataStoreKeys.PENDING_FCM_TOKEN)
        val result: MobileProvisioningResult = repository.register(
            host = host,
            provisioningId = setupData.provisioningId,
            wgPubkey = publicKey,
            pinProof = pinProof,
            nonce = setupData.nonce,
            deviceLabel = deviceLabel.ifBlank { null },
            fcmToken = fcmToken,
        ).getOrThrow()

        // 4. Persist. private key first — if writeString fails the rest doesn't
        //    matter, but if a later step fails after we've written we still
        //    have a usable tunnel config in the store for next-launch recovery.
        dataStore.writeString(DataStoreKeys.WG_PRIVATE_KEY, privateKey)
        dataStore.writeString(DataStoreKeys.WG_ENDPOINT, result.endpoint)
        dataStore.writeString(DataStoreKeys.WG_SERVER_PUBKEY, result.serverPubkey)
        dataStore.writeString(DataStoreKeys.WG_TUNNEL_IP, result.tunnelIp)
        dataStore.writeString(DataStoreKeys.WG_DNS, result.dns)

        // 5. Bring the tunnel up. WireGuardManager.connect runs asynchronously
        //    on Dispatchers.IO; the caller observes wireGuardManager.state to
        //    discover Connected vs Error.
        wireGuardManager.connect(
            WireGuardConfig(
                privateKey = privateKey,
                address = "${result.tunnelIp}/32",
                dns = result.dns.ifEmpty { DEFAULT_DNS_FALLBACK },
                peer = WireGuardPeer(
                    publicKey = result.serverPubkey,
                    endpoint = result.endpoint,
                    allowedIPs = result.allowedIps,
                ),
            )
        )
    }

    private fun hmacSha256Hex(key: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val raw = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        return raw.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        // Empty DNS (the server's preferred default) is treated as
        // "phone keeps its LAN DNS"; wireguard-android's parseDnsServers
        // requires at least one entry, hence the fallback. 1.1.1.1 is the
        // existing fallback used by reconnect() in WireGuardManager.
        const val DEFAULT_DNS_FALLBACK = "1.1.1.1"
    }
}
