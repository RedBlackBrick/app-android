package com.tradingplatform.app.security

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.biometric.BiometricManager as AndroidBiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BiometricManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keystoreManager: KeystoreManager,
) {

    /**
     * Vérifie si la biométrie est disponible et configurée sur le device.
     */
    fun isAvailable(): Boolean {
        val manager = AndroidBiometricManager.from(context)
        val result = manager.canAuthenticate(
            AndroidBiometricManager.Authenticators.BIOMETRIC_STRONG
        )
        return result == AndroidBiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Affiche le prompt biométrique.
     * @param activity FragmentActivity nécessaire pour BiometricPrompt
     * @param title Titre affiché dans le prompt
     * @param subtitle Sous-titre affiché dans le prompt
     * @param onSuccess Callback appelé en cas de succès d'authentification
     * @param onFailure Callback appelé en cas d'échec ou d'annulation
     * @param onKeyInvalidated Callback appelé si la clé Keystore a été invalidée (biométrie supprimée)
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String = "Déverrouiller Trading Platform",
        subtitle: String = "Utilisez votre biométrie pour continuer",
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit = {},
        onKeyInvalidated: () -> Unit = {},
    ) {
        val executor = ContextCompat.getMainExecutor(context)

        val authCallback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                Timber.d("Biometric auth error $errorCode: $errString")
                onFailure(errString.toString())
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                Timber.d("Biometric auth failed (wrong fingerprint/face)")
                // Ne pas appeler onFailure ici — l'utilisateur peut réessayer
            }
        }

        val biometricPrompt = BiometricPrompt(activity, executor, authCallback)

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(AndroidBiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Annuler")
            .build()

        // Vérifier si la clé Keystore est encore valide avant de lancer le prompt
        try {
            keystoreManager.initCipher()
        } catch (e: KeyPermanentlyInvalidatedException) {
            Timber.w("Keystore key permanently invalidated (biometric removed) — regenerating")
            keystoreManager.regenerateKey()
            onKeyInvalidated()
            return
        }

        biometricPrompt.authenticate(promptInfo)
    }
}
