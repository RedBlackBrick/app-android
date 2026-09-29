package com.tradingplatform.app.usecase.vpn

import com.tradingplatform.app.domain.usecase.vpn.ObserveVpnStateUseCase
import com.tradingplatform.app.vpn.SystemVpnMonitor
import com.tradingplatform.app.vpn.VpnState
import com.tradingplatform.app.vpn.WireGuardManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ObserveVpnStateUseCase] : combine le tunnel intégré et le VPN système via `displayedVpnState`
 * (même règle que `VpnSettingsViewModel`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveVpnStateUseCaseTest {

    private val wireGuardManager = mockk<WireGuardManager>(relaxed = true)
    private val systemVpnMonitor = mockk<SystemVpnMonitor>(relaxed = true)
    private val scope = CoroutineScope(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun useCase(
        inApp: MutableStateFlow<VpnState>,
        systemActive: MutableStateFlow<Boolean>,
    ): ObserveVpnStateUseCase {
        every { wireGuardManager.state } returns inApp
        every { systemVpnMonitor.active } returns systemActive
        return ObserveVpnStateUseCase(wireGuardManager, systemVpnMonitor, scope)
    }

    @Test
    fun `Disconnected without any system VPN stays Disconnected`() {
        val state = useCase(MutableStateFlow(VpnState.Disconnected), MutableStateFlow(false))()

        assertEquals(VpnState.Disconnected, state.value)
    }

    @Test
    fun `a system VPN while the in-app tunnel is Disconnected gives SystemVpnActive`() {
        val state = useCase(MutableStateFlow(VpnState.Disconnected), MutableStateFlow(true))()

        assertEquals(VpnState.SystemVpnActive, state.value)
    }

    @Test
    fun `Connecting wins over a VPN network already visible`() {
        val state = useCase(MutableStateFlow(VpnState.Connecting), MutableStateFlow(true))()

        assertEquals(VpnState.Connecting, state.value)
    }

    @Test
    fun `in-app Connected is kept over a system VPN`() {
        val state = useCase(MutableStateFlow(VpnState.Connected()), MutableStateFlow(true))()

        assertEquals(VpnState.Connected(), state.value)
    }

    @Test
    fun `ConsentRequired is exposed as is when no system VPN is up`() {
        val state = useCase(MutableStateFlow(VpnState.ConsentRequired), MutableStateFlow(false))()

        assertEquals(VpnState.ConsentRequired, state.value)
    }

    @Test
    fun `follows the in-app tunnel and the system VPN reactively`() {
        val inApp = MutableStateFlow<VpnState>(VpnState.Disconnected)
        val systemActive = MutableStateFlow(false)
        val state = useCase(inApp, systemActive)()
        assertEquals(VpnState.Disconnected, state.value)

        inApp.value = VpnState.Connecting
        assertEquals(VpnState.Connecting, state.value)

        inApp.value = VpnState.Connected()
        assertEquals(VpnState.Connected(), state.value)

        inApp.value = VpnState.Disconnected
        systemActive.value = true
        assertEquals(VpnState.SystemVpnActive, state.value)

        systemActive.value = false
        assertEquals(VpnState.Disconnected, state.value)
    }

    @Test
    fun `first value asks Android when the monitor callback has not reported the system VPN yet`() {
        val systemActive = MutableStateFlow(false)
        // Comme le vrai moniteur : `isActiveNow()` met aussi à jour le flux `active`.
        every { systemVpnMonitor.isActiveNow() } answers { systemActive.value = true; true }

        val state = useCase(MutableStateFlow(VpnState.Disconnected), systemActive)()

        assertEquals(VpnState.SystemVpnActive, state.value)
    }
}
