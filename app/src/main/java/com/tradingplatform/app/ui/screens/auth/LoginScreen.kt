package com.tradingplatform.app.ui.screens.auth

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tradingplatform.app.ui.components.ErrorBanner
import com.tradingplatform.app.ui.components.LoadingOverlay
import com.tradingplatform.app.ui.components.LoginBackdrop
import com.tradingplatform.app.ui.screens.settings.VpnConsentUiState
import com.tradingplatform.app.ui.screens.setup.launchVpnConsent
import com.tradingplatform.app.ui.theme.Spacing
import com.tradingplatform.app.vpn.VpnState
import kotlinx.coroutines.delay

/**
 * Ecran de connexion.
 *
 * @param onNavigateToDashboard Appelé quand le login est réussi (sans TOTP).
 * @param onNavigateToTotp Appelé quand le 2FA est requis.
 *   Le sessionToken est déjà stocké dans [SessionManager] par [LoginViewModel].
 */
@Composable
fun LoginScreen(
    onNavigateToDashboard: () -> Unit,
    onNavigateToTotp: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    val hasVpnConfig by viewModel.hasVpnConfig.collectAsStateWithLifecycle()
    val consentState by viewModel.consentState.collectAsStateWithLifecycle()

    // Dialogue de consentement VPN d'Android (`VpnService.prepare`), comme VpnSettingsScreen :
    // RESULT_OK = accordé. Lancé une seule fois (`Launched` vit dans le ViewModel).
    val vpnConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.onVpnConsentResult(granted = result.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(consentState) {
        if (consentState == VpnConsentUiState.Required) {
            launchVpnConsent(
                intent = viewModel.vpnConsentIntent(),
                launch = { vpnConsentLauncher.launch(it) },
                onLaunched = viewModel::onVpnConsentLaunched,
                onResult = viewModel::onVpnConsentResult,
            )
        }
    }

    // Réagir aux états de navigation — une seule fois par transition
    LaunchedEffect(uiState) {
        when (uiState) {
            is LoginUiState.Success -> {
                viewModel.resetState()
                onNavigateToDashboard()
            }
            is LoginUiState.TotpRequired -> {
                viewModel.resetState()
                onNavigateToTotp()
            }
            else -> Unit
        }
    }

    LoginScreenContent(
        uiState = uiState,
        vpnState = vpnState,
        hasVpnConfig = hasVpnConfig,
        consentDenied = consentState == VpnConsentUiState.Denied,
        onLoginClick = { email, password -> viewModel.login(email, password) },
        onDismissError = { viewModel.resetState() },
        onActivateVpn = viewModel::onActivateVpn,
        modifier = modifier,
    )
}

@Composable
private fun LoginScreenContent(
    uiState: LoginUiState,
    vpnState: VpnState,
    hasVpnConfig: Boolean,
    consentDenied: Boolean,
    onLoginClick: (email: String, password: String) -> Unit,
    onDismissError: () -> Unit,
    onActivateVpn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    val isLoading = uiState is LoginUiState.Loading
    val errorState = uiState as? LoginUiState.Error
    val vpnBanner = loginVpnBanner(
        state = vpnState,
        hasVpnConfig = hasVpnConfig,
        consentDenied = consentDenied,
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding(),
    ) {
        // Décor : courbe de cours qui se trace lentement, très discrète, derrière le formulaire.
        LoginBackdrop(modifier = Modifier.fillMaxSize())

        // Défilable (bandeau VPN + clavier + police agrandie) mais centré quand tout tient :
        // hauteur minimale = hauteur disponible.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl, vertical = Spacing.lg),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Titre
            Text(
                text = "Trading Platform",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Spacer(modifier = Modifier.height(Spacing.sm))

            Text(
                text = "Connectez-vous à votre compte",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(Spacing.lg))

            // État du tunnel VPN — informe sans bloquer : « Se connecter » reste actif.
            LoginVpnBanner(
                model = vpnBanner,
                onActivate = onActivateVpn,
            )

            Spacer(modifier = Modifier.height(Spacing.xl))

            // Champ email
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Adresse e-mail *") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                ),
                enabled = !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Champ adresse e-mail" },
            )

            Spacer(modifier = Modifier.height(Spacing.lg))

            // Champ mot de passe
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Mot de passe *") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        if (email.isNotBlank() && password.isNotEmpty()) {
                            onLoginClick(email.trim(), password)
                        }
                    },
                ),
                enabled = !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Champ mot de passe" },
            )

            Spacer(modifier = Modifier.height(Spacing.xl))

            // Bouton de connexion
            Button(
                onClick = {
                    focusManager.clearFocus()
                    onLoginClick(email.trim(), password)
                },
                enabled = !isLoading && email.isNotBlank() && password.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Bouton Se connecter" },
            ) {
                Text(
                    text = "Se connecter",
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            Spacer(modifier = Modifier.height(Spacing.sm))

            Text(
                text = "* Champs obligatoires",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Start),
            )

            // Countdown verrouillage compte (429 / AUTH_1008)
            if (errorState?.retryAfterSeconds != null) {
                Spacer(modifier = Modifier.height(Spacing.lg))
                CountdownText(
                    initialSeconds = errorState.retryAfterSeconds,
                    modifier = Modifier.semantics {
                        contentDescription = "Délai avant réessai : ${errorState.retryAfterSeconds} secondes"
                    },
                )
            }
        }

        // Bannière d'erreur ancrée en bas
        if (errorState != null && errorState.retryAfterSeconds == null) {
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

/**
 * Affiche un compte à rebours décroissant à partir de [initialSeconds].
 * Réaffiche le message de verrouillage pendant toute la durée du délai.
 */
@Composable
private fun CountdownText(
    initialSeconds: Int,
    modifier: Modifier = Modifier,
) {
    var remaining by remember(initialSeconds) { mutableIntStateOf(initialSeconds) }

    LaunchedEffect(initialSeconds) {
        while (remaining > 0) {
            delay(1_000L)
            remaining--
        }
    }

    if (remaining > 0) {
        Text(
            text = "Compte verrouillé. Réessayez dans $remaining secondes.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = modifier,
        )
    }
}
