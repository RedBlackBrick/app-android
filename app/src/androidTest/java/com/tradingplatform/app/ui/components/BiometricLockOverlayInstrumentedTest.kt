package com.tradingplatform.app.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.fragment.app.FragmentActivity
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tradingplatform.app.security.BiometricManager
import com.tradingplatform.app.security.KeystoreManager
import com.tradingplatform.app.ui.theme.TradingPlatformTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Instrumented (real device/emulator) test for [BiometricLockOverlay] — verifies the
 * fail-closed contract described in CLAUDE.md §4: [onAuthSuccess] must never fire except from
 * a genuine `BiometricPrompt` success callback, and the Android back button must never dismiss
 * the overlay.
 *
 * Uses a bare [FragmentActivity] host (declared in `app/src/androidTest/AndroidManifest.xml`)
 * rather than [com.tradingplatform.app.MainActivity] — no Hilt graph is needed since
 * [BiometricManager] and [KeystoreManager] are constructed directly (both have public,
 * argument-light constructors precisely so they can be built outside of DI in tests).
 *
 * Gradle Managed Devices (api30/api34, `aosp-atd` images — see app/build.gradle.kts) have no
 * biometric enrolled, so [BiometricManager.authenticate] fails fast via
 * `onFailure(BIOMETRIC_NOT_AVAILABLE_MESSAGE)` without ever showing a system prompt. That is
 * exactly the fail-closed path this test pins: the overlay must stay up and
 * [onAuthSuccess] must stay uncalled.
 */
@RunWith(AndroidJUnit4::class)
class BiometricLockOverlayInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<FragmentActivity>()

    private val lockedText = "Trading Platform est verrouillé"

    @Test
    fun lockOverlay_neverCallsOnAuthSuccess_andSurvivesBackPress_withoutRealBiometric() {
        val authSucceeded = AtomicBoolean(false)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val biometricManager = BiometricManager(context, KeystoreManager())

        composeRule.setContent {
            TradingPlatformTheme {
                BiometricLockOverlay(
                    isLocked = true,
                    onAuthSuccess = { authSucceeded.set(true) },
                    biometricManager = biometricManager,
                )
            }
        }

        composeRule.onNodeWithText(lockedText).assertIsDisplayed()

        // Let the auto-triggered LaunchedEffect (triggerBiometricAuth) run its course. On a
        // CI emulator with no biometric enrolled this resolves to onFailure(...) almost
        // immediately; we wait comfortably past that to assert the negative outcome holds.
        Thread.sleep(2_000)

        assert(!authSucceeded.get()) {
            "onAuthSuccess must never fire without a real BiometricPrompt success callback"
        }
        composeRule.onNodeWithText(lockedText).assertIsDisplayed()

        // Back button: BackHandler(enabled = true) {} in BiometricLockOverlay must swallow it —
        // the overlay stays up, nothing navigates underneath it.
        Espresso.pressBack()

        composeRule.onNodeWithText(lockedText).assertIsDisplayed()
        assert(!authSucceeded.get())
    }
}
