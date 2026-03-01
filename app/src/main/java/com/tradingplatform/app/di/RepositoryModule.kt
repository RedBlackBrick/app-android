package com.tradingplatform.app.di

import com.tradingplatform.app.data.repository.AlertRepositoryImpl
import com.tradingplatform.app.data.repository.AuthRepositoryImpl
import com.tradingplatform.app.data.repository.DeviceRepositoryImpl
import com.tradingplatform.app.data.repository.MarketDataRepositoryImpl
import com.tradingplatform.app.data.repository.PairingRepositoryImpl
import com.tradingplatform.app.data.repository.PortfolioRepositoryImpl
import com.tradingplatform.app.domain.repository.AlertRepository
import com.tradingplatform.app.domain.repository.AuthRepository
import com.tradingplatform.app.domain.repository.DeviceRepository
import com.tradingplatform.app.domain.repository.MarketDataRepository
import com.tradingplatform.app.domain.repository.PairingRepository
import com.tradingplatform.app.domain.repository.PortfolioRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    @Singleton
    abstract fun bindPortfolioRepository(impl: PortfolioRepositoryImpl): PortfolioRepository

    @Binds
    @Singleton
    abstract fun bindMarketDataRepository(impl: MarketDataRepositoryImpl): MarketDataRepository

    @Binds
    @Singleton
    abstract fun bindDeviceRepository(impl: DeviceRepositoryImpl): DeviceRepository

    @Binds
    @Singleton
    abstract fun bindAlertRepository(impl: AlertRepositoryImpl): AlertRepository

    @Binds
    @Singleton
    abstract fun bindPairingRepository(impl: PairingRepositoryImpl): PairingRepository
}
