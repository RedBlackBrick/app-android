package com.tradingplatform.app.widget

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.tradingplatform.app.domain.repository.AdminWidgetVisibilityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android implementation of [AdminWidgetVisibilityManager].
 *
 * Uses [PackageManager.setComponentEnabledSetting] to show/hide admin-only widget
 * receivers in the launcher's widget picker.
 */
@Singleton
class AdminWidgetVisibilityManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : AdminWidgetVisibilityManager {

    override suspend fun applyVisibility(isAdmin: Boolean) {
        val state = if (isAdmin)
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, SystemStatusWidgetReceiver::class.java),
            state,
            PackageManager.DONT_KILL_APP,
        )
    }
}
