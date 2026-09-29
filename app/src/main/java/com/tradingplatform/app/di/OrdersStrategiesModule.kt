package com.tradingplatform.app.di

import com.tradingplatform.app.data.api.OrdersWriteApi
import com.tradingplatform.app.data.api.StrategiesWriteApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Named
import javax.inject.Singleton

/**
 * API d'écriture des ordres et des liens stratégie, construites sur le Retrofit `@Named("write")`
 * (`WriteNetworkModule` : client OkHttp avec `retryOnConnectionFailure(false)`, donc aucune
 * écriture n'est rejouée automatiquement). Les API de lecture (`OrdersApi`, `StrategiesApi`) et les
 * bindings `OrdersRepository` / `StrategiesRepository` restent fournis par `NetworkModule` /
 * `RepositoryModule`.
 */
@Module
@InstallIn(SingletonComponent::class)
object OrdersStrategiesModule {

    @Provides
    @Singleton
    fun provideOrdersWriteApi(@Named("write") retrofit: Retrofit): OrdersWriteApi =
        retrofit.create(OrdersWriteApi::class.java)

    @Provides
    @Singleton
    fun provideStrategiesWriteApi(@Named("write") retrofit: Retrofit): StrategiesWriteApi =
        retrofit.create(StrategiesWriteApi::class.java)
}
