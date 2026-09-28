package com.tradingplatform.app.ui.components

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.fragment.app.FragmentActivity
import com.tradingplatform.app.security.BiometricManager
import com.tradingplatform.app.ui.theme.IconSize
import com.tradingplatform.app.ui.theme.Spacing
import kotlinx.coroutines.delay

private const val ESCAPE_HATCH_DELAY_MS = 60_000L

/** Message affiché quand le prompt ne peut pas être présenté (hôte non-Fragment, pas de manager). */
internal const val BIOMETRIC_UNAVAILABLE_MESSAGE = "Authentification indisponible sur cet écran"

/**
 * Overlay opaque affiché lors du verrou biométrique.
 *
 * Quand [isLocked] == true :
 * - Overlay opaque sur tout l'écran (les données de trading ne sont plus visibles), qui
 *   intercepte les touches et le bouton retour (rien n'atteint le contenu masqué)
 * - Icône cadenas + bouton "Déverrouiller" qui déclenche [BiometricManager.authenticate]
 * - Transition douce via [AnimatedVisibility]
 *
 * Quand [isLocked] == false : overlay invisible, contenu accessible normalement.
 *
 * **Fail-closed** : [onAuthSuccess] n'est appelé QUE par le callback de succès du
 * BiometricPrompt. Si le prompt ne peut pas être affiché (pas de [FragmentActivity] dans la
 * chaîne de Context, [biometricManager] null), l'overlay reste affiché avec un message
 * d'erreur. La seule autre sortie est le bouton "Se reconnecter" (après 60 s) qui appelle
 * [onKeyInvalidated] → logout forcé.
 *
 * En `LocalInspectionMode` (@Preview), l'UI est rendue statiquement sans lancer le prompt.
 *
 * [authEnabled] == false (ex. dialog de corruption Keystore affiché) : l'overlay reste affiché
 * et opaque, mais ni le prompt automatique ni le timer de l'escape hatch ne démarrent et le
 * bouton "Déverrouiller" est désactivé — un autre flux (dialog) détient l'interaction. Le
 * repasser à true déclenche le prompt comme à l'apparition de l'overlay.
 *
 * [WireGuardVpnService] reste actif pendant le verrou (service foreground indépendant).
 */
@Composable
fun BiometricLockOverlay(
    isLocked: Boolean,
    onAuthSuccess: () -> Unit,
    modifier: Modifier = Modifier,
    onKeyInvalidated: () -> Unit = {},
    biometricManager: BiometricManager? = null,
    authEnabled: Boolean = true,
) {
    AnimatedVisibility(
        visible = isLocked,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        val context = LocalContext.current
        val inspectionMode = LocalInspectionMode.current
        var authError by remember { mutableStateOf<String?>(null) }
        var showEscapeHatch by remember { mutableStateOf(false) }

        if (!inspectionMode) {
            // Le retour arrière ne doit ni fermer l'app vers un état ambigu ni naviguer sous
            // l'overlay. Composé après le NavHost → prioritaire sur ses callbacks.
            BackHandler(enabled = true) {}

            // Lancer automatiquement le prompt biométrique dès que l'overlay devient visible
            // (ou dès que l'authentification redevient possible — authEnabled false → true).
            LaunchedEffect(authEnabled) {
                if (authEnabled) {
                    triggerBiometricAuth(
                        context = context,
                        biometricManager = biometricManager,
                        onSuccess = onAuthSuccess,
                        onError = { authError = it },
                        onKeyInvalidated = onKeyInvalidated,
                    )
                }
            }

            // Escape hatch — if the user is still stuck on the lock overlay after 60 s
            // (biometric hardware failure, prompt never appears, etc.), surface a
            // "Se reconnecter" button that forces a logout via onKeyInvalidated.
            // Pas de timer tant que l'authentification est suspendue (authEnabled == false).
            LaunchedEffect(authEnabled) {
                if (authEnabled) {
                    delay(ESCAPE_HATCH_DELAY_MS)
                    showEscapeHatch = true
                } else {
                    showEscapeHatch = false
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                // Bloque les touches vers le contenu masqué : un Box avec seulement un background
                // n'est pas « touché » au hit-test et laisse passer les taps vers le NavHost
                // (frère en dessous). Un pointerInput suffit à arrêter le hit-test des frères ;
                // on ne consomme PAS les événements, sinon les boutons enfants (Déverrouiller)
                // verraient leurs taps annulés au moindre ACTION_MOVE.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent()
                        }
                    }
                }
                .semantics { contentDescription = "Écran verrouillé. Authentification requise." },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(IconSize.xl),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(Spacing.xl))

                Text(
                    text = "Trading Platform est verrouillé",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(modifier = Modifier.height(Spacing.sm))

                Text(
                    text = "Authentifiez-vous pour continuer",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                authError?.let { error ->
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.xl))

                Button(
                    enabled = authEnabled,
                    onClick = {
                        if (!inspectionMode) {
                            authError = null
                            triggerBiometricAuth(
                                context = context,
                                biometricManager = biometricManager,
                                onSuccess = onAuthSuccess,
                                onError = { authError = it },
                                onKeyInvalidated = onKeyInvalidated,
                            )
                        }
                    },
                ) {
                    Text("Déverrouiller")
                }

                if (showEscapeHatch && authEnabled) {
                    Spacer(modifier = Modifier.height(Spacing.md))
                    OutlinedButton(
                        onClick = onKeyInvalidated,
                        modifier = Modifier.semantics {
                            contentDescription = "Problème biométrique — se reconnecter"
                        },
                    ) {
                        Text("Se reconnecter")
                    }
                }
            }
        }
    }
}

/**
 * Remonte la chaîne de [ContextWrapper] jusqu'à une [FragmentActivity] (LocalContext peut être
 * un ContextThemeWrapper ou autre wrapper autour de l'Activity).
 */
internal fun Context.findFragmentActivity(): FragmentActivity? {
    var current: Context? = this
    while (current != null) {
        if (current is FragmentActivity) return current
        current = (current as? ContextWrapper)?.baseContext
    }
    return null
}

/**
 * Déclenche le prompt biométrique via [BiometricManager].
 *
 * Fail-closed : si aucune [FragmentActivity] n'est trouvée ou si [biometricManager] est null,
 * [onError] est appelé — **jamais** [onSuccess].
 */
private fun triggerBiometricAuth(
    context: Context,
    biometricManager: BiometricManager?,
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
    onKeyInvalidated: () -> Unit,
) {
    val activity = context.findFragmentActivity()
    if (activity == null || biometricManager == null) {
        onError(BIOMETRIC_UNAVAILABLE_MESSAGE)
        return
    }
    biometricManager.authenticate(
        activity = activity,
        onSuccess = onSuccess,
        onFailure = { errorMsg -> onError(errorMsg) },
        onKeyInvalidated = {
            // Clé invalidée (suppression biométrie) — logout forcé vers LoginScreen
            onKeyInvalidated()
        },
    )
}
