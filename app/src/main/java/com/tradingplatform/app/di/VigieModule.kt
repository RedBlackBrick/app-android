package com.tradingplatform.app.di

import com.tradingplatform.app.data.api.VigieApi
import com.tradingplatform.app.data.repository.VigieRepositoryImpl
import com.tradingplatform.app.domain.repository.VigieRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

/**
 * Vigie « Refuge » : `VigieApi` sur le `Retrofit` normal (lecture seule) et binding du repository.
 * Module dédié (comme `InboxRiskModule`) pour ne pas toucher `NetworkModule` / `RepositoryModule`.
 */
@Module
@InstallIn(SingletonComponent::class)
object VigieApiModule {

    @Provides
    @Singleton
    fun provideVigieApi(retrofit: Retrofit): VigieApi = retrofit.create(VigieApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class VigieBindingsModule {

    @Binds
    @Singleton
    abstract fun bindVigieRepository(impl: VigieRepositoryImpl): VigieRepository
}
