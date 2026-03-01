package com.tradingplatform.app.vpn

/**
 * Exception levée quand une opération réseau est tentée sans tunnel VPN actif.
 *
 * Étend Exception (PAS IOException) — permet un catch distinct dans WidgetUpdateWorker
 * sans déclencher le mécanisme de retry WorkManager (qui est réservé aux IOException réseau).
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
) : Exception(message)
