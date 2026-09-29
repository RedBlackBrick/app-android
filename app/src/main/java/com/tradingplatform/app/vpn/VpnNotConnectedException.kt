package com.tradingplatform.app.vpn

import java.io.IOException

/**
 * Exception levée quand une opération réseau est tentée sans tunnel VPN actif.
 *
 * Étend [IOException] : elle est levée depuis `VpnRequiredInterceptor`, donc dans la chaîne
 * d'intercepteurs OkHttp. OkHttp ne livre proprement à `onFailure` (donc au `Result.failure` du
 * Repository) que les IOException ; toute autre exception est RELANCÉE sur le thread du
 * dispatcher, ce qui tue le process (crash de l'app dès qu'un appel part sans VPN). Elle reste
 * identifiable par `is VpnNotConnectedException`.
 *
 * Attention à l'ordre des `catch` / `when` : la tester AVANT `IOException`. Le Worker des widgets
 * s'en sert pour ne PAS déclencher de retry WorkManager (absence de VPN = cas prévisible, réservé
 * aux vraies erreurs réseau).
 *
 * Usage dans le ViewModel Dashboard :
 * .onFailure { e ->
 *     when (e) {
 *         is VpnNotConnectedException -> // garder valeur précédente, pas d'erreur bloquante
 *         is SocketTimeoutException -> Unit // transitoire
 *         else -> _uiState.value = Error(e.localizedMessage)
 *     }
 * }
 */
class VpnNotConnectedException(
    message: String = "VPN tunnel not active — request blocked",
) : IOException(message)
