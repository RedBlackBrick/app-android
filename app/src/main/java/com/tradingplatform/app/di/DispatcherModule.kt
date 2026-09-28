package com.tradingplatform.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier

/**
 * Qualifies [CoroutineDispatcher] injection points that must run blocking I/O
 * (e.g. a hand-built `OkHttpClient.newCall(...).execute()` call, as opposed to
 * a Retrofit suspend function which already dispatches off the caller by
 * itself). See CLAUDE.md §2 "Accès réseau sur le thread principal".
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/**
 * Qualifies [CoroutineDispatcher] injection points for CPU-bound work
 * (JSON/crypto processing, sorting, etc.) that should not run on [IoDispatcher]
 * or the caller's dispatcher. Provided alongside [IoDispatcher] for symmetry;
 * not wired into any class yet.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default
}
