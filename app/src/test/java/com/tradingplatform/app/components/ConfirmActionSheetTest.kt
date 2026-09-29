package com.tradingplatform.app.components

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.tradingplatform.app.security.BiometricManager
import com.tradingplatform.app.ui.components.ConfirmAction
import com.tradingplatform.app.ui.components.ConfirmActionContent
import com.tradingplatform.app.ui.components.ConfirmActionTestTags
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Contenu de la feuille de confirmation, hébergé dans une `ComponentActivity` simple (pas de
 * `FragmentActivity`) : le `BiometricPrompt` ne peut pas s'afficher → la feuille doit rester en
 * erreur et ne JAMAIS confirmer (fail-closed).
 *
 * Les lignes de récapitulatif sont vides à dessein : elles utiliseraient la police mono (ressource
 * de police) inutile ici.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ConfirmActionSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun action(requireReason: Boolean) = ConfirmAction(
        title = "Activer l'arrêt d'urgence ?",
        summaryLines = emptyList(),
        confirmLabel = "Activer l'arrêt",
        destructive = true,
        requireReason = requireReason,
    )

    @Test
    fun `continue stays disabled until a non-blank reason is typed`() {
        composeRule.setContent {
            MaterialTheme {
                ConfirmActionContent(
                    action = action(requireReason = true),
                    biometricManager = null,
                    onDismiss = {},
                    onConfirmed = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(ConfirmActionTestTags.PRIMARY).assertIsNotEnabled()

        composeRule.onNodeWithTag(ConfirmActionTestTags.REASON).performTextInput("   ")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ConfirmActionTestTags.PRIMARY).assertIsNotEnabled()

        composeRule.onNodeWithTag(ConfirmActionTestTags.REASON).performTextInput("Arrêt manuel")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ConfirmActionTestTags.PRIMARY).assertIsEnabled()
    }

    @Test
    fun `in a non-Fragment host the final step shows an error and never confirms`() {
        var confirmations = 0
        val biometricManager = mockk<BiometricManager>(relaxed = true)

        composeRule.setContent {
            MaterialTheme {
                ConfirmActionContent(
                    action = action(requireReason = false),
                    biometricManager = biometricManager,
                    onDismiss = {},
                    onConfirmed = { confirmations++ },
                )
            }
        }
        composeRule.waitForIdle()

        // Étape 1 → étape 2.
        composeRule.onNodeWithText("Étape 1 sur 2 · Récapitulatif").assertIsDisplayed()
        composeRule.onNodeWithTag(ConfirmActionTestTags.PRIMARY).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Étape 2 sur 2 · Confirmation").assertIsDisplayed()

        // Étape 2 : pas de FragmentActivity → erreur, aucune confirmation, aucun prompt.
        composeRule.onNodeWithTag(ConfirmActionTestTags.PRIMARY).performClick()
        composeRule.waitForIdle()

        assertEquals("nothing may be confirmed without a successful prompt", 0, confirmations)
        composeRule.onNodeWithText("Authentification indisponible sur cet écran").assertIsDisplayed()
        composeRule.onNodeWithText("Étape 2 sur 2 · Confirmation").assertIsDisplayed()
        verify(exactly = 0) { biometricManager.authenticate(any(), any(), any(), any(), any(), any()) }

        // Réessayer ne confirme toujours pas.
        composeRule.onNodeWithTag(ConfirmActionTestTags.PRIMARY).performClick()
        composeRule.waitForIdle()
        assertEquals(0, confirmations)
    }

    @Test
    fun `back returns to the summary step and cancel dismisses the sheet`() {
        var dismissed = false

        composeRule.setContent {
            MaterialTheme {
                ConfirmActionContent(
                    action = action(requireReason = false),
                    biometricManager = null,
                    onDismiss = { dismissed = true },
                    onConfirmed = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(ConfirmActionTestTags.PRIMARY).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Étape 2 sur 2 · Confirmation").assertIsDisplayed()

        composeRule.onNodeWithTag(ConfirmActionTestTags.SECONDARY).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Étape 1 sur 2 · Récapitulatif").assertIsDisplayed()
        assertFalse("« Retour » must not dismiss the sheet", dismissed)

        composeRule.onNodeWithTag(ConfirmActionTestTags.SECONDARY).performClick()
        composeRule.waitForIdle()
        assertEquals(true, dismissed)
    }
}
