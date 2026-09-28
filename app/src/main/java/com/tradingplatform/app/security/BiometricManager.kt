package com.tradingplatform.app.security

import android.content.Context
import androidx.biometric.BiometricManager as AndroidBiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import timber.log.Timber
import java.util.concurrent.Executor

/**
 * Construit le [BiometricPrompt] — injectable pour que les tests JVM puissent observer
 * la demande de prompt sans FragmentManager réel.
 */
fun interface BiometricPromptFactory {
    fun create(
        activity: FragmentActivity,
        executor: Executor,
        callback: BiometricPrompt.AuthenticationCallback,
    ): BiometricPrompt
}

/** Message remonté quand aucune biométrie forte n'est utilisable (pas de prompt affiché). */
const val BIOMETRIC_NOT_AVAILABLE_MESSAGE = "Aucune biométrie forte configurée sur cet appareil"

private fun canAuthenticateStrong(context: Context): Boolean =
    AndroidBiometricManager.from(context).canAuthenticate(
        AndroidBiometricManager.Authenticators.BIOMETRIC_STRONG
    ) == AndroidBiometricManager.BIOMETRIC_SUCCESS

/**
 * Abstraction du prompt biométrique (BIOMETRIC_STRONG uniquement).
 *
 * Fourni par Hilt via [com.tradingplatform.app.di.SecurityModule] (constructeur à 2 arguments).
 * Les paramètres [promptFactory] / [executorProvider] / [strongBiometricAvailable] ne servent
 * qu'aux tests.
 */
class BiometricManager(
    private val context: Context,
    private val keystoreManager: KeystoreManager,
    private val promptFactory: BiometricPromptFactory = BiometricPromptFactory { activity, executor, callback ->
        BiometricPrompt(activity, executor, callback)
    },
    private val executorProvider: (Context) -> Executor = { ctx -> ContextCompat.getMainExecutor(ctx) },
    private val strongBiometricAvailable: (Context) -> Boolean = ::canAuthenticateStrong,
) {

    /**
     * Vérifie si la biométrie est disponible et configurée sur le device.
     */
    fun isAvailable(): Boolean = strongBiometricAvailable(context)

    /**
     * Affiche le prompt biométrique.
     *
     * Pré-check de la clé Keystore temporisée ([KeystoreManager.checkAuthValidity]) :
     * - [KeyState.Invalidated] (biométrie supprimée / ré-enrôlée) → régénère la clé et appelle
     *   [onKeyInvalidated] (logout forcé) **sans** afficher le prompt ;
     * - [KeyState.Expired] (cas nominal après 300 s, `UserNotAuthenticatedException`) ou
     *   [KeyState.Valid] → affiche le prompt. Une auth BIOMETRIC_STRONG réussie ré-arme la
     *   fenêtre de 300 s de la clé (géré par l'OS).
     *
     * @param activity FragmentActivity nécessaire pour BiometricPrompt
     * @param title Titre affiché dans le prompt
     * @param subtitle Sous-titre affiché dans le prompt
     * @param onSuccess Callback appelé en cas de succès d'authentification
     * @param onFailure Callback appelé en cas d'erreur ou d'annulation
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
        when (keystoreManager.checkAuthValidity()) {
            KeyState.Invalidated -> {
                Timber.w("BiometricManager: Keystore key permanently invalidated (biometric removed) — regenerating")
                try {
                    keystoreManager.regenerateKey()
                } catch (e: Exception) {
                    Timber.e(e, "BiometricManager: key regeneration failed")
                }
                onKeyInvalidated()
                return
            }
            KeyState.Expired -> Timber.d("BiometricManager: key auth window expired — prompting")
            KeyState.Valid -> Unit
        }

        // Pas de biométrie forte enrôlée/disponible : ne pas afficher le prompt (sur API 28 sans
        // capteur d'empreinte, androidx.biometric retombe sur son dialog AppCompat, incompatible
        // avec notre thème). Fail-closed : erreur, l'overlay reste (sortie = "Se reconnecter").
        if (!isAvailable()) {
            Timber.w("BiometricManager: no strong biometric available — prompt not shown")
            onFailure(BIOMETRIC_NOT_AVAILABLE_MESSAGE)
            return
        }

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

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            // BIOMETRIC_STRONG seul (pas de DEVICE_CREDENTIAL) → bouton négatif obligatoire.
            .setAllowedAuthenticators(AndroidBiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Annuler")
            .setConfirmationRequired(false)
            .build()

        val biometricPrompt = promptFactory.create(activity, executorProvider(context), authCallback)
        biometricPrompt.authenticate(promptInfo)
    }
}
