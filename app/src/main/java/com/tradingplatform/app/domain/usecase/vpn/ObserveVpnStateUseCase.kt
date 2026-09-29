package com.tradingplatform.app.domain.usecase.vpn

import com.tradingplatform.app.vpn.SystemVpnMonitor
import com.tradingplatform.app.vpn.VpnState
import com.tradingplatform.app.vpn.WireGuardManager
import com.tradingplatform.app.vpn.displayedVpnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * État VPN **affiché** par l'app : tunnel intégré ([WireGuardManager.state]) combiné au VPN
 * système tiers ([SystemVpnMonitor.active]) via [displayedVpnState] — même règle que
 * `VpnSettingsViewModel` (le tunnel intégré `Connected`/`Connecting` prime ; sinon un VPN système
 * actif donne [VpnState.SystemVpnActive]).
 *
 * `@Singleton` : le flux partagé est construit une seule fois sur le scope applicatif
 * (`AppModule.provideApplicationScope()`), quel que soit le nombre de ViewModels qui l'observent.
 * `Eagerly` garde `.value` à jour même sans collecteur (lecture ponctuelle au moment d'une erreur).
 */
@Singleton
class ObserveVpnStateUseCase @Inject constructor(
    private val wireGuardManager: WireGuardManager,
    private val systemVpnMonitor: SystemVpnMonitor,
    private val applicationScope: CoroutineScope,
) {
    private val shared: StateFlow<VpnState> by lazy {
        combine(wireGuardManager.state, systemVpnMonitor.active) { inApp, systemActive ->
            displayedVpnState(inApp, systemActive)
        }.stateIn(
            scope = applicationScope,
            started = SharingStarted.Eagerly,
            // Même règle que le flux, appliquée dès la 1re lecture : le callback du moniteur peut
            // ne pas avoir encore rapporté un VPN système déjà monté.
            initialValue = displayedVpnState(
                wireGuardManager.state.value,
                systemVpnMonitor.active.value || systemVpnMonitor.isActiveNow(),
            ),
        )
    }

    operator fun invoke(): StateFlow<VpnState> = shared
}
