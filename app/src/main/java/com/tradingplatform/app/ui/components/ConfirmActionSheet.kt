package com.tradingplatform.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tradingplatform.app.security.BiometricManager
import com.tradingplatform.app.ui.theme.IconSize
import com.tradingplatform.app.ui.theme.Spacing
import com.tradingplatform.app.ui.theme.asNumeric
import com.tradingplatform.app.ui.theme.asNumericIfNumber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Description d'une action d'écriture à confirmer (voir `docs/write-actions.md`).
 *
 * @param title titre de la feuille (ex. « Annuler l'ordre ? »).
 * @param summaryLines récapitulatif « libellé → valeur » de ce qui va être demandé.
 * @param confirmLabel libellé de l'action, repris sur le bouton final et comme titre du prompt
 *   biométrique (ex. « Annuler l'ordre »).
 * @param destructive bouton final en couleur `error`.
 * @param requireReason étape 1 : champ de motif obligatoire (non vide, non blanc).
 * @param reasonLabel libellé du champ de motif.
 * @param message texte d'explication affiché sous le titre (étape récapitulatif), en corps de texte.
 */
data class ConfirmAction(
    val title: String,
    val summaryLines: List<Pair<String, String>>,
    val confirmLabel: String,
    val destructive: Boolean = false,
    val requireReason: Boolean = false,
    val reasonLabel: String = "Motif",
    /** Explication en texte courant (effets, avertissements) : les phrases longues n'ont rien à faire dans une ligne « libellé → valeur ». */
    val message: String? = null,
) {
    companion object {
        /** Borne du backend pour un motif (kill switch : `reason` de 1 à 500 caractères). */
        const val MAX_REASON_LENGTH = 500
    }
}

// ── Logique pure (testée en JVM : ConfirmActionSheetLogicTest) ────────────────────────────────

/** Étape de la feuille : (1) récapitulatif (+ motif), (2) confirmation biométrique. */
internal enum class ConfirmStep { SUMMARY, BIOMETRIC }

/** Motif acceptable : toujours vrai s'il n'est pas exigé, sinon non vide et non blanc. */
internal fun isReasonValid(reason: String, requireReason: Boolean): Boolean =
    !requireReason || reason.isNotBlank()

/**
 * Le bouton principal de l'étape [step] est-il actif ?
 * - [ConfirmStep.SUMMARY] : motif valide (si exigé).
 * - [ConfirmStep.BIOMETRIC] : motif valide ET aucun prompt déjà en cours ([busy] — anti double tap).
 */
internal fun canProceed(
    step: ConfirmStep,
    reason: String,
    requireReason: Boolean,
    busy: Boolean = false,
): Boolean = when (step) {
    ConfirmStep.SUMMARY -> isReasonValid(reason, requireReason)
    ConfirmStep.BIOMETRIC -> isReasonValid(reason, requireReason) && !busy
}

/** Saisie du motif bornée à [ConfirmAction.MAX_REASON_LENGTH] caractères. */
internal fun limitReasonInput(input: String): String = input.take(ConfirmAction.MAX_REASON_LENGTH)

/** Motif transmis à `onConfirmed` : nettoyé (`trim`) si exigé, sinon `null`. */
internal fun reasonToSubmit(reason: String, requireReason: Boolean): String? =
    if (requireReason) reason.trim() else null

internal fun stepIndicatorLabel(step: ConfirmStep): String = when (step) {
    ConfirmStep.SUMMARY -> "Étape 1 sur 2 · Récapitulatif"
    ConfirmStep.BIOMETRIC -> "Étape 2 sur 2 · Confirmation"
}

/** Étape 1 : « Continuer » (neutre) ; étape 2 : le libellé de l'action. */
internal fun primaryButtonLabel(step: ConfirmStep, confirmLabel: String): String = when (step) {
    ConfirmStep.SUMMARY -> "Continuer"
    ConfirmStep.BIOMETRIC -> confirmLabel
}

internal fun primaryButtonDescription(step: ConfirmStep, confirmLabel: String): String = when (step) {
    ConfirmStep.SUMMARY -> "Continuer vers la confirmation"
    ConfirmStep.BIOMETRIC -> "$confirmLabel — confirmer avec l'empreinte"
}

internal fun secondaryButtonLabel(step: ConfirmStep): String = when (step) {
    ConfirmStep.SUMMARY -> "Annuler"
    ConfirmStep.BIOMETRIC -> "Retour"
}

internal fun reasonCounterLabel(reason: String): String =
    "Obligatoire · ${reason.length}/${ConfirmAction.MAX_REASON_LENGTH}"

/** Lignes affichées : à l'étape 2 le motif saisi est rappelé en dernière ligne. */
internal fun displayedSummaryLines(
    action: ConfirmAction,
    step: ConfirmStep,
    reason: String,
): List<Pair<String, String>> =
    if (action.requireReason && step == ConfirmStep.BIOMETRIC) {
        action.summaryLines + (action.reasonLabel to reason.trim())
    } else {
        action.summaryLines
    }

internal const val CONFIRM_BIOMETRIC_HINT =
    "Une authentification par empreinte est requise. La demande est envoyée au serveur, " +
        "puis son état est relu pour confirmation."

/** Tags de test (Compose). */
internal object ConfirmActionTestTags {
    const val REASON = "confirm_action_reason"
    const val PRIMARY = "confirm_action_primary"
    const val SECONDARY = "confirm_action_secondary"
    const val ERROR = "confirm_action_error"
}

private val MinTouchTarget = 48.dp

// ── Composables ───────────────────────────────────────────────────────────────────────────────

/**
 * Feuille modale de confirmation d'une action d'écriture, en deux étapes :
 * 1. récapitulatif de ce qui sera demandé (+ champ de motif si [ConfirmAction.requireReason]) ;
 * 2. bouton final → **`BiometricPrompt` à chaque action** (jamais mis en cache, BIOMETRIC_STRONG).
 *
 * **Fail-closed** : [onConfirmed] n'est appelé QUE depuis le callback de succès du prompt. Sans
 * `FragmentActivity` dans la chaîne de Context, sans biométrie forte, sur erreur ou annulation, un
 * message d'erreur s'affiche et la feuille reste ouverte (retour ou fermeture manuels) ; aucun
 * contournement par mot de passe. Un succès tardif, après fermeture ou remplacement de la feuille,
 * est ignoré.
 *
 * Le parent doit mettre [action] à `null` (ou lancer l'écriture puis fermer) dans [onConfirmed] :
 * la feuille affiche « en cours » mais ne rappelle jamais [onConfirmed] pour la même instance.
 * Le motif est transmis nettoyé (`trim`) ; `null` si aucun motif n'est demandé.
 *
 * [action] == null → rien n'est affiché. En `LocalInspectionMode`, rendu statique (sans feuille
 * modale ni prompt). [biometricManager] : `null` (défaut) = résolu via Hilt ([BiometricConfirmEntryPoint]).
 *
 * ```kotlin
 * var pending by remember { mutableStateOf<ConfirmAction?>(null) }
 * ConfirmActionSheet(
 *     action = pending,
 *     onDismiss = { pending = null },
 *     onConfirmed = { reason -> pending = null; viewModel.cancelOrder(orderId) },
 * )
 * ```
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmActionSheet(
    action: ConfirmAction?,
    onDismiss: () -> Unit,
    onConfirmed: (reason: String?) -> Unit,
    modifier: Modifier = Modifier,
    biometricManager: BiometricManager? = null,
) {
    if (action != null) {
        val manager = rememberBiometricManager(biometricManager)
        if (LocalInspectionMode.current) {
            Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
                ConfirmActionContent(
                    action = action,
                    biometricManager = manager,
                    onDismiss = onDismiss,
                    onConfirmed = onConfirmed,
                )
            }
        } else {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = onDismiss,
                sheetState = sheetState,
                modifier = modifier,
            ) {
                ConfirmActionContent(
                    action = action,
                    biometricManager = manager,
                    onDismiss = onDismiss,
                    onConfirmed = onConfirmed,
                )
            }
        }
    }
}

/** Contenu de la feuille (sans le conteneur modal) — interne pour être testable seul. */
@Composable
internal fun ConfirmActionContent(
    action: ConfirmAction,
    biometricManager: BiometricManager?,
    onDismiss: () -> Unit,
    onConfirmed: (reason: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val inspectionMode = LocalInspectionMode.current
    val focusManager = LocalFocusManager.current
    val currentOnConfirmed by rememberUpdatedState(onConfirmed)

    var step by remember(action) { mutableStateOf(ConfirmStep.SUMMARY) }
    var reason by rememberSaveable(action) { mutableStateOf("") }
    var busy by remember(action) { mutableStateOf(false) }
    var submitted by remember(action) { mutableStateOf(false) }
    var errorMessage by remember(action) { mutableStateOf<String?>(null) }

    // La feuille quitte la composition (fermée, ou `action` remplacée) : un succès biométrique
    // tardif ne doit plus déclencher l'écriture.
    val alive = remember(action) { AtomicBoolean(true) }
    DisposableEffect(action) {
        onDispose { alive.set(false) }
    }

    val enabled = canProceed(step, reason, action.requireReason, busy) && !submitted
    val showProgress = busy || submitted
    val destructiveFinalStep = action.destructive && step == ConfirmStep.BIOMETRIC

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(
            text = stepIndicatorLabel(step),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = action.title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )

        if (step == ConfirmStep.SUMMARY && !action.message.isNullOrBlank()) {
            Text(
                text = action.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ConfirmSummary(lines = displayedSummaryLines(action, step, reason))

        if (action.requireReason && step == ConfirmStep.SUMMARY) {
            OutlinedTextField(
                value = reason,
                onValueChange = {
                    reason = limitReasonInput(it)
                    errorMessage = null
                },
                label = { Text(action.reasonLabel) },
                supportingText = { Text(reasonCounterLabel(reason)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ConfirmActionTestTags.REASON)
                    .semantics { contentDescription = "${action.reasonLabel}, obligatoire" },
            )
        }

        if (step == ConfirmStep.BIOMETRIC) {
            Text(
                text = CONFIRM_BIOMETRIC_HINT,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ConfirmActionTestTags.ERROR)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = "Erreur : $message"
                    },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = {
                    if (step == ConfirmStep.BIOMETRIC) {
                        errorMessage = null
                        step = ConfirmStep.SUMMARY
                    } else {
                        onDismiss()
                    }
                },
                enabled = !showProgress,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = MinTouchTarget)
                    .testTag(ConfirmActionTestTags.SECONDARY),
            ) {
                Text(
                    text = secondaryButtonLabel(step),
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            Button(
                onClick = {
                    if (!inspectionMode && enabled) {
                        when (step) {
                            ConfirmStep.SUMMARY -> {
                                focusManager.clearFocus()
                                errorMessage = null
                                step = ConfirmStep.BIOMETRIC
                            }
                            ConfirmStep.BIOMETRIC -> {
                                errorMessage = null
                                busy = true
                                requestBiometricConfirmation(
                                    activity = context.findFragmentActivity(),
                                    biometricManager = biometricManager,
                                    title = action.confirmLabel,
                                    onConfirmed = {
                                        if (alive.get() && !submitted) {
                                            submitted = true
                                            busy = false
                                            currentOnConfirmed(reasonToSubmit(reason, action.requireReason))
                                        }
                                    },
                                    onError = { message ->
                                        if (alive.get()) {
                                            busy = false
                                            errorMessage = message
                                        }
                                    },
                                )
                            }
                        }
                    }
                },
                enabled = enabled,
                colors = if (destructiveFinalStep) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = MinTouchTarget)
                    .testTag(ConfirmActionTestTags.PRIMARY)
                    .semantics { contentDescription = primaryButtonDescription(step, action.confirmLabel) },
            ) {
                if (showProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(IconSize.sm),
                        color = LocalContentColor.current,
                    )
                    Spacer(modifier = Modifier.width(Spacing.sm))
                } else if (step == ConfirmStep.BIOMETRIC) {
                    Icon(
                        imageVector = Icons.Filled.Fingerprint,
                        contentDescription = null,
                        modifier = Modifier.size(IconSize.sm),
                    )
                    Spacer(modifier = Modifier.width(Spacing.sm))
                }
                Text(
                    text = primaryButtonLabel(step, action.confirmLabel),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/** Récapitulatif « libellé → valeur » dans une [TradingCard] ; rien si la liste est vide. */
@Composable
private fun ConfirmSummary(
    lines: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    if (lines.isEmpty()) return
    TradingCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            lines.forEach { (label, value) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics(mergeDescendants = true) {
                            contentDescription = "$label : $value"
                        },
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium.asNumericIfNumber(value)
                            .copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
