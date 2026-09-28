package com.tradingplatform.app.domain.usecase.setup

import com.tradingplatform.app.domain.repository.SetupRepository
import javax.inject.Inject

/**
 * Marks the mobile onboarding (WireGuard QR setup) as completed.
 *
 * Called by [com.tradingplatform.app.ui.screens.setup.SetupViewModel] once
 * [com.tradingplatform.app.vpn.VpnState.Connected] is observed after provisioning — see
 * CLAUDE.md §2 (`ViewModel` must go through a `UseCase`, never touch `EncryptedDataStore`
 * directly).
 */
class MarkSetupCompletedUseCase @Inject constructor(
    private val repository: SetupRepository,
) {
    suspend operator fun invoke() {
        repository.markSetupCompleted()
    }
}
