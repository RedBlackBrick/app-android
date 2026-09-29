package com.tradingplatform.app.usecase.vpn

import android.content.Intent
import com.tradingplatform.app.domain.usecase.vpn.GetVpnConsentIntentUseCase
import com.tradingplatform.app.vpn.WireGuardManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class GetVpnConsentIntentUseCaseTest {

    private val wireGuardManager = mockk<WireGuardManager>(relaxed = true)
    private val useCase = GetVpnConsentIntentUseCase(wireGuardManager)

    @Test
    fun `returns the consent intent from the manager when consent is missing`() {
        val intent = mockk<Intent>(relaxed = true)
        every { wireGuardManager.prepareIntent() } returns intent

        assertSame(intent, useCase())
    }

    @Test
    fun `returns null when consent is already granted`() {
        every { wireGuardManager.prepareIntent() } returns null

        assertNull(useCase())
    }
}
