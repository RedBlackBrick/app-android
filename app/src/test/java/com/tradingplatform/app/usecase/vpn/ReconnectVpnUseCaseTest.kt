package com.tradingplatform.app.usecase.vpn

import com.tradingplatform.app.domain.usecase.vpn.ReconnectVpnUseCase
import com.tradingplatform.app.vpn.WireGuardManager
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class ReconnectVpnUseCaseTest {

    private val wireGuardManager = mockk<WireGuardManager>(relaxed = true)

    @Test
    fun `invoke asks the manager to reconnect from the persisted config exactly once`() {
        ReconnectVpnUseCase(wireGuardManager)()

        verify(exactly = 1) { wireGuardManager.reconnect() }
    }
}
