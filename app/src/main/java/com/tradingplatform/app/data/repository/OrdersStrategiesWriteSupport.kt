package com.tradingplatform.app.data.repository

import com.tradingplatform.app.domain.exception.HttpStatusException
import com.tradingplatform.app.domain.model.WriteOutcome
import com.tradingplatform.app.domain.util.runCatchingCancellable
import com.tradingplatform.app.vpn.VpnNotConnectedException
import retrofit2.Response
import timber.log.Timber
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Traduction commune des écritures « ordres » et « liens stratégie » (annuler un ordre, pause /
 * reprise d'un lien) en `Result<WriteOutcome>`. Une écriture n'est JAMAIS rejouée : [execute]
 * n'appelle son `call` qu'une seule fois, et l'API appelée est construite sur le Retrofit
 * `@Named("write")` (`retryOnConnectionFailure(false)`).
 *
 * Issues :
 * - 2xx → `success(CONFIRMED)` (le corps de la réponse est ignoré) ;
 * - 409 → `failure(HttpStatusException(409, endpoint, conflictMessage))` ;
 * - 5xx → `success(REQUESTED_UNCONFIRMED)` : plusieurs erreurs applicatives (ordre inconnu,
 *   conflit concurrent, exception broker…) sont converties en 500 par le backend (contrat §9.4),
 *   donc un 500 ne prouve pas que l'écriture a échoué — l'appelant relit l'état ;
 * - timeout / IOException APRÈS l'envoi → `success(REQUESTED_UNCONFIRMED)` (même raison) ;
 * - échec certain AVANT l'envoi (VPN absent, DNS, connexion refusée, handshake TLS / pinning) →
 *   `failure` avec l'exception d'origine ;
 * - autres 4xx (400, 401, 403, 404, 422, 429…) → `failure(HttpStatusException(code, endpoint))`.
 *
 * Aucun identifiant d'ordre, de portefeuille ni de stratégie n'est loggé.
 */
internal object OrdersStrategiesWriteSupport {

    private const val HTTP_CONFLICT = 409
    private const val HTTP_SERVER_ERROR = 500
    private const val MAX_CAUSE_DEPTH = 8

    /**
     * @param endpoint gabarit du chemin (avec `{placeholders}`, jamais les vrais identifiants),
     *   utilisé dans [HttpStatusException] et les logs.
     * @param conflictMessage message explicite affiché sur un 409.
     * @param call l'appel Retrofit d'écriture, exécuté exactement une fois.
     */
    suspend fun execute(
        endpoint: String,
        conflictMessage: String,
        call: suspend () -> Response<Unit>,
    ): Result<WriteOutcome> = runCatchingCancellable {
        val response: Response<Unit>? = try {
            call()
        } catch (e: IOException) {
            val notSent = notSentCause(e)
            if (notSent != null) throw notSent
            Timber.w("Write %s: no response after send (%s) — outcome unconfirmed", endpoint, e.javaClass.simpleName)
            null
        }
        when {
            response == null -> WriteOutcome.REQUESTED_UNCONFIRMED
            response.isSuccessful -> WriteOutcome.CONFIRMED
            response.code() == HTTP_CONFLICT ->
                throw HttpStatusException(HTTP_CONFLICT, endpoint, conflictMessage)
            response.code() >= HTTP_SERVER_ERROR -> {
                Timber.w("Write %s: HTTP %d — outcome unconfirmed", endpoint, response.code())
                WriteOutcome.REQUESTED_UNCONFIRMED
            }
            else -> throw HttpStatusException(response.code(), endpoint)
        }
    }

    /**
     * Retourne la cause à propager en échec quand la requête n'a certainement PAS été envoyée,
     * `null` quand on ne peut pas l'exclure (timeout de lecture, connexion coupée, reset…).
     *
     * `VpnNotConnectedException` (levée par `VpnRequiredInterceptor`, ce n'est pas une IOException)
     * peut arriver directement, ou enveloppée par OkHttp dans une IOException « canceled due to … »
     * en cause / suppressed : on la retrouve pour que l'appelant l'identifie.
     */
    private fun notSentCause(e: IOException): Throwable? {
        if (e is UnknownHostException || e is ConnectException ||
            e is SSLHandshakeException || e is SSLPeerUnverifiedException
        ) {
            return e
        }
        return findVpnBlock(e)
    }

    private fun findVpnBlock(root: Throwable): VpnNotConnectedException? {
        var current: Throwable? = root
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (current is VpnNotConnectedException) return current
            for (suppressed in current.suppressedExceptions) {
                if (suppressed is VpnNotConnectedException) return suppressed
            }
            current = current.cause
            depth++
        }
        return null
    }
}
