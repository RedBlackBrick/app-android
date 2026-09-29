package com.tradingplatform.app.domain.model

/**
 * Issue d'une écriture (action non destructive côté mobile : annuler un ordre, mettre en pause un
 * lien stratégie, activer un kill switch portefeuille, changer une préférence).
 *
 * - [CONFIRMED] : le serveur a répondu 2xx.
 * - [REQUESTED_UNCONFIRMED] : la requête est partie mais la réponse est inexploitable (5xx / timeout
 *   après envoi). Le backend convertit plusieurs erreurs applicatives en 500 (cf. contrat §9.4) :
 *   l'UI dit « demandé », relit l'état, et ne rejoue JAMAIS l'écriture.
 */
enum class WriteOutcome { CONFIRMED, REQUESTED_UNCONFIRMED }
