package com.tradingplatform.app.ui.components

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
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.tradingplatform.app.security.BiometricManager
import com.tradingplatform.app.ui.theme.IconSize
import com.tradingplatform.app.ui.theme.Spacing
import kotlinx.coroutines.delay

private const val ESCAPE_HATCH_DELAY_MS = 60_000L

/**
 * Overlay opaque affiché lors du verrou biométrique.
 *
 * Quand [isLocked] == true :
 * - Overlay opaque sur tout l'écran (les données de trading ne sont plus visibles)
 * - Icône cadenas + bouton "Déverrouiller" qui déclenche [BiometricManager.authenticate]
 * - Transition douce via [AnimatedVisibility]
 *
 * Quand [isLocked] == false : overlay invisible, contenu accessible normalement.
 *
 * [WireGuardVpnService] reste actif pendant le verrou (service foreground indépendant).
 *
 * Usage dans MainActivity :
 * ```kotlin
 * BiometricLockOverlay(
 *     isLocked = isLocked,
 *     onAuthSuccess = { isLocked = false },
 * )
 * ```
 */
@Composable
fun BiometricLockOverlay(
    isLocked: Boolean,
    onAuthSuccess: () -> Unit,
    modifier: Modifier = Modifier,
    onKeyInvalidated: () -> Unit = {},
    biometricManager: BiometricManager? = null,
) {
    AnimatedVisibility(
        visible = isLocked,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        val context = LocalContext.current
        var authError by remember { mutableStateOf<String?>(null) }
        var showEscapeHatch by remember { mutableStateOf(false) }

        // Lancer automatiquement le prompt biométrique dès que l'overlay devient visible
        LaunchedEffect(Unit) {
            triggerBiometricAuth(
                context = context,
                biometricManager = biometricManager,
                onSuccess = onAuthSuccess,
                onError = { authError = it },
                onKeyInvalidated = onKeyInvalidated,
            )
        }

        // Escape hatch — if the user is still stuck on the lock overlay after 60 s
        // (biometric hardware failure, prompt never appears, etc.), surface a
        // "Se reconnecter" button that forces a logout via onKeyInvalidated.
        LaunchedEffect(Unit) {
            delay(ESCAPE_HATCH_DELAY_MS)
            showEscapeHatch = true
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
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

                if (authError != null) {
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    Text(
                        text = authError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.xl))

                Button(
                    onClick = {
                        authError = null
                        triggerBiometricAuth(
                            context = context,
                            biometricManager = biometricManager,
                            onSuccess = onAuthSuccess,
                            onError = { authError = it },
                            onKeyInvalidated = onKeyInvalidated,
                        )
                    },
                ) {
                    Text("Déverrouiller")
                }

                if (showEscapeHatch) {
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
 * Déclenche le prompt biométrique via [BiometricManager] si disponible.
 *
 * Si [biometricManager] est null (contexte hors activité ou en preview),
 * appelle directement [onSuccess] comme fallback.
 */
private fun triggerBiometricAuth(
    context: android.content.Context,
    biometricManager: BiometricManager?,
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
    onKeyInvalidated: () -> Unit,
) {
    val activity = context as? FragmentActivity ?: run {
        // Hors FragmentActivity (preview, test) : fallback sans auth
        onSuccess()
        return
    }
    if (biometricManager == null) {
        onSuccess()
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
