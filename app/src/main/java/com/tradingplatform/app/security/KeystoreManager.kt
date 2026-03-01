package com.tradingplatform.app.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.KeyPermanentlyInvalidatedException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

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
     * Requiert une authentification biométrique valide dans les 5 dernières minutes.
     */
    fun generateKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS)
            .build()
        keyGenerator.init(spec)
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
     * Gère KeyPermanentlyInvalidatedException (biométrie supprimée → clé invalidée).
     * @throws KeyPermanentlyInvalidatedException si la clé a été invalidée — appelant doit régénérer
     */
    @Throws(KeyPermanentlyInvalidatedException::class)
    fun initCipher(): Cipher {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = getKey() ?: generateKey()
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher
    }
}
