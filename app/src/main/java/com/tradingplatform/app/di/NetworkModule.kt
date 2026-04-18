package com.tradingplatform.app.di

import com.squareup.moshi.Moshi
import com.tradingplatform.app.BuildConfig
import com.tradingplatform.app.data.api.AuthApi
import com.tradingplatform.app.data.api.BrokerConnectionApi
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
import com.tradingplatform.app.data.api.interceptor.TimeoutInterceptor
import com.tradingplatform.app.data.api.interceptor.TokenAuthenticator
import com.tradingplatform.app.data.api.interceptor.UpgradeRequiredInterceptor
import com.tradingplatform.app.data.api.interceptor.VpnRequiredInterceptor
import com.tradingplatform.app.data.model.BigDecimalAdapter
import com.tradingplatform.app.data.model.InstantAdapter
import android.content.Context
import com.tradingplatform.app.security.CertificatePinnerProvider
import com.tradingplatform.app.security.LanTrustManager
import com.tradingplatform.app.security.isLocalNetwork
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import javax.net.ssl.HostnameVerifier
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
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
    // Chaîne d'intercepteurs (ordre obligatoire CLAUDE.md §3 + fix 426) :
    // Timeout → UpgradeRequired → CSRF → VPN → Auth → (TokenAuthenticator) → Logging
    // Timeout est en première position pour appliquer les timeouts par endpoint avant toute exécution.

    @Provides
    @Singleton
    fun provideMainOkHttpClient(
        @ApplicationContext context: Context,
        timeoutInterceptor: TimeoutInterceptor,
        upgradeRequiredInterceptor: UpgradeRequiredInterceptor,
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

        val cache = Cache(
            directory = File(context.cacheDir, "http_cache"),
            maxSize = 10L * 1024L * 1024L,
        )

        val builder = OkHttpClient.Builder()
            .cache(cache)
            .addInterceptor(timeoutInterceptor)
            .addInterceptor(upgradeRequiredInterceptor)
            .addInterceptor(csrfInterceptor)
            .addInterceptor(vpnRequiredInterceptor)
            .addInterceptor(authInterceptor)
            .authenticator(tokenAuthenticator)
            .addInterceptor(loggingInterceptor)
            .cookieJar(encryptedCookieJar)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
        certPinnerProvider.buildCertificatePinner(VPS_HOSTNAME)
            ?.let { builder.certificatePinner(it) }
        return builder.build()
    }

    // ── OkHttpClient @Named("lan") ─────────────────────────────────────────────
    // Utilisé par PairingRepositoryImpl et LocalMaintenanceRepositoryImpl pour
    // parler au pairing-server de la Radxa (LAN, port 8099, HTTPS avec cert
    // auto-signé). Le pairing-server refuse désormais de démarrer sans TLS —
    // d'où le switch http → https et le TrustManager permissif scopé LAN.
    //
    // Garde-fou anti-fuite (LanOnlyHttpsGuard) : avant chaque requête, on
    // refuse immédiatement toute URL qui n'est ni HTTPS ni RFC-1918. Ça limite
    // strictement le TrustManager permissif à sa surface légitime (8099 sur
    // le LAN du foyer) et empêche qu'il soit réutilisé par erreur pour un
    // autre host.
    //
    // Pas de CSRF ni Auth interceptors (LAN, pas VPS). VpnRequiredInterceptor
    // est inclus : la connexion vers radxa_ip:8099 exige VPN actif (CLAUDE.md
    // §8). La validation isLocalNetwork() reste effectuée en Repository.

    @Provides
    @Singleton
    @Named("lan")
    fun provideLanOkHttpClient(
        vpnRequiredInterceptor: VpnRequiredInterceptor,
    ): OkHttpClient {
        val (sslSocketFactory, trustManager) = LanTrustManager.socketFactory()

        // Le cert auto-signé est émis pour le hostname du Radxa (CN=radxa-…)
        // mais les clients s'y connectent par IP RFC-1918 — on accepte tant que
        // l'IP est site-local/link-local. La validation protocole/IP est faite
        // par lanOnlyHttpsGuard avant même que la socket soit ouverte.
        val lanHostnameVerifier = HostnameVerifier { hostname, _ -> isLocalNetwork(hostname) }

        return OkHttpClient.Builder()
            .addInterceptor(lanOnlyHttpsGuard())
            .addInterceptor(vpnRequiredInterceptor)
            .sslSocketFactory(sslSocketFactory, trustManager)
            .hostnameVerifier(lanHostnameVerifier)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Refuse toute requête non-HTTPS ou qui cible un hôte non RFC-1918.
     * Confine le TrustManager permissif au seul cas d'usage légitime
     * (pairing-server Radxa sur LAN 8099 via WireGuard).
     */
    private fun lanOnlyHttpsGuard(): Interceptor = Interceptor { chain ->
        val url: HttpUrl = chain.request().url
        if (!url.isHttps || !isLocalNetwork(url.host)) {
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(495)  // 495 Invalid SSL Certificate — repurposed to signal policy break
                .message("LAN client refused non-HTTPS or non-RFC-1918 target (${url.host})")
                .body(okhttp3.ResponseBody.create(null, ByteArray(0)))
                .build()
        } else {
            chain.proceed(chain.request())
        }
    }

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

    @Provides
    @Singleton
    fun provideBrokerConnectionApi(retrofit: Retrofit): BrokerConnectionApi =
        retrofit.create(BrokerConnectionApi::class.java)
}
