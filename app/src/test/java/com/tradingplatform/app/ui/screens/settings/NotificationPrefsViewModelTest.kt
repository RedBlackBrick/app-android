package com.tradingplatform.app.ui.screens.settings

import com.tradingplatform.app.domain.exception.HttpStatusException
import com.tradingplatform.app.domain.model.ChannelPreferences
import com.tradingplatform.app.domain.model.NotifCategory
import com.tradingplatform.app.domain.model.NotificationPreferences
import com.tradingplatform.app.domain.model.QuietHours
import com.tradingplatform.app.domain.model.RiskAlertThresholds
import com.tradingplatform.app.domain.model.WriteOutcome
import com.tradingplatform.app.domain.usecase.notification.GetNotificationPreferencesUseCase
import com.tradingplatform.app.domain.usecase.notification.SetPushCategoryUseCase
import com.tradingplatform.app.domain.usecase.write.EvaluateWriteGateUseCase
import com.tradingplatform.app.domain.usecase.write.WriteBlockReason
import com.tradingplatform.app.domain.usecase.write.WriteGate
import com.tradingplatform.app.util.MainDispatcherRule
import com.tradingplatform.app.vpn.VpnNotConnectedException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationPrefsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val getPrefs = mockk<GetNotificationPreferencesUseCase>()
    private val setPush = mockk<SetPushCategoryUseCase>()
    private val writeGate = mockk<EvaluateWriteGateUseCase>()

    /** Horloge simulée : l'heure de lecture passée à la garde d'écriture. */
    private var now = 2_000_000L

    private val staleMessage = "Données trop anciennes — actualisez l'écran avant d'agir."

    /** strategy=true, risk=true, system=false ; heures calmes actives ; un seuil de drawdown à 10 %. */
    private fun prefs(strategy: Boolean = true, risk: Boolean = true, system: Boolean = false) =
        NotificationPreferences(
            categories = mapOf(
                NotifCategory.STRATEGY_SIGNAL to ChannelPreferences(inApp = true, push = strategy, email = false),
                NotifCategory.RISK_ALERT to ChannelPreferences(inApp = true, push = risk, email = true),
                NotifCategory.SYSTEM to ChannelPreferences(inApp = true, push = system, email = true),
            ),
            quietHours = QuietHours(enabled = true, start = "22:00", end = "08:00"),
            riskAlertThresholds = RiskAlertThresholds(drawdownWarnPct = 0.10),
        )

    @Before
    fun setUp() {
        every { writeGate(any(), any()) } returns WriteGate.Allowed
    }

    private fun createViewModel() = NotificationPrefsViewModel(
        getNotificationPreferencesUseCase = getPrefs,
        setPushCategoryUseCase = setPush,
        evaluateWriteGate = writeGate,
        clock = { now },
    )

    // ── Lecture ──────────────────────────────────────────────────────────────

    @Test
    fun `initial load exposes the preferences with their read time`() = runTest {
        coEvery { getPrefs() } returns Result.success(prefs())

        val state = createViewModel().uiState.value

        assertEquals(prefs(), state.prefs.value)
        assertEquals(2_000_000L, state.prefs.syncedAt)
        assertFalse(state.prefs.isRefreshing)
        assertNull(state.prefs.error)
        assertNull(state.savingCategory)
        assertNull(state.pendingChange)
    }

    @Test
    fun `load failure without a value exposes an explicit error and allows no change`() = runTest {
        coEvery { getPrefs() } returns Result.failure(VpnNotConnectedException())
        val vm = createViewModel()

        val state = vm.uiState.value
        assertNull(state.prefs.value)
        assertEquals("VPN requis — activez le tunnel puis réessayez.", state.prefs.error)

        vm.onPushToggleRequested(NotifCategory.SYSTEM, true)

        verify(exactly = 0) { writeGate(any(), any()) }
        assertNull(vm.uiState.value.pendingChange)
    }

    @Test
    fun `refresh reloads the preferences and updates the read time`() = runTest {
        coEvery { getPrefs() } returnsMany listOf(
            Result.success(prefs(system = false)),
            Result.success(prefs(system = true)),
        )
        val vm = createViewModel()
        now = 2_090_000L

        vm.refresh()

        val state = vm.uiState.value
        assertTrue(state.prefs.value!!.isPushEnabled(NotifCategory.SYSTEM))
        assertEquals(2_090_000L, state.prefs.syncedAt)
    }

    // ── Garde et confirmation ────────────────────────────────────────────────

    @Test
    fun `blocked gate shows its message and neither opens the confirmation nor writes`() = runTest {
        coEvery { getPrefs() } returns Result.success(prefs())
        every { writeGate(any(), any()) } returns WriteGate.Blocked(WriteBlockReason.DATA_STALE, staleMessage)
        val vm = createViewModel()

        vm.onPushToggleRequested(NotifCategory.RISK_ALERT, false)

        assertEquals(staleMessage, vm.uiState.value.message)
        assertNull(vm.uiState.value.pendingChange)
        assertNull(vm.uiState.value.pendingConfirmation)
        coVerify(exactly = 0) { setPush(any(), any()) }
    }

    @Test
    fun `allowed gate opens a non destructive confirmation without reason and evaluates the read time`() = runTest {
        coEvery { getPrefs() } returns Result.success(prefs())
        val vm = createViewModel()

        vm.onPushToggleRequested(NotifCategory.RISK_ALERT, false)

        assertEquals(PushChange(NotifCategory.RISK_ALERT, false), vm.uiState.value.pendingChange)
        val action = vm.uiState.value.pendingConfirmation
        assertNotNull(action)
        assertEquals("Désactiver les notifications push ?", action!!.title)
        assertEquals("Désactiver le push", action.confirmLabel)
        assertFalse(action.destructive)
        assertFalse(action.requireReason)
        assertEquals(
            listOf(
                "Catégorie" to "Alertes de risque",
                "Notification push" to "Désactivée",
                "Portée" to "Tous vos appareils",
            ),
            action.summaryLines,
        )
        verify(exactly = 1) { writeGate(2_000_000L, 60_000L) }
        coVerify(exactly = 0) { setPush(any(), any()) }
    }

    @Test
    fun `enabling a category uses the activation wording`() = runTest {
        coEvery { getPrefs() } returns Result.success(prefs())
        val vm = createViewModel()

        vm.onPushToggleRequested(NotifCategory.SYSTEM, true)

        val action = vm.uiState.value.pendingConfirmation
        assertEquals("Activer les notifications push ?", action!!.title)
        assertEquals("Activer le push", action.confirmLabel)
        assertEquals("Catégorie" to "Système", action.summaryLines[0])
        assertEquals("Notification push" to "Activée", action.summaryLines[1])
    }

    @Test
    fun `requesting the value already read does nothing`() = runTest {
        coEvery { getPrefs() } returns Result.success(prefs(risk = true))
        val vm = createViewModel()

        vm.onPushToggleRequested(NotifCategory.RISK_ALERT, true)

        verify(exactly = 0) { writeGate(any(), any()) }
        assertNull(vm.uiState.value.pendingChange)
    }

    @Test
    fun `dismissing the confirmation closes it and a late confirmation is ignored`() = runTest {
        coEvery { getPrefs() } returns Result.success(prefs())
        val vm = createViewModel()
        vm.onPushToggleRequested(NotifCategory.RISK_ALERT, false)

        vm.onChangeDismissed()
        vm.onChangeConfirmed()

        assertNull(vm.uiState.value.pendingChange)
        coVerify(exactly = 0) { setPush(any(), any()) }
    }

    // ── Écriture unique + relecture ──────────────────────────────────────────

    @Test
    fun `confirmation writes exactly once then the switch shows the re-read state`() = runTest {
        coEvery { getPrefs() } returnsMany listOf(
            Result.success(prefs(risk = true)),
            Result.success(prefs(risk = false)),
        )
        coEvery { setPush(NotifCategory.RISK_ALERT, false) } returns Result.success(WriteOutcome.CONFIRMED)
        val vm = createViewModel()

        vm.onPushToggleRequested(NotifCategory.RISK_ALERT, false)
        vm.onChangeConfirmed()

        coVerify(exactly = 1) { setPush(NotifCategory.RISK_ALERT, false) }
        coVerify(exactly = 2) { getPrefs() }
        val state = vm.uiState.value
        assertFalse(state.prefs.value!!.isPushEnabled(NotifCategory.RISK_ALERT))
        assertEquals("Notifications push désactivées : Alertes de risque.", state.message)
        assertNull(state.savingCategory)
        assertNull(state.pendingChange)
    }

    @Test
    fun `the switch is not optimistic while the write is in flight`() = runTest {
        val release = CompletableDeferred<Result<WriteOutcome>>()
        coEvery { getPrefs() } returnsMany listOf(
            Result.success(prefs(risk = true)),
            Result.success(prefs(risk = false)),
        )
        coEvery { setPush(NotifCategory.RISK_ALERT, false) } coAnswers { release.await() }
        val vm = createViewModel()
        vm.onPushToggleRequested(NotifCategory.RISK_ALERT, false)

        vm.onChangeConfirmed()

        // Écriture en vol : l'interrupteur montre toujours l'état relu (activé) et la catégorie est verrouillée.
        val inFlight = vm.uiState.value
        assertTrue(inFlight.prefs.value!!.isPushEnabled(NotifCategory.RISK_ALERT))
        assertEquals(NotifCategory.RISK_ALERT, inFlight.savingCategory)
        assertNull(inFlight.message)

        release.complete(Result.success(WriteOutcome.CONFIRMED))

        assertFalse(vm.uiState.value.prefs.value!!.isPushEnabled(NotifCategory.RISK_ALERT))
        assertNull(vm.uiState.value.savingCategory)
    }

    @Test
    fun `no second write can start while one is in flight`() = runTest {
        val release = CompletableDeferred<Result<WriteOutcome>>()
        coEvery { getPrefs() } returnsMany listOf(
            Result.success(prefs(risk = true)),
            Result.success(prefs(risk = false)),
        )
        coEvery { setPush(NotifCategory.RISK_ALERT, false) } coAnswers { release.await() }
        val vm = createViewModel()
        vm.onPushToggleRequested(NotifCategory.RISK_ALERT, false)
        vm.onChangeConfirmed()

        // Double confirmation, autre interrupteur et pull-to-refresh pendant l'écriture : ignorés.
        vm.onChangeConfirmed()
        vm.onPushToggleRequested(NotifCategory.SYSTEM, true)
        vm.refresh()

        assertNull(vm.uiState.value.pendingChange)
        verify(exactly = 2) { writeGate(any(), any()) } // ouverture + avant l'envoi
        coVerify(exactly = 1) { setPush(any(), any()) }
        coVerify(exactly = 1) { getPrefs() } // pas de relecture parasite avant la fin

        release.complete(Result.success(WriteOutcome.CONFIRMED))

        coVerify(exactly = 1) { setPush(any(), any()) }
        coVerify(exactly = 2) { getPrefs() }
    }

    @Test
    fun `unconfirmed outcome with a re-read that shows the change reports it applied`() = runTest {
        coEvery { getPrefs() } returnsMany listOf(
            Result.success(prefs(system = false)),
            Result.success(prefs(system = true)),
        )
        coEvery { setPush(NotifCategory.SYSTEM, true) } returns Result.success(WriteOutcome.REQUESTED_UNCONFIRMED)
        val vm = createViewModel()

        vm.onPushToggleRequested(NotifCategory.SYSTEM, true)
        vm.onChangeConfirmed()

        assertEquals("Notifications push activées : Système.", vm.uiState.value.message)
        assertTrue(vm.uiState.value.prefs.value!!.isPushEnabled(NotifCategory.SYSTEM))
        coVerify(exactly = 1) { setPush(any(), any()) }
    }

    @Test
    fun `unconfirmed outcome with a re-read that shows no change never claims success and never retries`() = runTest {
        coEvery { getPrefs() } returns Result.success(prefs(risk = true))
        coEvery { setPush(NotifCategory.RISK_ALERT, false) } returns
            Result.success(WriteOutcome.REQUESTED_UNCONFIRMED)
        val vm = createViewModel()

        vm.onPushToggleRequested(NotifCategory.RISK_ALERT, false)
        vm.onChangeConfirmed()

        val state = vm.uiState.value
        assertEquals(
            "Modification demandée, mais le serveur affiche encore l'état précédent. " +
                "Actualisez avant de réessayer.",
            state.message,
        )
        assertTrue(state.prefs.value!!.isPushEnabled(NotifCategory.RISK_ALERT)) // l'interrupteur reste sur l'état relu
        assertNull(state.savingCategory)
        coVerify(exactly = 1) { setPush(any(), any()) }
        coVerify(exactly = 2) { getPrefs() }
    }

    @Test
    fun `a failed re-read after the write says so and keeps the previous value`() = runTest {
        coEvery { getPrefs() } returnsMany listOf(
            Result.success(prefs(risk = true)),
            Result.failure(IOException("timeout")),
        )
        coEvery { setPush(NotifCategory.RISK_ALERT, false) } returns Result.success(WriteOutcome.CONFIRMED)
        val vm = createViewModel()

        vm.onPushToggleRequested(NotifCategory.RISK_ALERT, false)
        vm.onChangeConfirmed()

        val state = vm.uiState.value
        assertEquals(
            "Modification demandée — relecture impossible. Actualisez pour vérifier avant de réessayer.",
            state.message,
        )
        assertTrue(state.prefs.value!!.isPushEnabled(NotifCategory.RISK_ALERT))
        assertNull(state.savingCategory)
    }

    @Test
    fun `an explicit refusal shows a clear message, does not re-read and does not retry`() = runTest {
        coEvery { getPrefs() } returns Result.success(prefs(risk = true))
        coEvery { setPush(NotifCategory.RISK_ALERT, false) } returns
            Result.failure(HttpStatusException(422, "v1/auth/preferences"))
        val vm = createViewModel()

        vm.onPushToggleRequested(NotifCategory.RISK_ALERT, false)
        vm.onChangeConfirmed()

        val state = vm.uiState.value
        assertEquals("Modification refusée par le serveur (HTTP 422) — préférence inchangée.", state.message)
        assertNull(state.savingCategory)
        coVerify(exactly = 1) { setPush(any(), any()) }
        coVerify(exactly = 1) { getPrefs() }
    }

    @Test
    fun `a write without VPN reports the VPN requirement`() = runTest {
        coEvery { getPrefs() } returns Result.success(prefs())
        coEvery { setPush(NotifCategory.RISK_ALERT, false) } returns Result.failure(VpnNotConnectedException())
        val vm = createViewModel()

        vm.onPushToggleRequested(NotifCategory.RISK_ALERT, false)
        vm.onChangeConfirmed()

        assertEquals("VPN requis — activez le tunnel puis réessayez.", vm.uiState.value.message)
        assertNull(vm.uiState.value.savingCategory)
    }

    @Test
    fun `a conflict re-reads the preferences and shows the conflict message`() = runTest {
        coEvery { getPrefs() } returnsMany listOf(
            Result.success(prefs(risk = true)),
            Result.success(prefs(risk = false)),
        )
        coEvery { setPush(NotifCategory.RISK_ALERT, false) } returns Result.failure(
            HttpStatusException(409, "v1/auth/preferences", "Préférences modifiées entre-temps — réessayez"),
        )
        val vm = createViewModel()

        vm.onPushToggleRequested(NotifCategory.RISK_ALERT, false)
        vm.onChangeConfirmed()

        assertEquals("Préférences modifiées entre-temps — réessayez", vm.uiState.value.message)
        assertFalse(vm.uiState.value.prefs.value!!.isPushEnabled(NotifCategory.RISK_ALERT))
        coVerify(exactly = 1) { setPush(any(), any()) }
        coVerify(exactly = 2) { getPrefs() }
    }

    @Test
    fun `the gate is re-evaluated before sending and a blocked gate prevents the write`() = runTest {
        coEvery { getPrefs() } returns Result.success(prefs())
        every { writeGate(any(), any()) } returnsMany listOf(
            WriteGate.Allowed,
            WriteGate.Blocked(WriteBlockReason.DATA_STALE, staleMessage),
        )
        val vm = createViewModel()

        vm.onPushToggleRequested(NotifCategory.RISK_ALERT, false) // ouverture : autorisé
        vm.onChangeConfirmed() // après la biométrie : donnée devenue périmée

        assertEquals(staleMessage, vm.uiState.value.message)
        assertNull(vm.uiState.value.savingCategory)
        coVerify(exactly = 0) { setPush(any(), any()) }
    }

    @Test
    fun `the message is consumed once shown`() = runTest {
        coEvery { getPrefs() } returns Result.success(prefs())
        every { writeGate(any(), any()) } returns WriteGate.Blocked(WriteBlockReason.DATA_STALE, staleMessage)
        val vm = createViewModel()
        vm.onPushToggleRequested(NotifCategory.RISK_ALERT, false)
        assertEquals(staleMessage, vm.uiState.value.message)

        vm.onMessageShown()

        assertNull(vm.uiState.value.message)
    }
}
