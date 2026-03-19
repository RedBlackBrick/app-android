package com.tradingplatform.app.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gère l'état du verrou biométrique d'inactivité (CLAUDE.md §4).
 * Singleton partagé entre MainActivity (qui lock) et AppNavViewModel (qui expose l'état à l'UI).
 */
@Singleton
class BiometricLockManager @Inject constructor() {
    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    fun lock() {
        _isLocked.value = true
    }

    fun unlock() {
        _isLocked.value = false
    }
}
