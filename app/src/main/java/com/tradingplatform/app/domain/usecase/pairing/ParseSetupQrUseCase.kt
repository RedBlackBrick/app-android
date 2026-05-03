package com.tradingplatform.app.domain.usecase.pairing

import com.tradingplatform.app.domain.model.SetupQrData
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeParseException
import javax.inject.Inject

/**
 * Parses the onboarding QR served by the web `/download` page.
 *
 * Expected payload (mobile_provisioning v1):
 * ```
 * {
 *   "v": 1,
 *   "type": "mobile_provisioning",
 *   "provisioning_id": "<uuid>",
 *   "claim_token": "<43-char base64url>",
 *   "nonce": "<64-char hex>",
 *   "vps_endpoint": "<host>:51820",
 *   "vps_pubkey": "<44-char base64>",
 *   "dns": "" | "<ipv4>",
 *   "expires_at": "<ISO-8601>"
 * }
 * ```
 *
 * The QR contains no private material — see [SetupQrData] for the security
 * model. claim_token must be kept off logs even though it's not a private key
 * (single-use, but a leak inside its 5-min TTL would let an attacker provision
 * their own phone using this user's session).
 */
class ParseSetupQrUseCase @Inject constructor() {

    operator fun invoke(raw: String): Result<SetupQrData> = runCatching {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{")) {
            throw UnrecognizedQrException("QR non reconnu — pas de JSON")
        }

        val obj = try {
            JSONObject(trimmed)
        } catch (e: JSONException) {
            throw UnrecognizedQrException("JSON invalide : ${e.message}")
        }

        // Discriminate from any future QR shape that may be served at the same
        // endpoint. We accept only what we explicitly know how to handle.
        val type = obj.optString("type")
        if (type != "mobile_provisioning") {
            throw UnrecognizedQrException("QR non reconnu — type='$type'")
        }
        val version = obj.optInt("v", 0)
        if (version != 1) {
            throw MalformedQrException("v (got=$version, expected=1)")
        }

        val provisioningId = obj.optString("provisioning_id").takeIf { it.isNotEmpty() }
            ?: throw MalformedQrException("provisioning_id")
        val claimToken = obj.optString("claim_token").takeIf { it.isNotEmpty() }
            ?: throw MalformedQrException("claim_token")
        val nonce = obj.optString("nonce").takeIf { it.isNotEmpty() }
            ?: throw MalformedQrException("nonce")
        val vpsEndpoint = obj.optString("vps_endpoint").takeIf { it.isNotEmpty() }
            ?: throw MalformedQrException("vps_endpoint")
        val vpsPubkey = obj.optString("vps_pubkey").takeIf { it.isNotEmpty() }
            ?: throw MalformedQrException("vps_pubkey")
        val expiresAtRaw = obj.optString("expires_at").takeIf { it.isNotEmpty() }
            ?: throw MalformedQrException("expires_at")
        val dns = obj.optString("dns")  // empty allowed — phone falls back to LAN DNS

        // Format checks. claim_token is 43 url-safe-base64 chars (32 bytes).
        if (claimToken.length != 43) {
            throw MalformedQrException("claim_token (length ${claimToken.length}, expected 43)")
        }
        // nonce is 64 hex chars (32 bytes).
        if (!nonce.matches(Regex("^[0-9a-fA-F]{64}$"))) {
            throw MalformedQrException("nonce (must be 64 hex chars)")
        }
        if (vpsPubkey.length != 44) {
            throw MalformedQrException("vps_pubkey (length ${vpsPubkey.length}, expected 44)")
        }
        if (!vpsEndpoint.contains(":") ||
            vpsEndpoint.substringAfterLast(":").toIntOrNull() == null
        ) {
            throw MalformedQrException("vps_endpoint (must be host:port)")
        }
        if (dns.isNotEmpty() && !dns.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$"))) {
            throw MalformedQrException("dns (must be empty or IPv4)")
        }

        val expiresAt = try {
            Instant.parse(expiresAtRaw)
        } catch (e: DateTimeParseException) {
            throw MalformedQrException("expires_at (must be ISO-8601: ${e.message})")
        }
        if (expiresAt.isBefore(Instant.now())) {
            throw ExpiredQrException(expiresAt)
        }

        // claim_token never goes to logs — [REDACTED] applies even if we add
        // debug printing here later.
        SetupQrData(
            version = version,
            provisioningId = provisioningId,
            claimToken = claimToken,
            nonce = nonce,
            vpsEndpoint = vpsEndpoint,
            vpsPubkey = vpsPubkey,
            dns = dns,
            expiresAt = expiresAt,
        )
    }
}

class ExpiredQrException(val expiredAt: Instant) :
    Exception("QR expiré (depuis $expiredAt). Régénérez-en un nouveau sur la page Téléchargements.")
