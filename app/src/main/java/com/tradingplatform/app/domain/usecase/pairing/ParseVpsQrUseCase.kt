package com.tradingplatform.app.domain.usecase.pairing

import com.tradingplatform.app.domain.model.PairingSession
import org.json.JSONException
import org.json.JSONObject
import javax.inject.Inject

class UnrecognizedQrException(message: String = "QR non reconnu") : Exception(message)
class MalformedQrException(field: String) : Exception("QR malformé — champ manquant : $field")

class ParseVpsQrUseCase @Inject constructor() {
    /**
     * Parse le QR VPS — format JSON :
     * { "session_id": "uuid", "session_pin": "472938", "device_wg_ip": "10.42.0.5" }
     *
     * Le session_pin n'est JAMAIS loggé — [REDACTED] si debug nécessaire.
     */
    suspend operator fun invoke(raw: String): Result<PairingSession> = runCatching {
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

        val sessionId = obj.optString("session_id").takeIf { it.isNotEmpty() }
            ?: throw MalformedQrException("session_id")
        val sessionPin = obj.optString("session_pin").takeIf { it.isNotEmpty() }
            ?: throw MalformedQrException("session_pin")
        val deviceWgIp = obj.optString("device_wg_ip").takeIf { it.isNotEmpty() }
            ?: throw MalformedQrException("device_wg_ip")

        // session_pin ne doit jamais être loggé — [REDACTED]
        PairingSession(
            sessionId = sessionId,
            sessionPin = sessionPin,
            deviceWgIp = deviceWgIp,
        )
    }
}
