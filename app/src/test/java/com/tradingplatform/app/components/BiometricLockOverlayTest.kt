package com.tradingplatform.app.components

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.tradingplatform.app.security.BiometricManager
import com.tradingplatform.app.ui.components.BiometricLockOverlay
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Audit finding #1 (CRITIQUE) — the biometric lock must fail closed.
 *
 * `createComposeRule()` hosts the content in a plain `ComponentActivity`, exactly like the
 * production `MainActivity : ComponentActivity()`. On current code
 * `triggerBiometricAuth` does `context as? FragmentActivity ?: run { onSuccess(); return }`,
 * so the overlay unlocks itself from its `LaunchedEffect` without any authentication.
 *
 * Expected RED on current code (both tests): `onAuthSuccess` is invoked immediately.
 * After the fix (fallback calls onError, MainActivity : FragmentActivity), a non-Fragment
 * host or a missing BiometricManager must leave the overlay displayed and locked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BiometricLockOverlayTest {

    @get:Rule
    val composeRule = createComposeRule()

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
        composeRule.onNodeWithText("Trading Platform est verrouillé").assertIsDisplayed()
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
        composeRule.onNodeWithText("Trading Platform est verrouillé").assertIsDisplayed()
    }
}
