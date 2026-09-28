package com.tradingplatform.app.components

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tradingplatform.app.security.BiometricManager
import com.tradingplatform.app.ui.components.BiometricLockOverlay
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Audit finding #1 (CRITIQUE) — the biometric lock must fail closed.
 *
 * `createComposeRule()` hosts the content in a plain `ComponentActivity` (not a
 * `FragmentActivity`), so `BiometricPrompt` cannot be shown. The overlay must then stay
 * displayed and locked, surface an error, and never call `onAuthSuccess`.
 * (Before the fix, `triggerBiometricAuth` did `context as? FragmentActivity ?: onSuccess()`
 * and unlocked itself from its `LaunchedEffect`.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BiometricLockOverlayTest {

    @get:Rule
    val composeRule = createComposeRule()

    private companion object {
        const val LOCKED_TITLE = "Trading Platform est verrouillé"
        const val UNAVAILABLE = "Authentification indisponible sur cet écran"
    }

    @Test
    fun `locked overlay in a non-Fragment host does not unlock without authentication`() {
        var unlocked = false
        val biometricManager = mockk<BiometricManager>(relaxed = true)

        composeRule.setContent {
            MaterialTheme {
                BiometricLockOverlay(
                    isLocked = true,
                    onAuthSuccess = { unlocked = true },
                    biometricManager = biometricManager,
                )
            }
        }
        composeRule.waitForIdle()

        assertFalse("overlay unlocked itself without biometric authentication", unlocked)
        composeRule.onNodeWithText(LOCKED_TITLE).assertIsDisplayed()
        // No FragmentActivity in the Context chain → the prompt is never requested.
        verify(exactly = 0) { biometricManager.authenticate(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `locked overlay without a BiometricManager does not unlock`() {
        var unlocked = false

        composeRule.setContent {
            MaterialTheme {
                BiometricLockOverlay(
                    isLocked = true,
                    onAuthSuccess = { unlocked = true },
                    biometricManager = null,
                )
            }
        }
        composeRule.waitForIdle()

        assertFalse("overlay unlocked itself with no BiometricManager", unlocked)
        composeRule.onNodeWithText(LOCKED_TITLE).assertIsDisplayed()
    }

    @Test
    fun `unavailable authentication shows an error and retry keeps the overlay locked`() {
        var unlocked = false

        composeRule.setContent {
            MaterialTheme {
                BiometricLockOverlay(
                    isLocked = true,
                    onAuthSuccess = { unlocked = true },
                    biometricManager = null,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(UNAVAILABLE).assertIsDisplayed()

        composeRule.onNodeWithText("Déverrouiller").performClick()
        composeRule.waitForIdle()

        assertFalse("retry unlocked the overlay without authentication", unlocked)
        composeRule.onNodeWithText(LOCKED_TITLE).assertIsDisplayed()
        composeRule.onNodeWithText(UNAVAILABLE).assertIsDisplayed()
    }

    @Test
    fun `inspection mode renders the static overlay without triggering authentication`() {
        var unlocked = false
        val biometricManager = mockk<BiometricManager>(relaxed = true)

        composeRule.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                MaterialTheme {
                    BiometricLockOverlay(
                        isLocked = true,
                        onAuthSuccess = { unlocked = true },
                        biometricManager = biometricManager,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        assertFalse(unlocked)
        composeRule.onNodeWithText(LOCKED_TITLE).assertIsDisplayed()
        // No auth LaunchedEffect in previews → no error surfaced, no prompt requested.
        composeRule.onNodeWithText(UNAVAILABLE).assertDoesNotExist()
        verify(exactly = 0) { biometricManager.authenticate(any(), any(), any(), any(), any(), any()) }
    }

    // ── authEnabled = false (dialog de corruption Keystore affiché — PR 1.7) ──

    @Test
    fun `auth disabled keeps the overlay locked without triggering the biometric prompt`() {
        var unlocked = false
        val biometricManager = mockk<BiometricManager>(relaxed = true)

        composeRule.setContent {
            MaterialTheme {
                BiometricLockOverlay(
                    isLocked = true,
                    onAuthSuccess = { unlocked = true },
                    biometricManager = biometricManager,
                    authEnabled = false,
                )
            }
        }
        composeRule.waitForIdle()

        assertFalse(unlocked)
        composeRule.onNodeWithText(LOCKED_TITLE).assertIsDisplayed()
        // triggerBiometricAuth never ran: in this non-Fragment host it would have surfaced
        // UNAVAILABLE immediately.
        composeRule.onNodeWithText(UNAVAILABLE).assertDoesNotExist()
        composeRule.onNodeWithText("Déverrouiller").assertIsNotEnabled()
        composeRule.onNodeWithText("Se reconnecter").assertDoesNotExist()
        verify(exactly = 0) { biometricManager.authenticate(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `re-enabling auth triggers the prompt attempt`() {
        var unlocked = false
        val authEnabled = mutableStateOf(false)

        composeRule.setContent {
            MaterialTheme {
                BiometricLockOverlay(
                    isLocked = true,
                    onAuthSuccess = { unlocked = true },
                    biometricManager = null,
                    authEnabled = authEnabled.value,
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(UNAVAILABLE).assertDoesNotExist()

        authEnabled.value = true
        composeRule.waitForIdle()

        // The auto-prompt LaunchedEffect ran (fail-closed: unavailable → error, still locked).
        composeRule.onNodeWithText(UNAVAILABLE).assertIsDisplayed()
        composeRule.onNodeWithText(LOCKED_TITLE).assertIsDisplayed()
        assertFalse(unlocked)
    }
}
