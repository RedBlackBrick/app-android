package com.tradingplatform.app.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import timber.log.Timber
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * État de la clé Keystore temporisée, vu avant d'afficher le prompt biométrique.
 * - [Valid] : la clé est utilisable (auth forte il y a moins de 300 s).
 * - [Expired] : la fenêtre d'auth est expirée (`UserNotAuthenticatedException`) — cas nominal
 *   après 5 min, un prompt BIOMETRIC_STRONG la ré-arme.
 * - [Invalidated] : la clé est définitivement invalidée (`KeyPermanentlyInvalidatedException`,
 *   biométrie supprimée / ré-enrôlée) — il faut la régénérer et forcer une ré-authentification.
 */
enum class KeyState { Valid, Expired, Invalidated }

@Singleton
class KeystoreManager @Inject constructor() {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "trading_platform_main_key"
        private const val AUTH_VALIDITY_SECONDS = 300
    }

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    /**
     * Génère (ou régénère) la clé AES-256-GCM dans le Keystore Android.
     * Requiert une authentification biométrique forte dans les 5 dernières minutes.
     */
    fun generateKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                AUTH_VALIDITY_SECONDS,
                KeyProperties.AUTH_BIOMETRIC_STRONG,
            )
        } else {
            // API 28-29 : seule API disponible (dépréciée à partir de 30).
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS)
        }
        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }

    /**
     * Récupère la clé depuis le Keystore.
     * Retourne null si la clé n'existe pas encore.
     */
    fun getKey(): SecretKey? {
        if (!keyStore.containsAlias(KEY_ALIAS)) return null
        return (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    /**
     * Régénère la clé (appelé quand KeyPermanentlyInvalidatedException est levé).
     * Supprime l'ancienne clé et en génère une nouvelle.
     */
    fun regenerateKey(): SecretKey {
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS)
        }
        return generateKey()
    }

    /**
     * Initialise un Cipher pour chiffrement.
     * @throws KeyPermanentlyInvalidatedException si la clé a été invalidée — appelant doit régénérer
     * @throws UserNotAuthenticatedException si la fenêtre d'auth de 300 s est expirée
     */
    @Throws(KeyPermanentlyInvalidatedException::class, UserNotAuthenticatedException::class)
    fun initCipher(): Cipher {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = getKey() ?: generateKey()
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher
    }

    /**
     * Sonde l'état de la clé sans jamais lever d'exception (appelé avant le prompt biométrique).
     * Toute erreur inattendue (Keystore indisponible, etc.) est traitée comme [KeyState.Expired] :
     * on affiche le prompt, ce qui reste fail-closed (le verrou n'est levé que sur succès).
     */
    fun checkAuthValidity(): KeyState = try {
        initCipher()
        KeyState.Valid
    } catch (e: KeyPermanentlyInvalidatedException) {
        KeyState.Invalidated
    } catch (e: UserNotAuthenticatedException) {
        KeyState.Expired
    } catch (e: Exception) {
        Timber.w(e, "KeystoreManager: unexpected error while checking key validity — treating as expired")
        KeyState.Expired
    }
}
