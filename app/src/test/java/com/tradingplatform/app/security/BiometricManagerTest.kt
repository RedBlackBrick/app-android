package com.tradingplatform.app.security

import android.app.Application
import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

/**
 * [BiometricManager.authenticate] pré-check Keystore (audit #1 / plan §A.5).
 *
 * - `UserNotAuthenticatedException` (fenêtre de 300 s expirée — cas nominal après le timeout
 *   d'inactivité) ne doit PAS planter l'app : le prompt doit être affiché.
 * - `KeyPermanentlyInvalidatedException` (biométrie supprimée) → régénération de la clé +
 *   `onKeyInvalidated`, sans prompt.
 *
 * Robolectric : les exceptions keystore et `PromptInfo.Builder` (TextUtils) viennent du
 * framework Android. Le [KeystoreManager] est mocké (pas d'AndroidKeyStore en JVM) mais
 * `checkAuthValidity()` exécute son code réel via `callOriginal()` pour valider le mapping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BiometricManagerTest {

    private val keystoreManager = mockk<KeystoreManager>(relaxed = true) {
        every { checkAuthValidity() } answers { callOriginal() }
    }
    private val activity = mockk<FragmentActivity>(relaxed = true)
    private val prompt = mockk<BiometricPrompt>(relaxed = true)
    private lateinit var capturedCallback: BiometricPrompt.AuthenticationCallback
    private var promptRequests = 0
    private var strongBiometricAvailable = true

    private val manager = BiometricManager(
        context = mockk<Context>(relaxed = true),
        keystoreManager = keystoreManager,
        promptFactory = BiometricPromptFactory { _, _, callback ->
            promptRequests++
            capturedCallback = callback
            prompt
        },
        executorProvider = { Executor { it.run() } },
        strongBiometricAvailable = { strongBiometricAvailable },
    )

    @Test
    fun `no strong biometric available reports failure without prompt or success`() {
        every { keystoreManager.initCipher() } throws UserNotAuthenticatedException()
        strongBiometricAvailable = false
        var succeeded = false
        var failure: String? = null

        manager.authenticate(
            activity = activity,
            onSuccess = { succeeded = true },
            onFailure = { failure = it },
        )

        assertFalse(succeeded)
        assertEquals(BIOMETRIC_NOT_AVAILABLE_MESSAGE, failure)
        assertEquals(0, promptRequests)
    }

    @Test
    fun `expired auth window does not crash and shows the prompt`() {
        every { keystoreManager.initCipher() } throws UserNotAuthenticatedException()
        var invalidated = false

        manager.authenticate(
            activity = activity,
            onSuccess = {},
            onKeyInvalidated = { invalidated = true },
        )

        assertEquals(1, promptRequests)
        verify(exactly = 1) { prompt.authenticate(any<BiometricPrompt.PromptInfo>()) }
        verify(exactly = 0) { keystoreManager.regenerateKey() }
        assertFalse(invalidated)
    }

    @Test
    fun `permanently invalidated key regenerates and signals invalidation without prompt`() {
        every { keystoreManager.initCipher() } throws KeyPermanentlyInvalidatedException()
        var invalidated = false
        var succeeded = false

        manager.authenticate(
            activity = activity,
            onSuccess = { succeeded = true },
            onKeyInvalidated = { invalidated = true },
        )

        assertTrue(invalidated)
        assertFalse(succeeded)
        verify(exactly = 1) { keystoreManager.regenerateKey() }
        assertEquals(0, promptRequests)
        verify(exactly = 0) { prompt.authenticate(any<BiometricPrompt.PromptInfo>()) }
    }

    @Test
    fun `unexpected keystore error is treated as expired and still prompts`() {
        every { keystoreManager.initCipher() } throws IllegalStateException("keystore unavailable")

        manager.authenticate(activity = activity, onSuccess = {})

        assertEquals(1, promptRequests)
        verify(exactly = 0) { keystoreManager.regenerateKey() }
    }

    @Test
    fun `success is reported only through the prompt callback`() {
        every { keystoreManager.initCipher() } throws UserNotAuthenticatedException()
        var succeeded = false
        var failure: String? = null

        manager.authenticate(
            activity = activity,
            onSuccess = { succeeded = true },
            onFailure = { failure = it },
        )
        // Rien n'est déverrouillé tant que le prompt n'a pas répondu.
        assertFalse(succeeded)

        capturedCallback.onAuthenticationError(BiometricPrompt.ERROR_NEGATIVE_BUTTON, "Annulé")
        assertFalse(succeeded)
        assertEquals("Annulé", failure)

        capturedCallback.onAuthenticationFailed()
        assertFalse(succeeded)

        capturedCallback.onAuthenticationSucceeded(mockk(relaxed = true))
        assertTrue(succeeded)
    }

    @Test
    fun `checkAuthValidity maps keystore exceptions to key states`() {
        every { keystoreManager.initCipher() } returns mockk(relaxed = true)
        assertEquals(KeyState.Valid, keystoreManager.checkAuthValidity())

        every { keystoreManager.initCipher() } throws UserNotAuthenticatedException()
        assertEquals(KeyState.Expired, keystoreManager.checkAuthValidity())

        every { keystoreManager.initCipher() } throws KeyPermanentlyInvalidatedException()
        assertEquals(KeyState.Invalidated, keystoreManager.checkAuthValidity())

        every { keystoreManager.initCipher() } throws RuntimeException("boom")
        assertEquals(KeyState.Expired, keystoreManager.checkAuthValidity())
    }
}
