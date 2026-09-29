package com.tradingplatform.app.domain.repository

import com.tradingplatform.app.domain.model.InboxNotification

/**
 * Boîte de réception serveur (`/v1/notifications`). Lecture + marquage « lu » uniquement : les
 * deux POST sont non destructifs et idempotents (marquer deux fois = 204 les deux fois), donc ils
 * passent par le client Retrofit normal (retry OkHttp acceptable).
 */
interface InboxRepository {
    /** Les [limit] (1..200) notifications les plus récentes, hors enfants de digest. */
    suspend fun list(limit: Int = 50): Result<List<InboxNotification>>

    /** Nombre de notifications non lues (même périmètre que [list]). */
    suspend fun unreadCount(): Result<Int>

    /** Marque une notification comme lue (204 sans corps). 404 si elle n'existe pas / n'est pas la nôtre. */
    suspend fun markRead(id: String): Result<Unit>

    /** Marque toutes les notifications de l'utilisateur comme lues (204 sans corps). */
    suspend fun markAllRead(): Result<Unit>
}
