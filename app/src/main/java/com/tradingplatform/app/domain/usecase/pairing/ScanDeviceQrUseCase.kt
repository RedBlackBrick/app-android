package com.tradingplatform.app.domain.usecase.pairing

import com.tradingplatform.app.domain.model.DevicePairingInfo
import java.net.URI
import javax.inject.Inject

class ScanDeviceQrUseCase @Inject constructor() {
    /**
     * Parse le QR Radxa — format URI :
     * pairing://radxa?id={device_id}&pub={wg_pubkey}&ip={local_ip}&port=8099
     *
     * Validation stricte : scheme, host, params obligatoires, IP littérale (pas de hostname),
     * port 8099, pubkey 44 chars base64.
     *
     * Utilise java.net.URI (pas android.net.Uri) pour rester dans le domaine pur Kotlin/JVM,
     * testable sans Android runtime.
     */
    suspend operator fun invoke(raw: String): Result<DevicePairingInfo> = runCatching {
        val uri = try {
            URI(raw.trim())
        } catch (e: Exception) {
            throw UnrecognizedQrException("URI invalide : ${e.message}")
        }

        if (uri.scheme != "pairing") {
            throw UnrecognizedQrException("Scheme non reconnu : ${uri.scheme}")
        }
        if (uri.host != "radxa") {
            throw UnrecognizedQrException("Host non reconnu : ${uri.host}")
        }

        val queryParams = parseQueryParams(uri.rawQuery ?: "")

        val deviceId = queryParams["id"]?.takeIf { it.isNotEmpty() }
            ?: throw MalformedQrException("id")
        val wgPubkey = queryParams["pub"]?.takeIf { it.isNotEmpty() }
            ?: throw MalformedQrException("pub")
        val ip = queryParams["ip"]?.takeIf { it.isNotEmpty() }
            ?: throw MalformedQrException("ip")
        val portStr = queryParams["port"]?.takeIf { it.isNotEmpty() }
            ?: throw MalformedQrException("port")

        // Valider l'IP — doit être une IP littérale (pas un hostname)
        // Regex couvre IPv4 uniquement (usage LAN Radxa)
        if (!IP_ADDRESS_PATTERN.matches(ip)) {
            throw MalformedQrException("ip format invalide")
        }

        // Valider le port — doit être 8099
        val port = portStr.toIntOrNull() ?: throw MalformedQrException("port non numérique")
        if (port != 8099) throw MalformedQrException("port inattendu : $port (attendu 8099)")

        // Valider la clé publique WireGuard — 44 chars base64
        if (wgPubkey.length != 44) {
            throw MalformedQrException("pub longueur invalide : ${wgPubkey.length} (attendu 44)")
        }

        DevicePairingInfo(
            deviceId = deviceId,
            wgPubkey = wgPubkey,
            localIp = ip,
            port = port,
        )
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").associate { pair ->
            val idx = pair.indexOf('=')
            if (idx < 0) pair to ""
            else pair.substring(0, idx) to pair.substring(idx + 1)
        }
    }

    companion object {
        // IPv4 littérale — bloque les hostnames (protection DNS rebinding)
        private val IP_ADDRESS_PATTERN = Regex(
            "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
        )
    }
}
