package com.tradingplatform.app.di

import android.content.Context
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.security.BiometricManager
import com.tradingplatform.app.security.CertificatePinnerProvider
import com.tradingplatform.app.security.KeystoreManager
import com.tradingplatform.app.security.RootDetector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {

    @Provides
    @Singleton
    fun provideKeystoreManager(): KeystoreManager = KeystoreManager()

    @Provides
    @Singleton
    fun provideBiometricManager(
        @ApplicationContext context: Context,
        keystoreManager: KeystoreManager,
    ): BiometricManager = BiometricManager(context, keystoreManager)

    @Provides
    @Singleton
    fun provideRootDetector(
        @ApplicationContext context: Context,
    ): RootDetector = RootDetector(context)

    @Provides
    @Singleton
    fun provideCertificatePinnerProvider(): CertificatePinnerProvider = CertificatePinnerProvider()

    @Provides
    @Singleton
    fun provideEncryptedDataStore(
        @ApplicationContext context: Context,
    ): EncryptedDataStore = EncryptedDataStore(context)
}
