package com.tradingplatform.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.data.session.SessionManager
import com.tradingplatform.app.domain.usecase.auth.LogoutUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val logoutUseCase: LogoutUseCase,
    private val sessionManager: SessionManager,
) : ViewModel() {

    fun logout() {
        viewModelScope.launch {
            logoutUseCase()
                .onFailure { Timber.w(it, "SettingsViewModel: logout API failed — local state cleared anyway") }
            // Déclenche la navigation vers LoginScreen via AppNavViewModel.isLoggedIn.
            sessionManager.notifyForcedLogout()
        }
    }
}
