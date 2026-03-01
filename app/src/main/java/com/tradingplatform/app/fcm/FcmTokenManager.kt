package com.tradingplatform.app.fcm

import com.google.firebase.messaging.FirebaseMessaging
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gestionnaire du token FCM.
 *
 * Responsabilités :
 * - Récupérer le token FCM courant via Firebase
 * - Fournir ce token aux composants qui en ont besoin (ex : envoi au VPS si requis)
 *
 * Le token FCM n'est jamais loggé en clair — [REDACTED] systématiquement.
 *
 * Utilisation future : si le VPS a besoin du token pour cibler des notifications,
 * injecter AuthApi ici et envoyer le token après le login réussi.
 */
@Singleton
class FcmTokenManager @Inject constructor() {

    /**
     * Récupère le token FCM courant de manière asynchrone.
     *
     * @param onToken callback appelé avec le token si la récupération réussit
     */
    fun getToken(onToken: (String) -> Unit) {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Timber.w("FCM token fetch échoué : ${task.exception}")
                return@addOnCompleteListener
            }
            val token = task.result
            Timber.d("FCM token obtenu : [REDACTED]")
            onToken(token)
        }
    }
}
