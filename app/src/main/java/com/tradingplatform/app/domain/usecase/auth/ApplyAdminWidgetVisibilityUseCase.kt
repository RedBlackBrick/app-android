package com.tradingplatform.app.domain.usecase.auth

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.widget.SystemStatusWidgetReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class ApplyAdminWidgetVisibilityUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: EncryptedDataStore,
) {
    suspend operator fun invoke() {
        val isAdmin = dataStore.readBoolean(DataStoreKeys.IS_ADMIN) ?: false
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
