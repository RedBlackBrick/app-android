package com.tradingplatform.app.di

import com.tradingplatform.app.data.repository.AlertRepositoryImpl
import com.tradingplatform.app.data.repository.AuthRepositoryImpl
import com.tradingplatform.app.data.repository.BrokerConnectionRepositoryImpl
import com.tradingplatform.app.data.repository.DeviceRepositoryImpl
import com.tradingplatform.app.data.repository.MyDevicesRepositoryImpl
import com.tradingplatform.app.data.repository.MarketDataRepositoryImpl
import com.tradingplatform.app.data.repository.MobileProvisioningRepositoryImpl
import com.tradingplatform.app.data.repository.NotificationRepositoryImpl
import com.tradingplatform.app.data.repository.PairingRepositoryImpl
import com.tradingplatform.app.data.repository.PortfolioRepositoryImpl
import com.tradingplatform.app.data.repository.RiskRepositoryImpl
import com.tradingplatform.app.data.repository.StrategiesRepositoryImpl
import com.tradingplatform.app.data.repository.WatchlistRepositoryImpl
import com.tradingplatform.app.domain.repository.AdminWidgetVisibilityManager
import com.tradingplatform.app.domain.repository.AlertRepository
import com.tradingplatform.app.domain.repository.AuthRepository
import com.tradingplatform.app.domain.repository.BrokerConnectionRepository
import com.tradingplatform.app.domain.repository.DeviceRepository
import com.tradingplatform.app.domain.repository.MyDevicesRepository
import com.tradingplatform.app.domain.repository.MarketDataRepository
import com.tradingplatform.app.domain.repository.MobileProvisioningRepository
import com.tradingplatform.app.domain.repository.NotificationRepository
import com.tradingplatform.app.domain.repository.PairingRepository
import com.tradingplatform.app.domain.repository.PortfolioRepository
import com.tradingplatform.app.domain.repository.RiskRepository
import com.tradingplatform.app.domain.repository.StrategiesRepository
import com.tradingplatform.app.domain.repository.WatchlistRepository
import com.tradingplatform.app.widget.AdminWidgetVisibilityManagerImpl
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
    abstract fun bindMyDevicesRepository(impl: MyDevicesRepositoryImpl): MyDevicesRepository

    @Binds
    @Singleton
    abstract fun bindAlertRepository(impl: AlertRepositoryImpl): AlertRepository

    @Binds
    @Singleton
    abstract fun bindPairingRepository(impl: PairingRepositoryImpl): PairingRepository

    @Binds
    @Singleton
    abstract fun bindNotificationRepository(impl: NotificationRepositoryImpl): NotificationRepository

    @Binds
    @Singleton
    abstract fun bindWatchlistRepository(impl: WatchlistRepositoryImpl): WatchlistRepository

    @Binds
    @Singleton
    abstract fun bindBrokerConnectionRepository(impl: BrokerConnectionRepositoryImpl): BrokerConnectionRepository

    @Binds
    @Singleton
    abstract fun bindMobileProvisioningRepository(
        impl: MobileProvisioningRepositoryImpl,
    ): MobileProvisioningRepository

    @Binds
    @Singleton
    abstract fun bindStrategiesRepository(impl: StrategiesRepositoryImpl): StrategiesRepository

    @Binds
    @Singleton
    abstract fun bindRiskRepository(impl: RiskRepositoryImpl): RiskRepository

    @Binds
    @Singleton
    abstract fun bindAdminWidgetVisibilityManager(impl: AdminWidgetVisibilityManagerImpl): AdminWidgetVisibilityManager
}
