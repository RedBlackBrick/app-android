package com.tradingplatform.app.ui.screens.strategies

import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.domain.exception.HttpStatusException
import com.tradingplatform.app.domain.model.Portfolio
import com.tradingplatform.app.domain.model.PortfolioStrategyEntry
import com.tradingplatform.app.domain.model.WriteOutcome
import com.tradingplatform.app.domain.usecase.portfolio.GetPortfolioStrategiesUseCase
import com.tradingplatform.app.domain.usecase.portfolio.ObserveActivePortfolioUseCase
import com.tradingplatform.app.domain.usecase.portfolio.ObservePortfoliosUseCase
import com.tradingplatform.app.domain.usecase.portfolio.SetPortfolioStrategyActiveUseCase
import com.tradingplatform.app.domain.usecase.write.EvaluateWriteGateUseCase
import com.tradingplatform.app.domain.usecase.write.WriteBlockReason
import com.tradingplatform.app.domain.usecase.write.WriteGate
import com.tradingplatform.app.util.MainDispatcherRule
import com.tradingplatform.app.vpn.VpnNotConnectedException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * Tests du [StrategiesViewModel] : lecture des liens du portefeuille actif, garde d'écriture,
 * confirmation, écriture UNE seule fois, relecture, échecs et changement de portefeuille.
 * Les attendus sont des valeurs littérales.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StrategiesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val observeActivePortfolioUseCase = mockk<ObserveActivePortfolioUseCase>()
    private val observePortfoliosUseCase = mockk<ObservePortfoliosUseCase>()
    private val getPortfolioStrategiesUseCase = mockk<GetPortfolioStrategiesUseCase>()
    private val setPortfolioStrategyActiveUseCase = mockk<SetPortfolioStrategyActiveUseCase>()
    private val evaluateWriteGateUseCase = mockk<EvaluateWriteGateUseCase>()

    /** Portefeuille actif simulé : modifier `.value` équivaut à un changement de sélection. */
    private val activePortfolio = MutableStateFlow("p1")
    private val portfolios = MutableStateFlow(
        listOf(
            Portfolio(id = "p1", name = "PEA Long terme", currency = "EUR"),
            Portfolio(id = "p2", name = "Compte test", currency = "USD"),
        ),
    )

    /** Arguments `dataSyncedAt` reçus par la garde, dans l'ordre. */
    private val gateArguments = mutableListOf<Long?>()

    private lateinit var viewModel: StrategiesViewModel

    private val momentumActive = entry("s1", "Momentum US", true)
    private val momentumPaused = entry("s1", "Momentum US", false)
    private val meanReversion = entry("s2", "Mean Reversion", true)
    private val optionsP2 = entry("s9", "Options", true)

    private fun entry(id: String, name: String?, active: Boolean) =
        PortfolioStrategyEntry(strategyId = id, name = name, isActive = active)

    @Before
    fun setUp() {
        every { observeActivePortfolioUseCase() } returns activePortfolio
        every { observePortfoliosUseCase() } returns portfolios
        every { evaluateWriteGateUseCase(any(), any()) } answers {
            gateArguments += firstArg<Long?>()
            WriteGate.Allowed
        }
    }

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
    }

    private fun createViewModel(): StrategiesViewModel = StrategiesViewModel(
        observeActivePortfolioUseCase = observeActivePortfolioUseCase,
        observePortfoliosUseCase = observePortfoliosUseCase,
        getPortfolioStrategiesUseCase = getPortfolioStrategiesUseCase,
        setPortfolioStrategyActiveUseCase = setPortfolioStrategyActiveUseCase,
        evaluateWriteGateUseCase = evaluateWriteGateUseCase,
    ).also { viewModel = it }

    private val state get() = viewModel.uiState.value

    // `vararg` d'un type `Result` (classe inline) est interdit par Kotlin : surcharges par arité.
    private fun stubReadList(portfolioId: String, results: List<Result<List<PortfolioStrategyEntry>>>) {
        coEvery { getPortfolioStrategiesUseCase(portfolioId) } returnsMany results
    }

    private fun stubReads(portfolioId: String, r1: Result<List<PortfolioStrategyEntry>>) =
        stubReadList(portfolioId, listOf(r1))

    private fun stubReads(
        portfolioId: String,
        r1: Result<List<PortfolioStrategyEntry>>,
        r2: Result<List<PortfolioStrategyEntry>>,
    ) = stubReadList(portfolioId, listOf(r1, r2))

    private fun stubReads(
        portfolioId: String,
        r1: Result<List<PortfolioStrategyEntry>>,
        r2: Result<List<PortfolioStrategyEntry>>,
        r3: Result<List<PortfolioStrategyEntry>>,
    ) = stubReadList(portfolioId, listOf(r1, r2, r3))

    // ── Lecture ───────────────────────────────────────────────────────────────

    @Test
    fun `loads the links of the active portfolio and stamps the read`() = runTest {
        stubReads("p1", Result.success(listOf(momentumActive, meanReversion)))
        val before = System.currentTimeMillis()

        createViewModel()

        val after = System.currentTimeMillis()
        assertEquals("p1", state.portfolioId)
        assertEquals(StrategiesContent.Success(listOf(momentumActive, meanReversion)), state.content)
        assertTrue("syncedAt = instant de la lecture réussie", state.syncedAt!! in before..after)
        assertFalse(state.isRefreshing)
        assertNull(state.write)
        assertNull(state.confirmation)
        assertNull(state.message)
        coVerify(exactly = 1) { getPortfolioStrategiesUseCase("p1") }
    }

    @Test
    fun `a failed first load shows an error without a timestamp then refresh recovers`() = runTest {
        coEvery { getPortfolioStrategiesUseCase("p1") } returns Result.failure(IOException("timeout"))

        createViewModel()

        assertEquals(
            StrategiesContent.Error("Impossible de charger les stratégies — tirez pour réessayer."),
            state.content,
        )
        assertNull(state.syncedAt)

        coEvery { getPortfolioStrategiesUseCase("p1") } returns Result.success(listOf(momentumActive))
        viewModel.refresh()

        assertEquals(StrategiesContent.Success(listOf(momentumActive)), state.content)
        assertNotNull(state.syncedAt)
    }

    @Test
    fun `a VPN failure on load says the tunnel is required`() = runTest {
        coEvery { getPortfolioStrategiesUseCase("p1") } returns Result.failure(VpnNotConnectedException())

        createViewModel()

        assertEquals(
            StrategiesContent.Error("VPN requis — activez le tunnel pour charger les stratégies."),
            state.content,
        )
    }

    @Test
    fun `a blank active portfolio shows an error and never calls the API`() = runTest {
        activePortfolio.value = ""

        createViewModel()

        assertEquals(StrategiesContent.Error("Portefeuille introuvable"), state.content)
        coVerify(exactly = 0) { getPortfolioStrategiesUseCase(any()) }
    }

    @Test
    fun `a failed refresh keeps the stale list with its timestamp and reports the error`() = runTest {
        stubReads("p1", Result.success(listOf(momentumActive)), Result.failure(IOException("down")))
        createViewModel()
        val syncedAt = state.syncedAt

        viewModel.refresh()

        assertEquals(StrategiesContent.Success(listOf(momentumActive)), state.content)
        assertEquals(syncedAt, state.syncedAt)
        assertEquals("Impossible de charger les stratégies — tirez pour réessayer.", state.message)
        assertFalse(state.isRefreshing)
    }

    // ── Garde d'écriture ──────────────────────────────────────────────────────

    @Test
    fun `a blocked write gate shows its message and never opens a confirmation nor writes`() = runTest {
        stubReads("p1", Result.success(listOf(momentumActive)))
        every { evaluateWriteGateUseCase(any(), any()) } returns WriteGate.Blocked(
            WriteBlockReason.DATA_STALE,
            "Données trop anciennes — actualisez l'écran avant d'agir.",
        )
        createViewModel()

        viewModel.requestToggle("s1")
        viewModel.confirmPending()

        assertNull(state.confirmation)
        assertNull(state.write)
        assertEquals("Données trop anciennes — actualisez l'écran avant d'agir.", state.message)
        coVerify(exactly = 0) { setPortfolioStrategyActiveUseCase(any(), any(), any()) }
    }

    @Test
    fun `the gate receives the instant of the last successful read`() = runTest {
        stubReads("p1", Result.success(listOf(momentumActive)))
        val before = System.currentTimeMillis()
        createViewModel()
        val after = System.currentTimeMillis()

        viewModel.requestToggle("s1")

        assertEquals(1, gateArguments.size)
        assertTrue(gateArguments[0]!! in before..after)
        assertEquals(state.syncedAt, gateArguments[0])
    }

    @Test
    fun `an unknown strategy id is ignored without evaluating the gate`() = runTest {
        stubReads("p1", Result.success(listOf(momentumActive)))
        createViewModel()

        viewModel.requestToggle("nope")

        assertNull(state.confirmation)
        assertNull(state.message)
        assertTrue(gateArguments.isEmpty())
    }

    // ── Confirmation ──────────────────────────────────────────────────────────

    @Test
    fun `an allowed gate exposes the pause confirmation with strategy portfolio and effect`() = runTest {
        stubReads("p1", Result.success(listOf(momentumActive)))
        createViewModel()

        viewModel.requestToggle("s1")

        val pending = state.confirmation!!
        assertEquals("p1", pending.portfolioId)
        assertEquals("s1", pending.strategyId)
        assertFalse(pending.targetActive)
        assertEquals("Mettre cette stratégie en pause ?", pending.action.title)
        assertEquals("Mettre en pause", pending.action.confirmLabel)
        assertEquals(
            listOf(
                "Stratégie" to "Momentum US",
                "Portefeuille" to "PEA Long terme",
            ),
            pending.action.summaryLines,
        )
        assertEquals(
            "Les nouveaux signaux de cette stratégie ne passeront plus d'ordres pour ce portefeuille.",
            pending.action.message,
        )
        assertFalse(pending.action.requireReason)
        coVerify(exactly = 0) { setPortfolioStrategyActiveUseCase(any(), any(), any()) }
    }

    @Test
    fun `the reactivation confirmation carries the detached-link warning`() = runTest {
        stubReads("p1", Result.success(listOf(momentumPaused)))
        createViewModel()

        viewModel.requestToggle("s1")

        val pending = state.confirmation!!
        assertTrue(pending.targetActive)
        assertEquals("Réactiver ce lien ?", pending.action.title)
        assertTrue(
            pending.action.message!!.endsWith(
                "Un lien détaché sur le web est indiscernable d'un lien en pause : " +
                    "la réactivation peut le remettre en service.",
            ),
        )
    }

    @Test
    fun `an unnamed link and an unknown portfolio name fall back to neutral labels`() = runTest {
        portfolios.value = emptyList()
        stubReads("p1", Result.success(listOf(entry("c2f4a9e0-1b7d-4e6a-8f30-5d9a1b2c3e4f", null, true))))
        createViewModel()

        viewModel.requestToggle("c2f4a9e0-1b7d-4e6a-8f30-5d9a1b2c3e4f")

        assertEquals(
            listOf(
                "Stratégie" to "Stratégie sans nom (…2c3e4f)",
                "Portefeuille" to "Portefeuille actif",
            ),
            state.confirmation!!.action.summaryLines.take(2),
        )
    }

    @Test
    fun `dismissing the confirmation drops it and a later confirm does nothing`() = runTest {
        stubReads("p1", Result.success(listOf(momentumActive)))
        createViewModel()
        viewModel.requestToggle("s1")

        viewModel.dismissConfirmation()
        viewModel.confirmPending()

        assertNull(state.confirmation)
        coVerify(exactly = 0) { setPortfolioStrategyActiveUseCase(any(), any(), any()) }
    }

    // ── Écriture ──────────────────────────────────────────────────────────────

    @Test
    fun `confirming writes exactly once, re-reads and shows the server state`() = runTest {
        stubReads(
            "p1",
            Result.success(listOf(momentumActive, meanReversion)),
            Result.success(listOf(momentumPaused, meanReversion)),
        )
        coEvery { setPortfolioStrategyActiveUseCase("p1", "s1", false) } returns
            Result.success(WriteOutcome.CONFIRMED)
        createViewModel()

        viewModel.requestToggle("s1")
        viewModel.confirmPending()
        viewModel.confirmPending() // 2e appel : la confirmation est déjà consommée

        coVerify(exactly = 1) { setPortfolioStrategyActiveUseCase("p1", "s1", false) }
        coVerify(exactly = 2) { getPortfolioStrategiesUseCase("p1") }
        assertEquals(StrategiesContent.Success(listOf(momentumPaused, meanReversion)), state.content)
        assertNull(state.write)
        assertNull(state.confirmation)
        assertEquals("Mise en pause effectuée", state.message)
        // Garde évaluée à l'ouverture de la feuille ET juste avant l'envoi.
        assertEquals(2, gateArguments.size)
    }

    @Test
    fun `reactivating a paused link sends active = true`() = runTest {
        stubReads("p1", Result.success(listOf(momentumPaused)), Result.success(listOf(momentumActive)))
        coEvery { setPortfolioStrategyActiveUseCase("p1", "s1", true) } returns
            Result.success(WriteOutcome.CONFIRMED)
        createViewModel()

        viewModel.requestToggle("s1")
        viewModel.confirmPending()

        coVerify(exactly = 1) { setPortfolioStrategyActiveUseCase("p1", "s1", true) }
        assertEquals(StrategiesContent.Success(listOf(momentumActive)), state.content)
        assertEquals("Réactivation effectuée", state.message)
    }

    @Test
    fun `an unconfirmed outcome says demandée then re-reads and confirms the state`() = runTest {
        stubReads("p1", Result.success(listOf(momentumActive)), Result.success(listOf(momentumPaused)))
        coEvery { setPortfolioStrategyActiveUseCase("p1", "s1", false) } returns
            Result.success(WriteOutcome.REQUESTED_UNCONFIRMED)
        createViewModel()

        viewModel.requestToggle("s1")
        viewModel.confirmPending()

        coVerify(exactly = 1) { setPortfolioStrategyActiveUseCase("p1", "s1", false) }
        coVerify(exactly = 2) { getPortfolioStrategiesUseCase("p1") }
        assertEquals(StrategiesContent.Success(listOf(momentumPaused)), state.content)
        assertEquals("Confirmé : le lien est en pause.", state.message)
        assertNull(state.write)
    }

    @Test
    fun `an unconfirmed outcome the server did not apply is reported with the real state`() = runTest {
        stubReads("p1", Result.success(listOf(momentumActive)), Result.success(listOf(momentumActive)))
        coEvery { setPortfolioStrategyActiveUseCase("p1", "s1", false) } returns
            Result.success(WriteOutcome.REQUESTED_UNCONFIRMED)
        createViewModel()

        viewModel.requestToggle("s1")
        viewModel.confirmPending()

        assertEquals(StrategiesContent.Success(listOf(momentumActive)), state.content)
        assertEquals("La demande n'apparaît pas appliquée : le lien est toujours actif.", state.message)
        assertNull(state.write)
    }

    @Test
    fun `the final state is shown only after the re-read completes`() = runTest {
        val rereadRelease = CompletableDeferred<Unit>()
        var reads = 0
        coEvery { getPortfolioStrategiesUseCase("p1") } coAnswers {
            reads++
            if (reads == 1) {
                Result.success(listOf(momentumActive))
            } else {
                rereadRelease.await()
                Result.success(listOf(momentumPaused))
            }
        }
        coEvery { setPortfolioStrategyActiveUseCase("p1", "s1", false) } returns
            Result.success(WriteOutcome.REQUESTED_UNCONFIRMED)
        createViewModel()

        viewModel.requestToggle("s1")
        viewModel.confirmPending()

        // Relecture en cours : l'ancien état reste affiché, les boutons restent désactivés.
        assertEquals(StrategyWritePhase.VERIFYING, state.write?.phase)
        assertEquals(StrategiesContent.Success(listOf(momentumActive)), state.content)
        assertEquals("Mise en pause demandée — vérification en cours", state.message)
        assertFalse(canStartStrategyWrite(state.write, state.isRefreshing))

        rereadRelease.complete(Unit)

        assertNull(state.write)
        assertEquals(StrategiesContent.Success(listOf(momentumPaused)), state.content)
    }

    @Test
    fun `a certain failure shows its message, does not re-read and does not retry`() = runTest {
        stubReads("p1", Result.success(listOf(momentumActive)))
        coEvery { setPortfolioStrategyActiveUseCase("p1", "s1", false) } returns Result.failure(
            HttpStatusException(409, "v1/portfolios/{portfolio_id}/strategies/{strategy_id}"),
        )
        createViewModel()
        val syncedAt = state.syncedAt

        viewModel.requestToggle("s1")
        viewModel.confirmPending()

        coVerify(exactly = 1) { setPortfolioStrategyActiveUseCase("p1", "s1", false) }
        coVerify(exactly = 1) { getPortfolioStrategiesUseCase("p1") }
        assertEquals("Conflit : l'état de ce lien a changé — actualisez avant de réessayer.", state.message)
        assertNull(state.write)
        assertEquals(StrategiesContent.Success(listOf(momentumActive)), state.content)
        assertEquals(syncedAt, state.syncedAt)
    }

    @Test
    fun `a write blocked by the VPN reports it`() = runTest {
        stubReads("p1", Result.success(listOf(momentumActive)))
        coEvery { setPortfolioStrategyActiveUseCase("p1", "s1", false) } returns
            Result.failure(VpnNotConnectedException())
        createViewModel()

        viewModel.requestToggle("s1")
        viewModel.confirmPending()

        assertEquals("VPN requis — activez le tunnel puis réessayez.", state.message)
        assertNull(state.write)
    }

    @Test
    fun `the gate is evaluated again right before sending and a block cancels the write`() = runTest {
        stubReads("p1", Result.success(listOf(momentumActive)))
        every { evaluateWriteGateUseCase(any(), any()) } returnsMany listOf(
            WriteGate.Allowed,
            WriteGate.Blocked(WriteBlockReason.VPN_NOT_CONNECTED, "VPN requis — activez le tunnel puis réessayez."),
        )
        createViewModel()

        viewModel.requestToggle("s1")
        assertNotNull(state.confirmation)
        viewModel.confirmPending()

        assertNull(state.confirmation)
        assertNull(state.write)
        assertEquals("VPN requis — activez le tunnel puis réessayez.", state.message)
        coVerify(exactly = 0) { setPortfolioStrategyActiveUseCase(any(), any(), any()) }
    }

    @Test
    fun `only one write at a time and no refresh while it is in flight`() = runTest {
        val release = CompletableDeferred<Unit>()
        stubReads("p1", Result.success(listOf(momentumActive, meanReversion)), Result.success(listOf(momentumPaused, meanReversion)))
        coEvery { setPortfolioStrategyActiveUseCase("p1", "s1", false) } coAnswers {
            release.await()
            Result.success(WriteOutcome.CONFIRMED)
        }
        createViewModel()

        viewModel.requestToggle("s1")
        viewModel.confirmPending()

        assertEquals(StrategyWritePhase.SENDING, state.write?.phase)
        assertEquals("s1", state.write?.strategyId)

        // Autre ligne pendant l'envoi : ignoré (pas de feuille, pas de garde).
        val gateCallsBefore = gateArguments.size
        viewModel.requestToggle("s2")
        assertNull(state.confirmation)
        assertEquals(gateCallsBefore, gateArguments.size)
        // Pull-to-refresh pendant l'envoi : ignoré.
        viewModel.refresh()
        coVerify(exactly = 1) { getPortfolioStrategiesUseCase("p1") }

        release.complete(Unit)

        assertNull(state.write)
        coVerify(exactly = 1) { setPortfolioStrategyActiveUseCase(any(), any(), any()) }
        assertEquals(StrategiesContent.Success(listOf(momentumPaused, meanReversion)), state.content)
    }

    @Test
    fun `a link that changed between the tap and the confirmation aborts the write`() = runTest {
        stubReads("p1", Result.success(listOf(momentumActive)), Result.success(listOf(momentumPaused)))
        createViewModel()
        viewModel.requestToggle("s1") // demande une pause

        viewModel.refresh() // entre-temps le lien est déjà en pause côté serveur
        viewModel.confirmPending()

        coVerify(exactly = 0) { setPortfolioStrategyActiveUseCase(any(), any(), any()) }
        assertEquals("L'état de ce lien a changé — vérifiez la liste avant d'agir.", state.message)
        assertNull(state.write)
    }

    @Test
    fun `a failed re-read leaves the state unverified and the gate sees no fresh read`() = runTest {
        stubReads("p1", Result.success(listOf(momentumActive)), Result.failure(IOException("down")))
        coEvery { setPortfolioStrategyActiveUseCase("p1", "s1", false) } returns
            Result.success(WriteOutcome.CONFIRMED)
        createViewModel()

        viewModel.requestToggle("s1")
        viewModel.confirmPending()

        assertEquals(
            "Demande envoyée, mais l'état n'a pas pu être relu — tirez pour actualiser avant d'agir.",
            state.message,
        )
        assertNull(state.syncedAt)
        assertNull(state.write)

        gateArguments.clear()
        viewModel.requestToggle("s1")
        assertEquals(listOf<Long?>(null), gateArguments)
    }

    // ── Message ───────────────────────────────────────────────────────────────

    @Test
    fun `messageShown clears only the message that was displayed`() = runTest {
        stubReads("p1", Result.success(listOf(momentumActive)))
        every { evaluateWriteGateUseCase(any(), any()) } returns WriteGate.Blocked(
            WriteBlockReason.NOT_LOGGED_IN,
            "Session expirée — reconnectez-vous.",
        )
        createViewModel()
        viewModel.requestToggle("s1")

        viewModel.messageShown("un autre message")
        assertEquals("Session expirée — reconnectez-vous.", state.message)

        viewModel.messageShown("Session expirée — reconnectez-vous.")
        assertNull(state.message)
    }

    // ── Changement de portefeuille ────────────────────────────────────────────

    @Test
    fun `switching portfolio resets the state before reloading with the new id`() = runTest {
        val p2Release = CompletableDeferred<Unit>()
        stubReads("p1", Result.success(listOf(momentumActive)))
        coEvery { getPortfolioStrategiesUseCase("p2") } coAnswers {
            p2Release.await()
            Result.success(listOf(optionsP2))
        }
        createViewModel()
        viewModel.requestToggle("s1") // confirmation ouverte pour p1
        assertNotNull(state.confirmation)

        activePortfolio.value = "p2"

        // Rechargement en cours : plus rien de p1 à l'écran.
        assertEquals("p2", state.portfolioId)
        assertEquals(StrategiesContent.Loading, state.content)
        assertNull(state.syncedAt)
        assertNull(state.confirmation)
        assertNull(state.message)

        p2Release.complete(Unit)

        assertEquals(StrategiesContent.Success(listOf(optionsP2)), state.content)
        assertNotNull(state.syncedAt)
        coVerify(exactly = 1) { getPortfolioStrategiesUseCase("p2") }

        // Une confirmation tardive de la feuille de p1 ne doit rien écrire.
        viewModel.confirmPending()
        coVerify(exactly = 0) { setPortfolioStrategyActiveUseCase(any(), any(), any()) }
    }

    @Test
    fun `a response for the previous portfolio never overwrites the new one`() = runTest {
        val p1Refresh = CompletableDeferred<Result<List<PortfolioStrategyEntry>>>()
        var p1Reads = 0
        coEvery { getPortfolioStrategiesUseCase("p1") } coAnswers {
            p1Reads++
            if (p1Reads == 1) Result.success(listOf(momentumActive)) else p1Refresh.await()
        }
        coEvery { getPortfolioStrategiesUseCase("p2") } returns Result.success(listOf(optionsP2))
        createViewModel()
        viewModel.refresh() // relecture de p1 en vol

        activePortfolio.value = "p2"
        p1Refresh.complete(Result.success(listOf(momentumActive, meanReversion)))

        assertEquals("p2", state.portfolioId)
        assertEquals(StrategiesContent.Success(listOf(optionsP2)), state.content)
    }

    @Test
    fun `a write in flight when the portfolio changes finishes without touching the new list`() = runTest {
        val release = CompletableDeferred<Unit>()
        stubReads("p1", Result.success(listOf(momentumActive)))
        coEvery { getPortfolioStrategiesUseCase("p2") } returns Result.success(listOf(optionsP2))
        coEvery { setPortfolioStrategyActiveUseCase("p1", "s1", false) } coAnswers {
            release.await()
            Result.success(WriteOutcome.CONFIRMED)
        }
        createViewModel()
        viewModel.requestToggle("s1")
        viewModel.confirmPending()

        activePortfolio.value = "p2"

        // La liste de p2 est affichée, mais une seule écriture à la fois : rien ne démarre.
        assertEquals(StrategiesContent.Success(listOf(optionsP2)), state.content)
        assertEquals("p1", state.write?.portfolioId)
        viewModel.requestToggle("s9")
        assertNull(state.confirmation)

        release.complete(Unit)

        assertNull(state.write)
        assertEquals("Une demande était en cours pour le portefeuille précédent — vérifiez son état.", state.message)
        assertEquals(StrategiesContent.Success(listOf(optionsP2)), state.content)
        // Pas de relecture de p1 : seule la lecture initiale a eu lieu.
        coVerify(exactly = 1) { getPortfolioStrategiesUseCase("p1") }
        coVerify(exactly = 1) { setPortfolioStrategyActiveUseCase(any(), any(), any()) }
    }
}
