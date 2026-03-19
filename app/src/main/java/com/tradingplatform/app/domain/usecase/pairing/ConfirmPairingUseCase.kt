package com.tradingplatform.app.domain.usecase.pairing

import com.tradingplatform.app.domain.exception.PairingTimeoutException
import com.tradingplatform.app.domain.model.PairingStatus
import com.tradingplatform.app.domain.repository.PairingRepository
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import javax.inject.Inject

class ConfirmPairingUseCase @Inject constructor(
    private val repository: PairingRepository,
) {
    /**
     * Poll le statut de pairing jusqu'à PAIRED ou FAILED, ou timeout 120s.
     * Intervalle de polling : 2s — imposé par l'architecture (§8 CLAUDE.md), ne pas modifier.
     * Le Flow pollStatus() doit émettre avec delay(2_000) entre chaque appel côté Repository.
     * Retourne Result.failure(PairingTimeoutException) si le timeout 120s est atteint.
     */
    suspend operator fun invoke(
        deviceIp: String,
        devicePort: Int,
        sessionId: String,
    ): Result<PairingStatus> = try {
        Result.success(
            withTimeout(120_000L) {
                repository.pollStatus(deviceIp, devicePort, sessionId)
                    .first { status ->
                        Timber.d("ConfirmPairing: sessionId=$sessionId status=$status")
                        status == PairingStatus.PAIRED || status == PairingStatus.FAILED
                    }
            }
        )
    } catch (e: TimeoutCancellationException) {
        Timber.w("ConfirmPairing: session timeout after 120s — sessionId=$sessionId")
        Result.failure(PairingTimeoutException())
    } catch (e: Exception) {
        Result.failure(e)
    }
}
