package com.tradingplatform.app.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import com.tradingplatform.app.security.BiometricManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import timber.log.Timber

/*
 * Confirmation biométrique d'une action d'écriture (utilisée par [ConfirmActionSheet]).
 *
 * Contrairement au verrou de l'app (`BiometricLockOverlay`), aucune fenêtre de validité n'est
 * réutilisée : **chaque action déclenche un nouveau `BiometricPrompt`** (BIOMETRIC_STRONG seul, via
 * [BiometricManager.authenticate]). Aucune crypto ici : c'est une ré-authentification, pas une clé.
 *
 * **Fail-closed** : le callback de succès n'est atteint QUE par `onAuthenticationSucceeded` du
 * prompt. Pas de [FragmentActivity] dans la chaîne de Context, pas de [BiometricManager], pas de
 * biométrie forte enrôlée, clé Keystore invalidée, erreur ou annulation → seul `onError` est appelé.
 * Il n'existe aucun contournement par code PIN / schéma / mot de passe.
 */

/** Sous-titre du prompt (le titre est le libellé de l'action, ex. « Annuler l'ordre »). */
internal const val BIOMETRIC_CONFIRM_SUBTITLE = "Confirmez avec votre empreinte"

/** Message quand le système ne fournit aucun texte d'erreur exploitable. */
internal const val BIOMETRIC_CONFIRM_FALLBACK_ERROR = "Confirmation annulée ou échouée — réessayez."

/** Clé Keystore invalidée (biométrie modifiée) : le prompt n'est pas affiché, rien n'est confirmé. */
internal const val BIOMETRIC_CONFIRM_KEY_INVALIDATED =
    "La biométrie de l'appareil a changé : réessayez, ou reconnectez-vous à l'application."

/**
 * Point d'accès Hilt au [BiometricManager] pour un composable qui n'a pas de ViewModel dédié
 * (même approche que `WidgetEntryPoint` / `FcmEntryPoint`, `@Singleton` fourni par `SecurityModule`).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface BiometricConfirmEntryPoint {
    fun biometricManager(): BiometricManager
}

/**
 * Résout le [BiometricManager] singleton, ou `null` si le graphe Hilt est indisponible (test
 * Compose sans `Application` Hilt, aperçu). `null` → l'appelant reste fail-closed.
 */
internal fun resolveBiometricManager(context: Context): BiometricManager? =
    try {
        EntryPointAccessors
            .fromApplication(context.applicationContext, BiometricConfirmEntryPoint::class.java)
            .biometricManager()
    } catch (e: Exception) {
        Timber.w(e, "BiometricConfirm: BiometricManager indisponible")
        null
    }

/** [provided] (tests instrumentés, écrans qui possèdent déjà le manager) sinon l'entry point Hilt. */
@Composable
internal fun rememberBiometricManager(provided: BiometricManager?): BiometricManager? {
    val context = LocalContext.current
    return remember(context, provided) { provided ?: resolveBiometricManager(context) }
}

/**
 * Texte d'erreur affichable : le message du système s'il existe, sinon
 * [BIOMETRIC_CONFIRM_FALLBACK_ERROR].
 */
internal fun biometricErrorMessage(raw: String?): String =
    raw?.trim()?.takeIf { it.isNotEmpty() } ?: BIOMETRIC_CONFIRM_FALLBACK_ERROR

/**
 * Demande une authentification biométrique forte pour confirmer une action.
 *
 * - [onConfirmed] : appelé au plus une fois, UNIQUEMENT depuis le succès du prompt.
 * - [onError] : message FR à afficher ; jamais appelé après [onConfirmed] (et inversement).
 *
 * [activity] null ou [biometricManager] null → [BIOMETRIC_UNAVAILABLE_MESSAGE], aucun prompt.
 * [BiometricManager.authenticate] recevant `onKeyInvalidated` (clé Keystore invalidée) : traité
 * comme une erreur, sans logout (le logout forcé reste réservé au verrou de l'app).
 */
internal fun requestBiometricConfirmation(
    activity: FragmentActivity?,
    biometricManager: BiometricManager?,
    title: String,
    subtitle: String = BIOMETRIC_CONFIRM_SUBTITLE,
    onConfirmed: () -> Unit,
    onError: (String) -> Unit,
) {
    if (activity == null || biometricManager == null) {
        onError(BIOMETRIC_UNAVAILABLE_MESSAGE)
        return
    }
    var settled = false
    fun succeed() {
        if (settled) return
        settled = true
        onConfirmed()
    }
    fun fail(message: String) {
        if (settled) return
        settled = true
        onError(message)
    }
    try {
        biometricManager.authenticate(
            activity = activity,
            title = title,
            subtitle = subtitle,
            onSuccess = { succeed() },
            onFailure = { raw -> fail(biometricErrorMessage(raw)) },
            onKeyInvalidated = { fail(BIOMETRIC_CONFIRM_KEY_INVALIDATED) },
        )
    } catch (e: Exception) {
        // Une exception d'un de NOS callbacks (déjà réglés) ne doit pas être avalée.
        if (settled) throw e
        // Ex. IllegalStateException du BiometricPrompt (FragmentManager déjà sauvegardé).
        Timber.w(e, "BiometricConfirm: prompt impossible à afficher")
        fail(BIOMETRIC_UNAVAILABLE_MESSAGE)
    }
}
