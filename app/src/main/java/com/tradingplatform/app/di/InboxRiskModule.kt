package com.tradingplatform.app.di

import com.tradingplatform.app.data.api.PreferencesApi
import com.tradingplatform.app.data.api.PreferencesWriteApi
import com.tradingplatform.app.data.api.RiskWriteApi
import com.tradingplatform.app.data.repository.InboxRepositoryImpl
import com.tradingplatform.app.data.repository.NotificationPreferencesRepositoryImpl
import com.tradingplatform.app.domain.repository.InboxRepository
import com.tradingplatform.app.domain.repository.NotificationPreferencesRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Named
import javax.inject.Singleton

/**
 * Notifications serveur (boîte de réception), préférences push et risque. Modules dédiés pour ne
 * pas toucher [NetworkModule] / [RepositoryModule] (partagés) :
 *
 * - [InboxRiskModule] fournit les API : `PreferencesApi` (lecture) sur le `Retrofit` normal ;
 *   `PreferencesWriteApi` et `RiskWriteApi` (écritures) sur `@Named("write") Retrofit`
 *   (`WriteNetworkModule`, `retryOnConnectionFailure(false)`) — jamais rejouées.
 * - [InboxRiskBindingsModule] lie les nouveaux repositories.
 *
 * `NotificationApi`, `RiskApi` et le binding `RiskRepository` restent fournis par `NetworkModule` /
 * `RepositoryModule` ; `RiskRepositoryImpl` reçoit simplement `RiskWriteApi` en plus.
 */
@Module
@InstallIn(SingletonComponent::class)
object InboxRiskModule {

    @Provides
    @Singleton
    fun providePreferencesApi(retrofit: Retrofit): PreferencesApi =
        retrofit.create(PreferencesApi::class.java)

    @Provides
    @Singleton
    fun providePreferencesWriteApi(@Named("write") retrofit: Retrofit): PreferencesWriteApi =
        retrofit.create(PreferencesWriteApi::class.java)

    @Provides
    @Singleton
    fun provideRiskWriteApi(@Named("write") retrofit: Retrofit): RiskWriteApi =
        retrofit.create(RiskWriteApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class InboxRiskBindingsModule {

    @Binds
    @Singleton
    abstract fun bindInboxRepository(impl: InboxRepositoryImpl): InboxRepository

    @Binds
    @Singleton
    abstract fun bindNotificationPreferencesRepository(
        impl: NotificationPreferencesRepositoryImpl,
    ): NotificationPreferencesRepository
}
