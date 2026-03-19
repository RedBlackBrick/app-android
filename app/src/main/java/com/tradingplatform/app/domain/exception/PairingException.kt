package com.tradingplatform.app.domain.exception

/**
 * La session de pairing a expiré (timeout 120s côté VPS) avant que le device
 * confirme l'état PAIRED. L'utilisateur doit relancer le pairing depuis le VPS.
 */
class PairingTimeoutException(
    message: String = "Session expirée — relancez le pairing depuis le VPS",
) : Exception(message)

/**
 * Le device Radxa a retourné une réponse non-2xx lors de l'envoi du PIN.
 * [httpCode] est le code HTTP retourné. [body] est le body de la réponse (peut être vide).
 */
class PairingDeviceException(
    val httpCode: Int,
    val body: String,
    message: String = "sendPin failed: HTTP $httpCode — $body",
) : Exception(message)
