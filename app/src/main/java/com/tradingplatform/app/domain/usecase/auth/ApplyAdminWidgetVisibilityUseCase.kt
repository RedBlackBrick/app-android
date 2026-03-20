package com.tradingplatform.app.domain.usecase.auth

import com.tradingplatform.app.domain.repository.AdminWidgetVisibilityManager
import javax.inject.Inject

/**
 * Applies admin widget visibility based on the user's admin status.
 *
 * Delegates to [AdminWidgetVisibilityManager] — the domain layer defines the
 * interface, the widget/data layer provides the Android implementation.
 *
 * Pure Kotlin UseCase — no Android API dependency.
 */
class ApplyAdminWidgetVisibilityUseCase @Inject constructor(
    private val visibilityManager: AdminWidgetVisibilityManager,
) {
    /**
     * @param isAdmin true to enable admin-only widgets, false to disable them.
     */
    suspend operator fun invoke(isAdmin: Boolean) {
        visibilityManager.applyVisibility(isAdmin)
    }
}
