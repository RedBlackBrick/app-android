package com.tradingplatform.app.domain.usecase.vpn

import com.tradingplatform.app.vpn.WireGuardManager
import javax.inject.Inject

/**
 * Relance le tunnel intégré depuis la configuration persistée (clés `wg_*` d'`EncryptedDataStore`).
 * Fire-and-forget : l'issue est publiée par [WireGuardManager.state] (`Connecting`, puis
 * `Connected`, `ConsentRequired` ou `Error`). Sans configuration, [WireGuardManager.reconnect]
 * ne fait rien (voir [HasVpnConfigUseCase]).
 */
class ReconnectVpnUseCase @Inject constructor(
    private val wireGuardManager: WireGuardManager,
) {
    operator fun invoke() {
        wireGuardManager.reconnect()
    }
}
