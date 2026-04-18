package com.tradingplatform.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.domain.model.User
import com.tradingplatform.app.domain.usecase.auth.GetUserProfileUseCase
import com.tradingplatform.app.domain.usecase.market.GetDefaultQuoteSymbolUseCase
import com.tradingplatform.app.domain.usecase.market.SetDefaultQuoteSymbolUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ProfileUiState {
    data object Loading : ProfileUiState
    data class Success(val user: User) : ProfileUiState
    data class Error(val message: String) : ProfileUiState
}

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val getUserProfileUseCase: GetUserProfileUseCase,
    private val getDefaultQuoteSymbolUseCase: GetDefaultQuoteSymbolUseCase,
    private val setDefaultQuoteSymbolUseCase: SetDefaultQuoteSymbolUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ProfileUiState>(ProfileUiState.Loading)
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    private val _defaultQuoteSymbol = MutableStateFlow("")
    val defaultQuoteSymbol: StateFlow<String> = _defaultQuoteSymbol.asStateFlow()

    init {
        loadProfile()
        loadDefaultQuoteSymbol()
    }

    fun refresh() {
        loadProfile()
        loadDefaultQuoteSymbol()
    }

    /**
     * Persiste le symbole par défaut saisi par l'utilisateur.
     * Symbole vide → retour au fallback watchlist / AppDefaults.
     */
    fun updateDefaultQuoteSymbol(symbol: String) {
        viewModelScope.launch {
            setDefaultQuoteSymbolUseCase(symbol)
                .onSuccess { _defaultQuoteSymbol.value = symbol.uppercase().trim() }
        }
    }

    private fun loadProfile() {
        viewModelScope.launch {
            _uiState.update { ProfileUiState.Loading }
            getUserProfileUseCase()
                .onSuccess { user ->
                    _uiState.update { ProfileUiState.Success(user) }
                }
                .onFailure { e ->
                    _uiState.update {
                        ProfileUiState.Error(e.localizedMessage ?: "Erreur")
                    }
                }
        }
    }

    private fun loadDefaultQuoteSymbol() {
        viewModelScope.launch {
            _defaultQuoteSymbol.value = getDefaultQuoteSymbolUseCase()
        }
    }
}
