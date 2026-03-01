package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.api.PairingLanApi
import com.tradingplatform.app.domain.model.PairingStatus
import com.tradingplatform.app.domain.repository.PairingRepository
import com.tradingplatform.app.security.isLocalNetwork
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class PairingRepositoryImpl @Inject constructor(
    @Named("lan") private val pairingApi: PairingLanApi,
) : PairingRepository {

    /**
     * Envoie le PIN de session à la Radxa via HTTP LAN (non chiffré — LAN uniquement, TTL 120s).
     *
     * Règles critiques (CLAUDE.md §8) :
     * - Valide que l'IP est RFC-1918 avant tout appel réseau (anti-DNS-rebinding)
     * - Le session_pin n'est JAMAIS loggé — [REDACTED] uniquement
     * - Connexion HTTP uniquement (pas de certificate pinning — LAN local)
     */
    override suspend fun sendPin(
        deviceIp: String,
        devicePort: Int,
        sessionId: String,
        sessionPin: String,
    ): Result<Unit> = runCatching {
        if (!isLocalNetwork(deviceIp)) {
            error("Refused: $deviceIp is not a local network address (RFC-1918 required)")
        }

        Timber.d("PairingRepository: sending PIN to $deviceIp:$devicePort sessionId=$sessionId pin=[REDACTED]")

        val url = "http://$deviceIp:$devicePort/pin"
        val body = mapOf(
            "session_id" to sessionId,
            "session_pin" to sessionPin,
        )

        val response = pairingApi.sendPin(url, body)
        if (!response.isSuccessful) {
            error("sendPin failed: HTTP ${response.code()}")
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
            Timber.e("PairingRepository: pollStatus refused — $deviceIp is not RFC-1918")
            emit(PairingStatus.FAILED)
            return@flow
        }

        val url = "http://$deviceIp:$devicePort/status?session_id=$sessionId"

        while (true) {
            val status = runCatching {
                val response = pairingApi.getStatus(url)
                if (response.isSuccessful) {
                    val statusStr = response.body()?.get("status") ?: "failed"
                    PairingStatus.fromString(statusStr)
                } else {
                    Timber.w("PairingRepository: poll status HTTP ${response.code()}")
                    PairingStatus.PENDING
                }
            }.getOrElse { e ->
                Timber.e(e, "PairingRepository: poll status network error")
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
