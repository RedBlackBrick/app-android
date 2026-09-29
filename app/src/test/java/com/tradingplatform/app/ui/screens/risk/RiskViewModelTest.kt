package com.tradingplatform.app.ui.screens.risk

import com.tradingplatform.app.domain.exception.HttpStatusException
import com.tradingplatform.app.domain.model.Portfolio
import com.tradingplatform.app.domain.model.RiskStatus
import com.tradingplatform.app.domain.model.WriteOutcome
import com.tradingplatform.app.domain.usecase.portfolio.ObserveActivePortfolioUseCase
import com.tradingplatform.app.domain.usecase.portfolio.ObservePortfoliosUseCase
import com.tradingplatform.app.domain.usecase.risk.ActivatePortfolioKillSwitchUseCase
import com.tradingplatform.app.domain.usecase.risk.GetRiskStatusUseCase
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
import kotlinx.coroutines.flow.MutableStateFlow
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
class RiskViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val observeActivePortfolio = mockk<ObserveActivePortfolioUseCase>()
    private val observePortfolios = mockk<ObservePortfoliosUseCase>()
    private val getRiskStatus = mockk<GetRiskStatusUseCase>()
    private val activateKillSwitch = mockk<ActivatePortfolioKillSwitchUseCase>()
    private val writeGate = mockk<EvaluateWriteGateUseCase>()

    /** Portefeuille actif simulé : modifier `.value` équivaut à un changement de sélection. */
    private val activePortfolio = MutableStateFlow("p1")
    private val portfolios = MutableStateFlow(
        listOf(
            Portfolio(id = "p1", name = "Growth EUR", currency = "EUR"),
            Portfolio(id = "p2", name = "Value USD", currency = "USD"),
        ),
    )

    /** Horloge simulée : l'heure de lecture passée à la garde d'écriture. */
    private var now = 1_000_000L

    private val inactive = RiskStatus(
        killSwitchActive = false,
        killSwitchReason = null,
        unresolvedViolations = 2,
        dailyLossUsagePct = 0.85,
        drawdownCurrentPct = -0.124,
    )
    private val active = inactive.copy(killSwitchActive = true, killSwitchReason = "Arrêt manuel")

    private val gateAllowed: WriteGate = WriteGate.Allowed
    private val staleMessage = "Données trop anciennes — actualisez l'écran avant d'agir."

    @Before
    fun setUp() {
        every { observeActivePortfolio() } returns activePortfolio
        every { observePortfolios() } returns portfolios
        every { writeGate(any(), any()) } returns gateAllowed
    }

    private fun createViewModel() = RiskViewModel(
        observeActivePortfolioUseCase = observeActivePortfolio,
        observePortfoliosUseCase = observePortfolios,
        getRiskStatusUseCase = getRiskStatus,
        activatePortfolioKillSwitchUseCase = activateKillSwitch,
        evaluateWriteGate = writeGate,
        clock = { now },
    )

    // ── Lecture ──────────────────────────────────────────────────────────────

    @Test
    fun `initial load exposes the risk status of the active portfolio with its read time`() = runTest {
        coEvery { getRiskStatus("p1") } returns Result.success(inactive)

        val state = createViewModel().uiState.value

        assertEquals("p1", state.portfolioId)
        assertEquals(inactive, state.risk.value)
        assertEquals(1_000_000L, state.risk.syncedAt)
        assertFalse(state.risk.isRefreshing)
        assertNull(state.risk.error)
        assertTrue(state.canOfferSuspend)
        assertFalse(state.isWriting)
    }

    @Test
    fun `load failure without a value exposes an explicit error and offers no action`() = runTest {
        coEvery { getRiskStatus("p1") } returns Result.failure(VpnNotConnectedException())

        val vm = createViewModel()
        val state = vm.uiState.value

        assertNull(state.risk.value)
        assertEquals("VPN requis — activez le tunnel puis réessayez.", state.risk.error)
        assertFalse(state.canOfferSuspend)

        vm.onSuspendClicked()

        verify(exactly = 0) { writeGate(any(), any()) }
        assertNull(vm.uiState.value.pendingConfirmation)
    }

    @Test
    fun `refresh keeps the stale value when the refresh fails`() = runTest {
        coEvery { getRiskStatus("p1") } returnsMany listOf(
            Result.success(inactive),
            Result.failure(IOException("timeout")),
        )
        val vm = createViewModel()

        vm.refresh()

        val state = vm.uiState.value
        assertEquals(inactive, state.risk.value)
        assertEquals("Serveur injoignable — vérifiez la connexion puis réessayez.", state.risk.error)
    }

    // ── Garde d'écriture et confirmation ─────────────────────────────────────

    @Test
    fun `blocked gate shows its message and neither opens the confirmation nor writes`() = runTest {
        coEvery { getRiskStatus("p1") } returns Result.success(inactive)
        every { writeGate(any(), any()) } returns WriteGate.Blocked(WriteBlockReason.DATA_STALE, staleMessage)
        val vm = createViewModel()

        vm.onSuspendClicked()

        assertEquals(staleMessage, vm.uiState.value.message)
        assertNull(vm.uiState.value.pendingConfirmation)
        coVerify(exactly = 0) { activateKillSwitch(any(), any()) }
    }

    @Test
    fun `allowed gate opens a destructive confirmation with a mandatory reason and the full recap`() = runTest {
        coEvery { getRiskStatus("p1") } returns Result.success(inactive)
        val vm = createViewModel()

        vm.onSuspendClicked()

        val action = vm.uiState.value.pendingConfirmation
        assertNotNull(action)
        assertEquals("Suspendre le trading ?", action!!.title)
        assertTrue(action.destructive)
        assertTrue(action.requireReason)
        assertEquals("Suspendre le trading", action.confirmLabel)
        assertEquals(
            listOf(
                "Portefeuille" to "Growth EUR",
                "Effet" to "Nouveaux ordres bloqués pendant 7 jours",
                "Ordres ouverts" to "Non annulés",
                "Sorties" to "Non bloquées (stops, clôtures)",
                "Levée" to "Sur le web uniquement",
            ),
            action.summaryLines,
        )
        coVerify(exactly = 0) { activateKillSwitch(any(), any()) }
    }

    @Test
    fun `the gate is evaluated with the read time of the state and the 60 second window`() = runTest {
        coEvery { getRiskStatus("p1") } returns Result.success(inactive)
        val vm = createViewModel()
        now = 1_050_000L
        vm.refresh()

        vm.onSuspendClicked()

        verify(exactly = 1) { writeGate(1_050_000L, 60_000L) }
    }

    @Test
    fun `dismissing the confirmation closes it and a late confirmation is ignored`() = runTest {
        coEvery { getRiskStatus("p1") } returns Result.success(inactive)
        val vm = createViewModel()
        vm.onSuspendClicked()

        vm.onConfirmationDismissed()
        vm.onSuspendConfirmed("Motif tardif")

        assertNull(vm.uiState.value.pendingConfirmation)
        coVerify(exactly = 0) { activateKillSwitch(any(), any()) }
    }

    @Test
    fun `kill switch already active offers no action and never evaluates the gate`() = runTest {
        coEvery { getRiskStatus("p1") } returns Result.success(active)
        val vm = createViewModel()

        vm.onSuspendClicked()

        assertFalse(vm.uiState.value.canOfferSuspend)
        assertNull(vm.uiState.value.pendingConfirmation)
        verify(exactly = 0) { writeGate(any(), any()) }
        coVerify(exactly = 0) { activateKillSwitch(any(), any()) }
    }

    @Test
    fun `a refresh that finds the kill switch active closes the open confirmation`() = runTest {
        coEvery { getRiskStatus("p1") } returnsMany listOf(Result.success(inactive), Result.success(active))
        val vm = createViewModel()
        vm.onSuspendClicked()
        assertNotNull(vm.uiState.value.pendingConfirmation)

        vm.refresh()
        vm.onSuspendConfirmed("Trop tard")

        assertNull(vm.uiState.value.pendingConfirmation)
        assertTrue(vm.uiState.value.risk.value!!.killSwitchActive)
        coVerify(exactly = 0) { activateKillSwitch(any(), any()) }
    }

    // ── Motif obligatoire ────────────────────────────────────────────────────

    @Test
    fun `a blank or missing reason never reaches the write`() = runTest {
        coEvery { getRiskStatus("p1") } returns Result.success(inactive)
        val vm = createViewModel()

        vm.onSuspendClicked()
        vm.onSuspendConfirmed("   ")
        assertEquals(
            "Motif obligatoire — la suspension n'a pas été envoyée.",
            vm.uiState.value.message,
        )
        vm.onMessageShown()
        vm.onSuspendClicked()
        vm.onSuspendConfirmed(null)

        assertEquals(
            "Motif obligatoire — la suspension n'a pas été envoyée.",
            vm.uiState.value.message,
        )
        assertFalse(vm.uiState.value.isWriting)
        coVerify(exactly = 0) { activateKillSwitch(any(), any()) }
    }

    // ── Écriture unique + relecture ──────────────────────────────────────────

    @Test
    fun `confirmation writes exactly once with the trimmed reason then re-reads the state`() = runTest {
        coEvery { getRiskStatus("p1") } returnsMany listOf(Result.success(inactive), Result.success(active))
        coEvery { activateKillSwitch("p1", "Volatilité anormale") } returns Result.success(WriteOutcome.CONFIRMED)
        val vm = createViewModel()

        vm.onSuspendClicked()
        vm.onSuspendConfirmed("  Volatilité anormale  ")

        coVerify(exactly = 1) { activateKillSwitch("p1", "Volatilité anormale") }
        coVerify(exactly = 2) { getRiskStatus("p1") }
        val state = vm.uiState.value
        assertEquals(active, state.risk.value)
        assertEquals("Suspension du trading confirmée — kill switch actif.", state.message)
        assertFalse(state.isWriting)
        assertFalse(state.canOfferSuspend)
        assertNull(state.pendingConfirmation)
    }

    @Test
    fun `while the write is in flight the button stays disabled and no second write can start`() = runTest {
        val release = CompletableDeferred<Result<WriteOutcome>>()
        coEvery { getRiskStatus("p1") } returnsMany listOf(Result.success(inactive), Result.success(active))
        coEvery { activateKillSwitch("p1", "Motif A") } coAnswers { release.await() }
        val vm = createViewModel()
        vm.onSuspendClicked()

        vm.onSuspendConfirmed("Motif A")

        assertTrue(vm.uiState.value.isWriting)
        assertNull(vm.uiState.value.pendingConfirmation)

        // Deuxième confirmation, deuxième tap et pull-to-refresh pendant l'écriture : tous ignorés.
        vm.onSuspendConfirmed("Motif A")
        vm.onSuspendClicked()
        vm.refresh()
        assertNull(vm.uiState.value.pendingConfirmation)
        verify(exactly = 2) { writeGate(any(), any()) } // 1 à l'ouverture + 1 avant l'envoi
        coVerify(exactly = 1) { activateKillSwitch(any(), any()) }
        coVerify(exactly = 1) { getRiskStatus("p1") } // pas de relecture parasite avant la fin

        release.complete(Result.success(WriteOutcome.CONFIRMED))

        assertFalse(vm.uiState.value.isWriting)
        coVerify(exactly = 1) { activateKillSwitch(any(), any()) }
        coVerify(exactly = 2) { getRiskStatus("p1") }
    }

    @Test
    fun `unconfirmed outcome followed by a re-read that shows the switch active reports it confirmed`() = runTest {
        coEvery { getRiskStatus("p1") } returnsMany listOf(Result.success(inactive), Result.success(active))
        coEvery { activateKillSwitch("p1", "Panne broker") } returns
            Result.success(WriteOutcome.REQUESTED_UNCONFIRMED)
        val vm = createViewModel()

        vm.onSuspendClicked()
        vm.onSuspendConfirmed("Panne broker")

        coVerify(exactly = 1) { activateKillSwitch(any(), any()) }
        coVerify(exactly = 2) { getRiskStatus("p1") }
        assertEquals("Suspension du trading confirmée — kill switch actif.", vm.uiState.value.message)
        assertTrue(vm.uiState.value.risk.value!!.killSwitchActive)
    }

    @Test
    fun `unconfirmed outcome followed by a re-read still inactive never claims success and never retries`() = runTest {
        coEvery { getRiskStatus("p1") } returns Result.success(inactive)
        coEvery { activateKillSwitch("p1", "Panne broker") } returns
            Result.success(WriteOutcome.REQUESTED_UNCONFIRMED)
        val vm = createViewModel()

        vm.onSuspendClicked()
        vm.onSuspendConfirmed("Panne broker")

        coVerify(exactly = 1) { activateKillSwitch(any(), any()) }
        coVerify(exactly = 2) { getRiskStatus("p1") }
        val state = vm.uiState.value
        assertEquals(
            "Suspension demandée — non confirmée par le serveur. Actualisez avant de réessayer.",
            state.message,
        )
        assertFalse(state.risk.value!!.killSwitchActive)
        assertFalse(state.isWriting)
        // Le bouton est de retour : l'utilisateur relance lui-même, après avoir vu l'état relu.
        assertTrue(state.canOfferSuspend)
    }

    @Test
    fun `a failed re-read after the write says so and keeps the previous value`() = runTest {
        coEvery { getRiskStatus("p1") } returnsMany listOf(
            Result.success(inactive),
            Result.failure(IOException("timeout")),
        )
        coEvery { activateKillSwitch("p1", "Motif") } returns Result.success(WriteOutcome.CONFIRMED)
        val vm = createViewModel()

        vm.onSuspendClicked()
        vm.onSuspendConfirmed("Motif")

        val state = vm.uiState.value
        assertEquals(
            "Suspension demandée — relecture impossible. Actualisez pour vérifier l'état avant de réessayer.",
            state.message,
        )
        assertEquals(inactive, state.risk.value)
        assertFalse(state.isWriting)
        coVerify(exactly = 1) { activateKillSwitch(any(), any()) }
    }

    @Test
    fun `an explicit refusal shows a clear message, does not re-read and does not retry`() = runTest {
        coEvery { getRiskStatus("p1") } returns Result.success(inactive)
        coEvery { activateKillSwitch("p1", "Motif") } returns Result.failure(
            HttpStatusException(403, "v1/risk/portfolios/{portfolio_id}/kill-switch"),
        )
        val vm = createViewModel()

        vm.onSuspendClicked()
        vm.onSuspendConfirmed("Motif")

        val state = vm.uiState.value
        assertEquals(
            "Suspension refusée — ce portefeuille n'est pas accessible avec ce compte.",
            state.message,
        )
        assertFalse(state.isWriting)
        assertTrue(state.canOfferSuspend)
        coVerify(exactly = 1) { activateKillSwitch(any(), any()) }
        coVerify(exactly = 1) { getRiskStatus("p1") }
    }

    @Test
    fun `a write without VPN reports the VPN requirement`() = runTest {
        coEvery { getRiskStatus("p1") } returns Result.success(inactive)
        coEvery { activateKillSwitch("p1", "Motif") } returns Result.failure(VpnNotConnectedException())
        val vm = createViewModel()

        vm.onSuspendClicked()
        vm.onSuspendConfirmed("Motif")

        assertEquals("VPN requis — activez le tunnel puis réessayez.", vm.uiState.value.message)
        assertFalse(vm.uiState.value.isWriting)
    }

    @Test
    fun `a conflict re-reads the state and shows the conflict message`() = runTest {
        coEvery { getRiskStatus("p1") } returnsMany listOf(Result.success(inactive), Result.success(active))
        coEvery { activateKillSwitch("p1", "Motif") } returns Result.failure(
            HttpStatusException(409, "v1/risk/portfolios/{portfolio_id}/kill-switch", "Conflit — relisez l'état"),
        )
        val vm = createViewModel()

        vm.onSuspendClicked()
        vm.onSuspendConfirmed("Motif")

        assertEquals("Conflit — relisez l'état", vm.uiState.value.message)
        assertTrue(vm.uiState.value.risk.value!!.killSwitchActive)
        coVerify(exactly = 1) { activateKillSwitch(any(), any()) }
        coVerify(exactly = 2) { getRiskStatus("p1") }
    }

    @Test
    fun `the gate is re-evaluated before sending and a blocked gate prevents the write`() = runTest {
        coEvery { getRiskStatus("p1") } returns Result.success(inactive)
        every { writeGate(any(), any()) } returnsMany listOf(
            WriteGate.Allowed,
            WriteGate.Blocked(WriteBlockReason.VPN_NOT_CONNECTED, "VPN requis — activez le tunnel puis réessayez."),
        )
        val vm = createViewModel()

        vm.onSuspendClicked() // ouverture : autorisé
        vm.onSuspendConfirmed("Motif") // avant l'envoi (après la biométrie) : bloqué

        assertEquals("VPN requis — activez le tunnel puis réessayez.", vm.uiState.value.message)
        assertFalse(vm.uiState.value.isWriting)
        coVerify(exactly = 0) { activateKillSwitch(any(), any()) }
    }

    @Test
    fun `the message is consumed once shown`() = runTest {
        coEvery { getRiskStatus("p1") } returns Result.success(inactive)
        every { writeGate(any(), any()) } returns WriteGate.Blocked(WriteBlockReason.DATA_STALE, staleMessage)
        val vm = createViewModel()
        vm.onSuspendClicked()
        assertEquals(staleMessage, vm.uiState.value.message)

        vm.onMessageShown()

        assertNull(vm.uiState.value.message)
    }

    // ── Changement de portefeuille actif ─────────────────────────────────────

    @Test
    fun `changing the active portfolio resets everything then reloads with the new id`() = runTest {
        val gate = CompletableDeferred<Result<RiskStatus>>()
        coEvery { getRiskStatus("p1") } returns Result.success(inactive)
        coEvery { getRiskStatus("p2") } coAnswers { gate.await() }
        val vm = createViewModel()
        vm.onSuspendClicked()
        assertNotNull(vm.uiState.value.pendingConfirmation)

        activePortfolio.value = "p2"

        // Rien de p1 ne reste affiché sous p2 pendant le rechargement.
        val loading = vm.uiState.value
        assertEquals("p2", loading.portfolioId)
        assertNull(loading.risk.value)
        assertTrue(loading.risk.isInitialLoading)
        assertNull(loading.pendingConfirmation)
        assertNull(loading.message)

        val p2Status = inactive.copy(unresolvedViolations = 7)
        gate.complete(Result.success(p2Status))

        assertEquals(p2Status, vm.uiState.value.risk.value)
        assertEquals("p2", vm.uiState.value.portfolioId)
        coVerify(exactly = 1) { getRiskStatus("p2") }
    }

    @Test
    fun `a late response for the previous portfolio never reaches the screen`() = runTest {
        val lateP1 = CompletableDeferred<Result<RiskStatus>>()
        val p2Status = inactive.copy(unresolvedViolations = 7)
        coEvery { getRiskStatus("p1") } coAnswers { lateP1.await() }
        coEvery { getRiskStatus("p2") } returns Result.success(p2Status)
        val vm = createViewModel() // la lecture de p1 est suspendue

        activePortfolio.value = "p2"
        lateP1.complete(Result.success(active)) // réponse tardive de p1

        assertEquals(p2Status, vm.uiState.value.risk.value)
        assertEquals("p2", vm.uiState.value.portfolioId)
    }

    @Test
    fun `a write finishing after a portfolio switch reports on the previous portfolio and leaves the new state alone`() =
        runTest {
            val release = CompletableDeferred<Result<WriteOutcome>>()
            val p2Status = inactive.copy(unresolvedViolations = 7)
            coEvery { getRiskStatus("p1") } returns Result.success(inactive)
            coEvery { getRiskStatus("p2") } returns Result.success(p2Status)
            coEvery { activateKillSwitch("p1", "Motif") } coAnswers { release.await() }
            val vm = createViewModel()
            vm.onSuspendClicked()
            vm.onSuspendConfirmed("Motif")
            assertTrue(vm.uiState.value.isWriting)

            activePortfolio.value = "p2"

            // Le bouton reste désactivé tant que l'écriture de p1 n'est pas terminée.
            assertEquals("p2", vm.uiState.value.portfolioId)
            assertEquals(p2Status, vm.uiState.value.risk.value)
            assertTrue(vm.uiState.value.isWriting)

            release.complete(Result.success(WriteOutcome.CONFIRMED))

            val state = vm.uiState.value
            assertFalse(state.isWriting)
            assertEquals(p2Status, state.risk.value)
            assertEquals(
                "Suspension demandée pour « Growth EUR » — vérifiez l'état de ce portefeuille.",
                state.message,
            )
            coVerify(exactly = 1) { activateKillSwitch(any(), any()) }
            coVerify(exactly = 1) { getRiskStatus("p1") } // pas de relecture de l'ancien portefeuille
        }
}
