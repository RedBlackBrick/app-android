package com.tradingplatform.app.domain.repository

/**
 * Persists the mobile onboarding (Setup) completion flag.
 *
 * [SetupViewModel] must not touch `EncryptedDataStore` directly (CLAUDE.md §2 — a `ViewModel`
 * accessing a data-layer component without going through a `UseCase`/`Repository` is forbidden).
 */
interface SetupRepository {
    /** Marks the mobile onboarding as completed — persisted so it survives process death. */
    suspend fun markSetupCompleted()

    /** Reads the onboarding completion flag. `false` if never written or unreadable (corruption). */
    suspend fun isSetupCompleted(): Boolean
}
