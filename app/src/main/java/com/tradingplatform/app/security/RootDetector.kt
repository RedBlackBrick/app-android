package com.tradingplatform.app.security

import android.content.Context
import com.scottyab.rootbeer.RootBeer
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RootDetector @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Détecte si le device est rooté via RootBeer.
     * Note : bypassable avec Magisk/Zygisk — couche de défense en profondeur uniquement.
     * Pour une attestation serveur, combiner avec Play Integrity API (Play Store uniquement).
     */
    fun isRooted(): Boolean {
        return try {
            RootBeer(context).isRooted
        } catch (e: Exception) {
            Timber.e(e, "RootBeer detection failed — assuming not rooted")
            false
        }
    }
}
