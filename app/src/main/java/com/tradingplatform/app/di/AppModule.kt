package com.tradingplatform.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Provides an application-scoped CoroutineScope.
     * Uses SupervisorJob so that child coroutine failures don't cancel siblings.
     * Used by WireGuardManager, TokenAuthenticator, and other singletons
     * that need a scope independent of any ViewModel or Activity lifecycle.
     */
    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
