package com.tradingplatform.app.di

import com.tradingplatform.app.data.repository.PortfolioSelectionRepositoryImpl
import com.tradingplatform.app.domain.repository.PortfolioSelectionRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Lie [PortfolioSelectionRepository] (portefeuille actif, multi-portefeuille). Module dédié pour
 * ne pas toucher [RepositoryModule] (partagé). `PositionDao`, `PnlDao`, `AuthApi`,
 * `EncryptedDataStore`, `SessionManager` et le `CoroutineScope` applicatif viennent des modules
 * existants (`DatabaseModule`, `NetworkModule`, `SecurityModule`, `AppModule`).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PortfolioSelectionModule {

    @Binds
    @Singleton
    abstract fun bindPortfolioSelectionRepository(
        impl: PortfolioSelectionRepositoryImpl,
    ): PortfolioSelectionRepository
}
