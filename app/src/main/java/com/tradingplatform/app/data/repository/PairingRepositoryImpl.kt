package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.api.PairingLanApi
import com.tradingplatform.app.domain.exception.PairingDeviceException
import com.tradingplatform.app.domain.model.PairingStatus
import com.tradingplatform.app.domain.repository.PairingRepository
import com.tradingplatform.app.domain.util.runCatchingCancellable
import com.tradingplatform.app.security.SealedBoxHelper
import com.tradingplatform.app.security.isLocalNetwork
import com.tradingplatform.app.security.sealLanBody
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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

        /** Cadence nominale du polling `/status` (CLAUDE.md §8 — ne pas modifier). */
        internal const val POLL_INTERVAL_MS = 2_000L

        /**
         * Attente après un HTTP 429 : les deux firmwares limitent à 10 requêtes/60 s par IP
         * (fenêtre glissante, `/pin` compris), sans `Retry-After`. Continuer à 2 s (30 req/min) ne
         * fait que rester bloqué ; une place se libère au plus tard en 60 s.
         */
        internal const val RATE_LIMITED_BACKOFF_MS = 10_000L

        /**
         * Nombre de `status: "unknown"` consécutifs (= le `session_id` demandé n'est pas celui de
         * l'état du device) au-delà duquel le pairing est déclaré échoué. Un `unknown` isolé est
         * toléré : sur 93fdc58 (serveur multi-thread) il peut précéder la publication de
         * `pairing` par `/pin` ou venir d'un code saisi sur le HAT qui écrase l'état.
         */
        internal const val UNKNOWN_SESSION_LIMIT = 3

        private const val STATUS_UNKNOWN_SESSION = "unknown"
        private const val HTTP_TOO_MANY_REQUESTS = 429
    }

    /**
     * Envoie le PIN de session à la Radxa via HTTP LAN (payload chiffré libsodium, TTL 120s).
     *
     * Règles critiques (CLAUDE.md §8) :
     * - Valide que l'IP est RFC-1918 avant tout appel réseau (anti-DNS-rebinding)
     * - Le session_pin et le local_token ne sont JAMAIS loggés — [REDACTED] uniquement
     * - Connexion HTTPS (cert auto-signé Radxa validé par [com.tradingplatform.app.security.LanTrustManager],
     *   pas le certificate pinning Root CA du VPS) — `di/NetworkModule.kt` `lanOnlyHttpsGuard()`
     *   refuse toute cible non-HTTPS ou non-RFC-1918 avant même l'ouverture de la socket
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
    ): Result<Unit> = runCatchingCancellable {
        Timber.tag(TAG).d("PairingRepository: sending encrypted PIN to $deviceIp:$devicePort sessionId=$sessionId pin=[REDACTED] token=[REDACTED] nonce=[REDACTED]")

        val payloadJson = JSONObject().apply {
            put("session_id", sessionId)
            put("session_pin", sessionPin)
            put("local_token", localToken)
            put("nonce", nonce)
        }.toString()

        val body = sealedBoxHelper.sealLanBody(
            deviceIp = deviceIp,
            radxaWgPubkeyBase64 = radxaWgPubkey,
            payload = payloadJson.toByteArray(Charsets.UTF_8),
        )

        val url = "https://$deviceIp:$devicePort/pin"
        val response = pairingApi.sendPin(url, body)
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string()?.takeIf { it.isNotBlank() } ?: ""
            throw PairingDeviceException(httpCode = response.code(), body = errorBody)
        }
    }

    /**
     * Poll le statut de pairing toutes les 2 secondes jusqu'à PAIRED ou FAILED.
     *
     * Règles critiques (CLAUDE.md §8) :
     * - Valide que l'IP est RFC-1918 avant chaque appel
     * - Délai de 2s imposé — ne pas modifier (économie batterie + charge Radxa) ; seule exception :
     *   un HTTP 429 (limite 10 req/60 s par IP de la Radxa) attend [RATE_LIMITED_BACKOFF_MS]
     * - La boucle s'arrête dès PAIRED ou FAILED (usage unique du session_pin)
     * - `status: "unknown"` (session_id différent) : toléré [UNKNOWN_SESSION_LIMIT] - 1 fois de suite,
     *   puis FAILED — sans quoi l'app attendait les 120 s complètes une session qui n'existe plus
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

        val url = "https://$deviceIp:$devicePort/status?session_id=$sessionId"

        var unknownStreak = 0
        while (true) {
            var rateLimited = false
            val status = runCatchingCancellable {
                val response = pairingApi.getStatus(url)
                if (response.isSuccessful) {
                    val statusStr = response.body()?.get("status")?.toString() ?: "failed"
                    if (statusStr.equals(STATUS_UNKNOWN_SESSION, ignoreCase = true)) unknownStreak++ else unknownStreak = 0
                    if (unknownStreak >= UNKNOWN_SESSION_LIMIT) {
                        Timber.tag(TAG).w("PairingRepository: session unknown to the device $unknownStreak times in a row — failing")
                        PairingStatus.FAILED
                    } else {
                        PairingStatus.fromString(statusStr)
                    }
                } else {
                    Timber.tag(TAG).w("PairingRepository: poll status HTTP ${response.code()}")
                    rateLimited = response.code() == HTTP_TOO_MANY_REQUESTS
                    PairingStatus.PENDING
                }
            }.getOrElse { e ->
                Timber.tag(TAG).e(e, "PairingRepository: poll status network error")
                PairingStatus.PENDING
            }

            emit(status)

            // Terminer le flow dès qu'on a un état terminal
            if (status == PairingStatus.PAIRED || status == PairingStatus.FAILED) break

            delay(if (rateLimited) RATE_LIMITED_BACKOFF_MS else POLL_INTERVAL_MS)
        }
    }
}
