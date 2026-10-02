package com.tradingplatform.app.ui.common

import com.tradingplatform.app.domain.exception.AccountLockedException
import com.tradingplatform.app.domain.exception.HttpStatusException
import com.tradingplatform.app.domain.exception.InvalidCredentialsException
import com.tradingplatform.app.domain.exception.InvalidTotpCodeException
import com.tradingplatform.app.domain.exception.NoPortfolioException
import com.tradingplatform.app.domain.exception.PairingTimeoutException
import com.tradingplatform.app.domain.usecase.pairing.MalformedQrException
import com.tradingplatform.app.domain.usecase.pairing.UnrecognizedQrException
import com.tradingplatform.app.vpn.VpnNotConnectedException
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Message affichable (français, sans jargon) pour une erreur de chargement.
 *
 * Les exceptions métier portent déjà un message français destiné à l'utilisateur : il est
 * conservé tel quel. Tout le reste (HTTP, réseau, exceptions techniques « Get X failed: HTTP 500 »)
 * est traduit ; le détail technique reste dans les logs, jamais à l'écran.
 */
fun Throwable.toUserMessage(fallback: String = GENERIC_ERROR): String = when (this) {
    is VpnNotConnectedException -> "VPN non connecté — activez le tunnel pour continuer"
    is InvalidCredentialsException,
    is AccountLockedException,
    is InvalidTotpCodeException,
    is NoPortfolioException,
    is PairingTimeoutException,
    is UnrecognizedQrException,
    is MalformedQrException,
    -> message?.takeIf { it.isNotBlank() } ?: fallback
    is HttpStatusException -> httpStatusMessage(code) ?: fallback
    is SocketTimeoutException -> "Le serveur met trop de temps à répondre — réessayez"
    is IOException -> "Serveur injoignable — vérifiez le VPN et votre connexion"
    else -> embeddedHttpCode(message)?.let { httpStatusMessage(it) } ?: fallback
}

internal const val GENERIC_ERROR = "Une erreur est survenue — réessayez"

private val HTTP_CODE_IN_MESSAGE = Regex("""HTTP (\d{3})""")

/** Les repositories signalent un échec par `error("… failed: HTTP 500")` : on en retire le code. */
internal fun embeddedHttpCode(message: String?): Int? =
    message?.let { HTTP_CODE_IN_MESSAGE.find(it)?.groupValues?.get(1)?.toIntOrNull() }

internal fun httpStatusMessage(code: Int): String? = when (code) {
    401 -> "Session expirée — reconnectez-vous"
    403 -> "Accès refusé"
    404 -> "Donnée introuvable"
    408 -> "Le serveur met trop de temps à répondre — réessayez"
    429 -> "Trop de requêtes — patientez quelques secondes"
    in 500..599 -> "Le serveur rencontre un problème — réessayez dans un instant"
    else -> null
}
