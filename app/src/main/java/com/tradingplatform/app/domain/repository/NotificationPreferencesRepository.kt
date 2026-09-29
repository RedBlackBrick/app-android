package com.tradingplatform.app.domain.repository

import com.tradingplatform.app.domain.model.NotifCategory
import com.tradingplatform.app.domain.model.NotificationPreferences
import com.tradingplatform.app.domain.model.WriteOutcome

/** Préférences de notification du compte (`/v1/auth/preferences`, clé `ui.notifications`). */
interface NotificationPreferencesRepository {

    suspend fun get(): Result<NotificationPreferences>

    /**
     * Active / coupe le canal PUSH d'une catégorie. Seul changement possible depuis le mobile
     * (in-app, e-mail, heures calmes et seuils = web).
     *
     * **Read-modify-write** : le backend fusionne `ui` de façon SUPERFICIELLE (chaque clé de premier
     * niveau envoyée remplace l'objet stocké). L'implémentation relit donc les préférences, ne
     * change que le booléen `push` de [category], puis renvoie `ui.notifications` ET `ui.appearance`
     * COMPLETS (toute clé inconnue est conservée telle quelle). L'écriture n'est jamais rejouée :
     *
     * - 2xx → `success(CONFIRMED)` ;
     * - 5xx ou timeout / IOException après envoi → `success(REQUESTED_UNCONFIRMED)` (l'appelant relit
     *   avec [get]) ;
     * - échec de la relecture, VPN absent, 4xx → `failure`.
     */
    suspend fun setPushEnabled(category: NotifCategory, enabled: Boolean): Result<WriteOutcome>
}
