package com.tradingplatform.app.domain.usecase.pairing

import com.tradingplatform.app.domain.model.SetupQrData
import org.json.JSONException
import org.json.JSONObject
import javax.inject.Inject

/**
 * Parse le QR du panel web (onboarding mobile).
 * Format JSON : { "wg_private_key": "...", "wg_public_key_server": "...",
 *                 "endpoint": "...", "tunnel_ip": "...", "dns": "..." }
 *
 * La clé privée WireGuard ne doit jamais être loggée — [REDACTED] si debug nécessaire.
 */
class ParseSetupQrUseCase @Inject constructor() {

    operator fun invoke(raw: String): Result<SetupQrData> = runCatching {
        val trimmed = raw.trim()

        // Doit ressembler à du JSON
        if (!trimmed.startsWith("{")) {
            throw UnrecognizedQrException("QR non reconnu — pas de JSON")
        }

        val obj = try {
            JSONObject(trimmed)
        } catch (e: JSONException) {
            throw UnrecognizedQrException("JSON invalide : ${e.message}")
        }

        val wgPrivateKey = obj.optString("wg_private_key").takeIf { it.isNotEmpty() }
            ?: throw MalformedQrException("wg_private_key")
        val wgPublicKeyServer = obj.optString("wg_public_key_server").takeIf { it.isNotEmpty() }
            ?: throw MalformedQrException("wg_public_key_server")
        val endpoint = obj.optString("endpoint").takeIf { it.isNotEmpty() }
            ?: throw MalformedQrException("endpoint")
        val tunnelIp = obj.optString("tunnel_ip").takeIf { it.isNotEmpty() }
            ?: throw MalformedQrException("tunnel_ip")
        val dns = obj.optString("dns").takeIf { it.isNotEmpty() }
            ?: throw MalformedQrException("dns")

        // Validations
        require(wgPrivateKey.length == 44) { "wg_private_key must be 44 chars base64" }
        require(wgPublicKeyServer.length == 44) { "wg_public_key_server must be 44 chars base64" }
        require(endpoint.contains(":") && endpoint.substringAfterLast(":").toIntOrNull() != null) {
            "endpoint must be host:port format"
        }
        require(tunnelIp.matches(Regex("""\d+\.\d+\.\d+\.\d+/\d+"""))) {
            "tunnel_ip must be CIDR format (x.x.x.x/N)"
        }
        require(dns.matches(Regex("""\d+\.\d+\.\d+\.\d+"""))) {
            "dns must be a valid IP"
        }

        // wgPrivateKey ne doit jamais être loggé — [REDACTED]
        SetupQrData(
            wgPrivateKey = wgPrivateKey,
            wgPublicKeyServer = wgPublicKeyServer,
            endpoint = endpoint,
            tunnelIp = tunnelIp,
            dns = dns,
        )
    }
}
