package com.tradingplatform.app.domain.repository

/**
 * Abstraction for controlling the visibility of admin-only widgets in the launcher.
 *
 * Defined in the domain layer so that [ApplyAdminWidgetVisibilityUseCase] does not
 * depend on Android APIs (PackageManager, ComponentName) or the data layer directly.
 *
 * Implementation lives in the data/widget layer and uses PackageManager to
 * enable/disable widget receivers.
 */
interface AdminWidgetVisibilityManager {

    /**
     * Enables or disables admin-only widget components based on the [isAdmin] flag.
     *
     * When [isAdmin] is false, admin widgets (e.g., SystemStatusWidget) are disabled
     * in the launcher — they do not appear in the widget picker.
     */
    suspend fun applyVisibility(isAdmin: Boolean)
}
