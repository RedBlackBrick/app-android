package com.tradingplatform.app.security

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Construit un [RequestBody] octet-stream chiffré via `crypto_box_seal` prêt à être POSTé
 * vers un device LAN (Radxa : pairing, maintenance).
 *
 * Factorise le pattern dupliqué entre [com.tradingplatform.app.data.repository.PairingRepositoryImpl]
 * et [com.tradingplatform.app.data.repository.LocalMaintenanceRepositoryImpl] :
 *   1. Valide que [deviceIp] est RFC-1918 (anti-DNS-rebinding — CLAUDE.md §4)
 *   2. Décode la clé publique WireGuard base64 (44 chars → 32 bytes Curve25519)
 *   3. Chiffre le payload avec la clé publique
 *   4. Emballe en `application/octet-stream`
 *
 * Lève [IllegalStateException] si [deviceIp] n'est pas local — à capter par `runCatching`
 * dans l'appelant (pattern Result<T>).
 *
 * Usage :
 * ```kotlin
 * val body = sealedBoxHelper.sealLanBody(
 *     deviceIp = "10.42.0.5",
 *     radxaWgPubkeyBase64 = radxaWgPubkey,
 *     payload = payloadJson.toByteArray(Charsets.UTF_8),
 * )
 * val response = pairingApi.sendPin("http://$deviceIp:$port/pin", body)
 * ```
 */
fun SealedBoxHelper.sealLanBody(
    deviceIp: String,
    radxaWgPubkeyBase64: String,
    payload: ByteArray,
): RequestBody {
    check(isLocalNetwork(deviceIp)) {
        "Refused: $deviceIp is not a local network address (RFC-1918 required)"
    }
    val pubkey = Base64.decode(radxaWgPubkeyBase64, Base64.NO_WRAP)
    val encrypted = seal(payload, pubkey)
    return encrypted.toRequestBody(OCTET_STREAM)
}

private val OCTET_STREAM = "application/octet-stream".toMediaType()
