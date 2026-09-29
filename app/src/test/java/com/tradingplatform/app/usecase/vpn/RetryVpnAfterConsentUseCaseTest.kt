package com.tradingplatform.app.usecase.vpn

import com.tradingplatform.app.domain.usecase.vpn.RetryVpnAfterConsentUseCase
import com.tradingplatform.app.vpn.WireGuardManager
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class RetryVpnAfterConsentUseCaseTest {

    private val wireGuardManager = mockk<WireGuardManager>(relaxed = true)

    @Test
    fun `invoke replays the connection stopped on ConsentRequired exactly once`() {
        RetryVpnAfterConsentUseCase(wireGuardManager)()

        verify(exactly = 1) { wireGuardManager.retryAfterConsent() }
    }
}
