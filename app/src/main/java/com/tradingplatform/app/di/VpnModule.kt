package com.tradingplatform.app.di

import android.content.Context
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.vpn.WireGuardManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object VpnModule {

    @Provides
    @Singleton
    fun provideWireGuardManager(
        @ApplicationContext context: Context,
        applicationScope: CoroutineScope,
        dataStore: EncryptedDataStore,
    ): WireGuardManager = WireGuardManager(context, applicationScope, dataStore)
}
