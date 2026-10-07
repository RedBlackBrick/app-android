package com.tradingplatform.app.ui.screens.vigie

import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.domain.model.Cached
import com.tradingplatform.app.domain.model.Vigie
import com.tradingplatform.app.domain.model.VigieLevel
import com.tradingplatform.app.domain.usecase.vigie.GetVigieUseCase
import com.tradingplatform.app.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class VigieViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val getVigieUseCase = mockk<GetVigieUseCase>()
    private lateinit var viewModel: VigieViewModel

    private val vigie = Vigie.UNAVAILABLE.copy(available = true, level = VigieLevel.NORMAL, score = 1.0)

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
    }

    private fun createViewModel() = VigieViewModel(getVigieUseCase).also { viewModel = it }

    @Test
    fun `initial load reads the cache-aware use case without forcing and exposes the REAL syncedAt`() = runTest {
        coEvery { getVigieUseCase(any()) } returns Result.success(Cached(vigie, syncedAt = 999L))

        createViewModel()

        val state = viewModel.uiState.value as VigieUiState.Success
        assertEquals(999L, state.syncedAt)
        assertEquals(VigieLevel.NORMAL, state.vigie.level)
        assertEquals(false, state.isRefreshing)
        assertNull(state.staleError)
        coVerify(exactly = 1) { getVigieUseCase(false) }
    }

    @Test
    fun `starts in Loading until the read returns`() = runTest {
        val gate = CompletableDeferred<Result<Cached<Vigie>>>()
        coEvery { getVigieUseCase(any()) } coAnswers { gate.await() }

        createViewModel()
        assertEquals(VigieUiState.Loading, viewModel.uiState.value)

        gate.complete(Result.success(Cached(vigie, 1L)))
        assertTrue(viewModel.uiState.value is VigieUiState.Success)
    }

    @Test
    fun `first read failing with no cache is an Error with a retry path`() = runTest {
        coEvery { getVigieUseCase(any()) } returns Result.failure(IOException("VPN down"))

        createViewModel()

        assertTrue(viewModel.uiState.value is VigieUiState.Error)

        coEvery { getVigieUseCase(true) } returns Result.success(Cached(vigie, 5L))
        viewModel.refresh()
        assertEquals(5L, (viewModel.uiState.value as VigieUiState.Success).syncedAt)
    }

    @Test
    fun `refresh forces the read`() = runTest {
        coEvery { getVigieUseCase(any()) } returns Result.success(Cached(vigie, 1L))
        createViewModel()

        viewModel.refresh()

        coVerify(exactly = 1) { getVigieUseCase(true) }
    }

    @Test
    fun `a failed refresh keeps the stale value with its old syncedAt and an error, never a blocking Error`() = runTest {
        coEvery { getVigieUseCase(any()) } returns Result.success(Cached(vigie, syncedAt = 42L))
        createViewModel()
        coEvery { getVigieUseCase(true) } returns Result.failure(IOException("Timeout"))

        viewModel.refresh()

        val state = viewModel.uiState.value as VigieUiState.Success
        assertEquals(42L, state.syncedAt)
        assertEquals(VigieLevel.NORMAL, state.vigie.level)
        assertNotNull(state.staleError)
        assertEquals(false, state.isRefreshing)
    }

    @Test
    fun `a successful refresh clears the stale error`() = runTest {
        coEvery { getVigieUseCase(any()) } returns Result.success(Cached(vigie, 1L))
        createViewModel()
        coEvery { getVigieUseCase(true) } returns Result.failure(IOException("Timeout"))
        viewModel.refresh()
        coEvery { getVigieUseCase(true) } returns Result.success(Cached(vigie, 2L))

        viewModel.refresh()

        val state = viewModel.uiState.value as VigieUiState.Success
        assertNull(state.staleError)
        assertEquals(2L, state.syncedAt)
    }

    @Test
    fun `a refresh while a read is in flight is ignored`() = runTest {
        val gate = CompletableDeferred<Result<Cached<Vigie>>>()
        coEvery { getVigieUseCase(any()) } coAnswers { gate.await() }
        createViewModel()

        viewModel.refresh()
        viewModel.refresh()

        coVerify(exactly = 1) { getVigieUseCase(any()) }
        gate.complete(Result.success(Cached(vigie, 1L)))
    }

    @Test
    fun `an unavailable Vigie is a Success carrying the unavailable value, not an error`() = runTest {
        coEvery { getVigieUseCase(any()) } returns Result.success(Cached(Vigie.UNAVAILABLE, 7L))

        createViewModel()

        val state = viewModel.uiState.value as VigieUiState.Success
        assertEquals(false, state.vigie.available)
        assertEquals(VigieLevel.UNKNOWN, state.vigie.level)
    }
}
