package com.tradingplatform.app.ui.screens.totp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tradingplatform.app.ui.components.ErrorBanner
import com.tradingplatform.app.ui.components.LoadingOverlay
import com.tradingplatform.app.ui.theme.Spacing

private const val TOTP_CODE_LENGTH = 6

/**
 * Ecran de vérification 2FA (TOTP).
 *
 * Le sessionToken est lu depuis [SessionManager] via [TotpViewModel] — il n'est
 * jamais transmis via les routes de navigation (sécurité backstack).
 *
 * @param onNavigateToDashboard Appelé quand la vérification réussit et le portfolio est chargé.
 * @param onNavigateBack Appelé quand l'utilisateur appuie sur "Retour" (optionnel).
 */
@Composable
fun TotpScreen(
    onNavigateToDashboard: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateBack: (() -> Unit)? = null,
    viewModel: TotpViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Naviguer vers le Dashboard une seule fois à la réussite
    LaunchedEffect(uiState) {
        if (uiState is TotpUiState.Success) {
            onNavigateToDashboard()
        }
    }

    TotpScreenContent(
        uiState = uiState,
        onVerifyClick = { code -> viewModel.verify(code) },
        onDismissError = { viewModel.resetError() },
        onNavigateBack = onNavigateBack,
        modifier = modifier,
    )
}

@Composable
private fun TotpScreenContent(
    uiState: TotpUiState,
    onVerifyClick: (code: String) -> Unit,
    onDismissError: () -> Unit,
    onNavigateBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var code by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    val isLoading = uiState is TotpUiState.Verifying
    val errorState = uiState as? TotpUiState.Error

    Box(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.xl),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Bouton retour (si disponible)
            if (onNavigateBack != null) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.align(Alignment.TopStart),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Retour à l'écran de connexion",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(Spacing.lg))
            }

            // Titre
            Text(
                text = "Authentification à deux facteurs",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Spacer(modifier = Modifier.height(Spacing.sm))

            Text(
                text = "Entrez votre code à $TOTP_CODE_LENGTH chiffres",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(Spacing.xxxl))

            // Champ code TOTP
            OutlinedTextField(
                value = code,
                onValueChange = { input ->
                    // Filtrer pour n'accepter que les chiffres, max 6 caractères
                    val filtered = input.filter { it.isDigit() }.take(TOTP_CODE_LENGTH)
                    code = filtered
                },
                label = { Text("Code à $TOTP_CODE_LENGTH chiffres") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        if (code.length == TOTP_CODE_LENGTH) {
                            onVerifyClick(code)
                        }
                    },
                ),
                enabled = !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Champ code TOTP à $TOTP_CODE_LENGTH chiffres" },
            )

            Spacer(modifier = Modifier.height(Spacing.xl))

            // Bouton vérifier
            Button(
                onClick = {
                    focusManager.clearFocus()
                    onVerifyClick(code)
                },
                enabled = !isLoading && code.length == TOTP_CODE_LENGTH,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Bouton Vérifier le code" },
            ) {
                Text(
                    text = "Vérifier",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        // Bannière d'erreur ancrée en bas
        if (errorState != null) {
            ErrorBanner(
                message = errorState.message,
                onDismiss = onDismissError,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(Spacing.lg),
            )
        }

        // Overlay de chargement — bloque les interactions
        if (isLoading) {
            LoadingOverlay()
        }
    }
}
