package com.tradingplatform.app.di

import com.squareup.moshi.Moshi
import com.squareup.moshi.adapters.EnumJsonAdapter
import com.tradingplatform.app.BuildConfig
import com.tradingplatform.app.data.api.AuthApi
import com.tradingplatform.app.data.api.DeviceApi
import com.tradingplatform.app.data.api.LocalMaintenanceApi
import com.tradingplatform.app.data.api.MarketDataApi
import com.tradingplatform.app.data.api.MyDevicesApi
import com.tradingplatform.app.data.api.NotificationApi
import com.tradingplatform.app.data.api.PairingLanApi
import com.tradingplatform.app.data.api.PortfolioApi
import com.tradingplatform.app.data.api.interceptor.AuthInterceptor
import com.tradingplatform.app.data.api.interceptor.CsrfInterceptor
import com.tradingplatform.app.data.api.interceptor.EncryptedCookieJar
import com.tradingplatform.app.data.api.interceptor.TokenAuthenticator
import com.tradingplatform.app.data.api.interceptor.VpnRequiredInterceptor
import com.tradingplatform.app.data.model.BigDecimalAdapter
import com.tradingplatform.app.data.model.InstantAdapter
import com.tradingplatform.app.domain.model.DeviceStatus
import com.tradingplatform.app.domain.model.PositionStatus
import com.tradingplatform.app.security.CertificatePinnerProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * Extrait le hostname depuis VPS_BASE_URL pour le certificate pinning.
     * Exemples :
     *   "https://10.42.0.1:443" → "10.42.0.1"
     *   "https://vps.example.com:443" → "vps.example.com"
     */
    private val VPS_HOSTNAME: String
        get() = BuildConfig.VPS_BASE_URL
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore(":")
            .substringBefore("/")

    // ── Base URL ──────────────────────────────────────────────────────────────

    @Provides
    @Singleton
    @Named("base_url")
    fun provideBaseUrl(): String = BuildConfig.VPS_BASE_URL

    // ── Moshi ─────────────────────────────────────────────────────────────────

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .add(BigDecimalAdapter())
        .add(InstantAdapter())
        .add(PositionStatus::class.java, EnumJsonAdapter.create(PositionStatus::class.java)
            .withUnknownFallback(PositionStatus.OPEN))
        .add(DeviceStatus::class.java, EnumJsonAdapter.create(DeviceStatus::class.java)
            .withUnknownFallback(DeviceStatus.OFFLINE))
        .build()

    // ── OkHttpClient @Named("bare") ────────────────────────────────────────────
    // Utilisé par CsrfInterceptor pour fetcher /csrf-token.
    // Évite la dépendance circulaire : OkHttpClient → CsrfInterceptor → AuthApi → OkHttpClient.
    // Timeouts courts (5s/5s) — un VPS lent ne doit pas bloquer un thread OkHttp indéfiniment.

    @Provides
    @Singleton
    @Named("bare")
    fun provideBareOkHttpClient(
        certPinnerProvider: CertificatePinnerProvider,
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
        certPinnerProvider.buildCertificatePinner(VPS_HOSTNAME)
            ?.let { builder.certificatePinner(it) }
        return builder.build()
    }

    // ── OkHttpClient principal ─────────────────────────────────────────────────
    // Chaîne d'intercepteurs (ordre obligatoire CLAUDE.md §3) :
    // CSRF → VPN → Auth → (TokenAuthenticator) → Logging

    @Provides
    @Singleton
    fun provideMainOkHttpClient(
        csrfInterceptor: CsrfInterceptor,
        vpnRequiredInterceptor: VpnRequiredInterceptor,
        authInterceptor: AuthInterceptor,
        tokenAuthenticator: TokenAuthenticator,
        encryptedCookieJar: EncryptedCookieJar,
        certPinnerProvider: CertificatePinnerProvider,
    ): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
            // Masquer les tokens sensibles dans les logs — jamais en clair (CLAUDE.md §1)
            redactHeader("Authorization")
            redactHeader("X-CSRF-Token")
        }

        val builder = OkHttpClient.Builder()
            .addInterceptor(csrfInterceptor)
            .addInterceptor(vpnRequiredInterceptor)
            .addInterceptor(authInterceptor)
            .authenticator(tokenAuthenticator)
            .addInterceptor(loggingInterceptor)
            .cookieJar(encryptedCookieJar)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
        certPinnerProvider.buildCertificatePinner(VPS_HOSTNAME)
            ?.let { builder.certificatePinner(it) }
        return builder.build()
    }

    // ── OkHttpClient @Named("lan") ─────────────────────────────────────────────
    // Utilisé par PairingRepositoryImpl pour les appels HTTP vers la Radxa (LAN, port 8099).
    // Aucun intercepteur VPS : pas de CSRF, Auth, ni VPN check.
    // La validation isLocalNetwork() est effectuée dans PairingRepositoryImpl.
    // HTTP non chiffré accepté : LAN uniquement, TTL 120s, 3 tentatives VPS (CLAUDE.md §8).

    @Provides
    @Singleton
    @Named("lan")
    fun provideLanOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        // Pas de CertificatePinner — HTTP non chiffré (LAN local uniquement)
        // Pas d'intercepteurs VPS (CSRF, Auth, VPN)
        .build()

    // ── Retrofit principal (VPS) ───────────────────────────────────────────────

    @Provides
    @Singleton
    fun provideRetrofit(
        @Named("base_url") baseUrl: String,
        okHttpClient: OkHttpClient,
        moshi: Moshi,
    ): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    // ── Retrofit LAN (Radxa pairing) ───────────────────────────────────────────
    // Base URL de fallback "http://localhost/" — chaque appel fournit une @Url complète.

    @Provides
    @Singleton
    @Named("lan")
    fun provideLanRetrofit(
        @Named("lan") lanOkHttpClient: OkHttpClient,
        moshi: Moshi,
    ): Retrofit = Retrofit.Builder()
        .baseUrl("http://localhost/")
        .client(lanOkHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    // ── APIs Retrofit ──────────────────────────────────────────────────────────

    @Provides
    @Singleton
    fun provideAuthApi(retrofit: Retrofit): AuthApi =
        retrofit.create(AuthApi::class.java)

    @Provides
    @Singleton
    fun providePortfolioApi(retrofit: Retrofit): PortfolioApi =
        retrofit.create(PortfolioApi::class.java)

    @Provides
    @Singleton
    fun provideMarketDataApi(retrofit: Retrofit): MarketDataApi =
        retrofit.create(MarketDataApi::class.java)

    @Provides
    @Singleton
    fun provideDeviceApi(retrofit: Retrofit): DeviceApi =
        retrofit.create(DeviceApi::class.java)

    @Provides
    @Singleton
    fun provideMyDevicesApi(retrofit: Retrofit): MyDevicesApi =
        retrofit.create(MyDevicesApi::class.java)

    @Provides
    @Singleton
    @Named("lan")
    fun providePairingLanApi(@Named("lan") lanRetrofit: Retrofit): PairingLanApi =
        lanRetrofit.create(PairingLanApi::class.java)

    @Provides
    @Singleton
    @Named("lan")
    fun provideLocalMaintenanceApi(@Named("lan") lanRetrofit: Retrofit): LocalMaintenanceApi =
        lanRetrofit.create(LocalMaintenanceApi::class.java)

    @Provides
    @Singleton
    fun provideNotificationApi(retrofit: Retrofit): NotificationApi =
        retrofit.create(NotificationApi::class.java)
}
