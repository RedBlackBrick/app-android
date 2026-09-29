package com.tradingplatform.app.domain.usecase.vpn

import android.content.Intent
import com.tradingplatform.app.vpn.WireGuardManager
import javax.inject.Inject

/**
 * Intent du dialogue de consentement VPN d'Android (`VpnService.prepare`), ou `null` si l'app est
 * déjà autorisée. À lancer via `ActivityResultContracts.StartActivityForResult()` ; RESULT_OK =
 * consentement accordé (puis [RetryVpnAfterConsentUseCase]).
 */
class GetVpnConsentIntentUseCase @Inject constructor(
    private val wireGuardManager: WireGuardManager,
) {
    operator fun invoke(): Intent? = wireGuardManager.prepareIntent()
}
