package com.tradingplatform.app.data.repository

import android.util.Base64
import com.tradingplatform.app.data.api.PairingLanApi
import com.tradingplatform.app.domain.exception.PairingDeviceException
import com.tradingplatform.app.domain.model.PairingStatus
import com.tradingplatform.app.domain.repository.PairingRepository
import com.tradingplatform.app.security.SealedBoxHelper
import com.tradingplatform.app.security.isLocalNetwork
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class PairingRepositoryImpl @Inject constructor(
    @Named("lan") private val pairingApi: PairingLanApi,
    private val sealedBoxHelper: SealedBoxHelper,
) : PairingRepository {

    companion object {
        private const val TAG = "PairingRepositoryImpl"
    }

    /**
     * Envoie le PIN de session à la Radxa via HTTP LAN (payload chiffré libsodium, TTL 120s).
     *
     * Règles critiques (CLAUDE.md §8) :
     * - Valide que l'IP est RFC-1918 avant tout appel réseau (anti-DNS-rebinding)
     * - Le session_pin et le local_token ne sont JAMAIS loggés — [REDACTED] uniquement
     * - Connexion HTTP uniquement (pas de certificate pinning — LAN local)
     * - Le payload JSON est chiffré avec crypto_box_seal (clé publique Curve25519 du Radxa)
     * - Le body envoyé est un octet-stream (bytes chiffrés, pas de JSON en clair)
     */
    override suspend fun sendPin(
        deviceIp: String,
        devicePort: Int,
        sessionId: String,
        sessionPin: String,
        localToken: String,
        nonce: String,
        radxaWgPubkey: String,
    ): Result<Unit> = runCatching {
        if (!isLocalNetwork(deviceIp)) {
            error("Refused: $deviceIp is not a local network address (RFC-1918 required)")
        }

        Timber.tag(TAG).d("PairingRepository: sending encrypted PIN to $deviceIp:$devicePort sessionId=$sessionId pin=[REDACTED] token=[REDACTED] nonce=[REDACTED]")

        // Construire le JSON payload (nonce included for anti-replay)
        val payloadJson = JSONObject().apply {
            put("session_id", sessionId)
            put("session_pin", sessionPin)
            put("local_token", localToken)
            put("nonce", nonce)
        }.toString()

        // Décoder la clé publique WireGuard base64 → 32 bytes Curve25519
        val pubkeyBytes = Base64.decode(radxaWgPubkey, Base64.NO_WRAP)

        // Chiffrer avec crypto_box_seal
        val encrypted = sealedBoxHelper.seal(payloadJson.toByteArray(Charsets.UTF_8), pubkeyBytes)

        // Envoyer en octet-stream
        val url = "http://$deviceIp:$devicePort/pin"
        val requestBody = encrypted.toRequestBody("application/octet-stream".toMediaType())

        val response = pairingApi.sendPin(url, requestBody)
        if (!response.isSuccessful) {
            val body = response.errorBody()?.string()?.takeIf { it.isNotBlank() } ?: ""
            throw PairingDeviceException(httpCode = response.code(), body = body)
        }
    }

    /**
     * Poll le statut de pairing toutes les 2 secondes jusqu'à PAIRED ou FAILED.
     *
     * Règles critiques (CLAUDE.md §8) :
     * - Valide que l'IP est RFC-1918 avant chaque appel
     * - Délai de 2s imposé — ne pas modifier (économie batterie + charge Radxa)
     * - La boucle s'arrête dès PAIRED ou FAILED (usage unique du session_pin)
     */
    override fun pollStatus(
        deviceIp: String,
        devicePort: Int,
        sessionId: String,
    ): Flow<PairingStatus> = flow {
        if (!isLocalNetwork(deviceIp)) {
            Timber.tag(TAG).e("PairingRepository: pollStatus refused — $deviceIp is not RFC-1918")
            emit(PairingStatus.FAILED)
            return@flow
        }

        val url = "http://$deviceIp:$devicePort/status?session_id=$sessionId"

        while (true) {
            val status = runCatching {
                val response = pairingApi.getStatus(url)
                if (response.isSuccessful) {
                    val statusStr = response.body()?.get("status")?.toString() ?: "failed"
                    PairingStatus.fromString(statusStr)
                } else {
                    Timber.tag(TAG).w("PairingRepository: poll status HTTP ${response.code()}")
                    PairingStatus.PENDING
                }
            }.getOrElse { e ->
                Timber.tag(TAG).e(e, "PairingRepository: poll status network error")
                PairingStatus.PENDING
            }

            emit(status)

            // Terminer le flow dès qu'on a un état terminal
            if (status == PairingStatus.PAIRED || status == PairingStatus.FAILED) break

            // Délai obligatoire de 2s — ne pas modifier
            delay(2_000L)
        }
    }
}
