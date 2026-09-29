package com.tradingplatform.app.domain.model

import java.time.Instant

/**
 * Notification serveur (boîte de réception) lue via `GET /v1/notifications`.
 *
 * Distincte de [Alert] (historique local alimenté par FCM, table Room `alerts`) : celle-ci vit
 * côté serveur et porte l'état « lu » partagé avec le web.
 *
 * - [type] : chaîne LIBRE (le backend n'a pas d'enum fermé : types historiques, types produits par
 *   les streams…). Tout type inconnu doit être traité comme générique par l'UI.
 * - [title] : nullable par prudence (le backend le déclare obligatoire).
 * - [body] : `""` quand le serveur ne l'envoie pas.
 * - [createdAt] : [Instant.EPOCH] quand le serveur n'envoie pas de date (cas théorique) — l'UI doit
 *   alors masquer l'horodatage plutôt que d'afficher 1970.
 */
data class InboxNotification(
    val id: String,
    val type: String,
    val title: String?,
    val body: String,
    val read: Boolean,
    val createdAt: Instant,
)
