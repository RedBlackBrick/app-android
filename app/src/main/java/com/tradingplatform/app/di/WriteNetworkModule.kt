package com.tradingplatform.app.di

import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import javax.inject.Named
import javax.inject.Singleton

/**
 * Infrastructure réseau des ÉCRITURES (annuler un ordre, pause d'un lien stratégie, kill switch
 * portefeuille, préférences push) — voir `docs/write-actions.md`.
 *
 * Une écriture ne doit **jamais** être rejouée par la pile réseau : si la connexion casse après
 * l'envoi de la requête, le serveur a peut-être déjà agi. OkHttp rejoue par défaut une requête sur
 * un échec de connexion (`retryOnConnectionFailure = true`) — y compris un `POST` — ce qui
 * transformerait un doute réseau en double exécution. Le client `@Named("write")` désactive donc ce
 * rejeu ; l'appelant traite l'échec comme « demandé, non confirmé » et relit l'état.
 *
 * Le client `write` est dérivé du client principal via `newBuilder()` : mêmes intercepteurs, dans le
 * même ordre (Timeout → UpgradeRequired → CSRF → VPN → Auth → logger debug), même `Authenticator`,
 * `CookieJar`, cache, pool de connexions, dispatcher, certificate pinning et timeouts. Seul le rejeu
 * sur échec de connexion change. Le refresh de token sur 401 et le retry CSRF sur 403 restent actifs :
 * ce sont des refus AVANT exécution côté serveur (requête non traitée), donc sans risque de doublon.
 *
 * Les interfaces Retrofit d'écriture (`OrdersWriteApi`, `StrategiesWriteApi`, `RiskWriteApi`,
 * `PreferencesWriteApi`) se construisent depuis `@Named("write") Retrofit`, jamais depuis le
 * `Retrofit` non qualifié.
 */
@Module
@InstallIn(SingletonComponent::class)
object WriteNetworkModule {

    /** Client principal + `retryOnConnectionFailure(false)`. Testable sans Hilt. */
    internal fun buildWriteClient(base: OkHttpClient): OkHttpClient =
        base.newBuilder()
            .retryOnConnectionFailure(false)
            .build()

    /** Même construction que `NetworkModule.provideRetrofit`, sur le client d'écriture. */
    internal fun buildWriteRetrofit(
        baseUrl: String,
        writeClient: OkHttpClient,
        moshi: Moshi,
    ): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(writeClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    /**
     * [base] = le `OkHttpClient` NON qualifié, c'est-à-dire le client principal de
     * `NetworkModule.provideMainOkHttpClient` avec toute sa chaîne d'intercepteurs.
     */
    @Provides
    @Singleton
    @Named("write")
    fun provideWriteOkHttpClient(base: OkHttpClient): OkHttpClient = buildWriteClient(base)

    @Provides
    @Singleton
    @Named("write")
    fun provideWriteRetrofit(
        @Named("base_url") baseUrl: String,
        @Named("write") writeClient: OkHttpClient,
        moshi: Moshi,
    ): Retrofit = buildWriteRetrofit(baseUrl, writeClient, moshi)
}
