package com.tradingplatform.app.components

import android.app.Application
import androidx.fragment.app.FragmentActivity
import com.tradingplatform.app.security.BiometricManager
import com.tradingplatform.app.ui.components.biometricErrorMessage
import com.tradingplatform.app.ui.components.requestBiometricConfirmation
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Confirmation biométrique d'une écriture — **fail-closed** : `onConfirmed` n'est atteint que par
 * le callback de succès du prompt, jamais par une absence d'activité/manager, une erreur, une
 * annulation ou une clé invalidée.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BiometricConfirmTest {

    private val activity = mockk<FragmentActivity>(relaxed = true)
    private val manager = mockk<BiometricManager>()

    private var confirmedCount = 0
    private val errors = mutableListOf<String>()

    private var requestedTitle: String? = null
    private var requestedSubtitle: String? = null
    private var successCallback: (() -> Unit)? = null
    private var failureCallback: ((String) -> Unit)? = null
    private var keyInvalidatedCallback: (() -> Unit)? = null

    /** Le mock mémorise les callbacks du prompt pour les déclencher à la main. */
    private fun captureCallbacks() {
        every { manager.authenticate(any(), any(), any(), any(), any(), any()) } answers {
            requestedTitle = arg(1)
            requestedSubtitle = arg(2)
            successCallback = arg(3)
            failureCallback = arg(4)
            keyInvalidatedCallback = arg(5)
        }
    }

    private fun request(
        activity: FragmentActivity? = this.activity,
        manager: BiometricManager? = this.manager,
    ) = requestBiometricConfirmation(
        activity = activity,
        biometricManager = manager,
        title = "Annuler l'ordre",
        onConfirmed = { confirmedCount++ },
        onError = { errors += it },
    )

    @Test
    fun `no FragmentActivity reports unavailable and never shows the prompt nor confirms`() {
        request(activity = null)

        assertEquals(0, confirmedCount)
        assertEquals(listOf("Authentification indisponible sur cet écran"), errors)
        verify(exactly = 0) { manager.authenticate(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `no BiometricManager reports unavailable and never confirms`() {
        request(manager = null)

        assertEquals(0, confirmedCount)
        assertEquals(listOf("Authentification indisponible sur cet écran"), errors)
    }

    @Test
    fun `success callback confirms exactly once and passes the action label as prompt title`() {
        captureCallbacks()

        request()
        assertEquals("Annuler l'ordre", requestedTitle)
        assertEquals("Confirmez avec votre empreinte", requestedSubtitle)
        assertEquals("prompt is shown but nothing is confirmed yet", 0, confirmedCount)

        successCallback!!.invoke()
        successCallback!!.invoke()

        assertEquals(1, confirmedCount)
        assertEquals(emptyList<String>(), errors)
    }

    @Test
    fun `system error or cancellation surfaces its message and never confirms`() {
        captureCallbacks()

        request()
        failureCallback!!.invoke("Trop de tentatives. Réessayez plus tard.")

        assertEquals(0, confirmedCount)
        assertEquals(listOf("Trop de tentatives. Réessayez plus tard."), errors)
    }

    @Test
    fun `blank system error falls back to a readable message`() {
        captureCallbacks()

        request()
        failureCallback!!.invoke("  ")

        assertEquals(0, confirmedCount)
        assertEquals(listOf("Confirmation annulée ou échouée — réessayez."), errors)
    }

    @Test
    fun `invalidated keystore key is an error without confirmation`() {
        captureCallbacks()

        request()
        keyInvalidatedCallback!!.invoke()

        assertEquals(0, confirmedCount)
        assertEquals(
            listOf("La biométrie de l'appareil a changé : réessayez, ou reconnectez-vous à l'application."),
            errors,
        )
    }

    @Test
    fun `a late success after an error does not confirm`() {
        captureCallbacks()

        request()
        failureCallback!!.invoke("Annuler")
        successCallback!!.invoke()

        assertEquals(0, confirmedCount)
        assertEquals(listOf("Annuler"), errors)
    }

    @Test
    fun `a late error after a success is ignored`() {
        captureCallbacks()

        request()
        successCallback!!.invoke()
        failureCallback!!.invoke("Annuler")

        assertEquals(1, confirmedCount)
        assertEquals(emptyList<String>(), errors)
    }

    @Test
    fun `an exception while showing the prompt is an error and never a confirmation`() {
        every { manager.authenticate(any(), any(), any(), any(), any(), any()) } throws
            IllegalStateException("FragmentManager has already saved its state")

        request()

        assertEquals(0, confirmedCount)
        assertEquals(listOf("Authentification indisponible sur cet écran"), errors)
    }

    @Test
    fun `biometricErrorMessage keeps a real message and replaces blank or null`() {
        assertEquals("Annulé", biometricErrorMessage("  Annulé "))
        assertEquals("Confirmation annulée ou échouée — réessayez.", biometricErrorMessage(""))
        assertEquals("Confirmation annulée ou échouée — réessayez.", biometricErrorMessage(null))
    }
}
