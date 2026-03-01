package com.tradingplatform.app.ui.screens.settings

import com.tradingplatform.app.security.BiometricManager
import com.tradingplatform.app.security.RootDetector
import com.tradingplatform.app.util.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SecuritySettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // BiometricManager and RootDetector are mocked — they depend on Android Context
    // and cannot be instantiated in a JVM unit test.
    private val biometricManager = mockk<BiometricManager>()
    private val rootDetector = mockk<RootDetector>()

    @Before
    fun setUp() {
        // Default safe values — individual tests override as needed
        every { biometricManager.isAvailable() } returns false
        every { rootDetector.isRooted() } returns false
    }

    private fun createViewModel(): SecuritySettingsViewModel = SecuritySettingsViewModel(
        biometricManager = biometricManager,
        rootDetector = rootDetector,
    )

    // ── isRooted ──────────────────────────────────────────────────────────────

    @Test
    fun `isRooted is true when rootDetector returns true`() = runTest {
        every { rootDetector.isRooted() } returns true

        val viewModel = createViewModel()

        assertTrue(
            "Expected isRooted=true when RootDetector reports root",
            viewModel.uiState.value.isRooted,
        )
    }

    @Test
    fun `isRooted is false when rootDetector returns false`() = runTest {
        every { rootDetector.isRooted() } returns false

        val viewModel = createViewModel()

        assertFalse(
            "Expected isRooted=false when RootDetector reports no root",
            viewModel.uiState.value.isRooted,
        )
    }

    // ── isBiometricAvailable ──────────────────────────────────────────────────

    @Test
    fun `isBiometricAvailable is true when biometricManager returns true`() = runTest {
        every { biometricManager.isAvailable() } returns true

        val viewModel = createViewModel()

        assertTrue(
            "Expected isBiometricAvailable=true when biometrics are available",
            viewModel.uiState.value.isBiometricAvailable,
        )
    }

    @Test
    fun `isBiometricAvailable is false when biometricManager returns false`() = runTest {
        every { biometricManager.isAvailable() } returns false

        val viewModel = createViewModel()

        assertFalse(
            "Expected isBiometricAvailable=false when biometrics are unavailable",
            viewModel.uiState.value.isBiometricAvailable,
        )
    }

    // ── isLoading ─────────────────────────────────────────────────────────────

    @Test
    fun `isLoading is false after security checks complete`() = runTest {
        val viewModel = createViewModel()

        assertFalse(
            "Expected isLoading=false after init completes",
            viewModel.uiState.value.isLoading,
        )
    }

    // ── Combined states ────────────────────────────────────────────────────────

    @Test
    fun `uiState reflects rooted device with no biometrics`() = runTest {
        every { rootDetector.isRooted() } returns true
        every { biometricManager.isAvailable() } returns false

        val viewModel = createViewModel()
        val state = viewModel.uiState.value

        assertTrue("Expected isRooted=true", state.isRooted)
        assertFalse("Expected isBiometricAvailable=false", state.isBiometricAvailable)
        assertFalse("Expected isLoading=false", state.isLoading)
    }

    @Test
    fun `uiState reflects non-rooted device with biometrics available`() = runTest {
        every { rootDetector.isRooted() } returns false
        every { biometricManager.isAvailable() } returns true

        val viewModel = createViewModel()
        val state = viewModel.uiState.value

        assertFalse("Expected isRooted=false", state.isRooted)
        assertTrue("Expected isBiometricAvailable=true", state.isBiometricAvailable)
        assertFalse("Expected isLoading=false", state.isLoading)
    }
}
