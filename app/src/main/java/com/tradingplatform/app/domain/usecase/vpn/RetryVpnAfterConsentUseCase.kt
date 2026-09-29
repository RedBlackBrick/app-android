package com.tradingplatform.app.domain.usecase.vpn

import com.tradingplatform.app.vpn.WireGuardManager
import javax.inject.Inject

/**
 * Rejoue la connexion qui s'était arrêtée sur `VpnState.ConsentRequired` une fois le consentement
 * accordé (publie `Connecting` de façon synchrone — voir [WireGuardManager.retryAfterConsent]).
 */
class RetryVpnAfterConsentUseCase @Inject constructor(
    private val wireGuardManager: WireGuardManager,
) {
    operator fun invoke() {
        wireGuardManager.retryAfterConsent()
    }
}
