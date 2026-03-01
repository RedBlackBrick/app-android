package com.tradingplatform.app.data.api.interceptor

import com.tradingplatform.app.BuildConfig
import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Injecte :
 * - Authorization: Bearer <access_token> (depuis EncryptedDataStore)
 * - X-App-Version: {versionCode} (pour détection upgrade requis 426)
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val dataStore: EncryptedDataStore,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = runBlocking {
            dataStore.readString(DataStoreKeys.ACCESS_TOKEN)
        }

        val requestBuilder = chain.request().newBuilder()
            .header("X-App-Version", BuildConfig.VERSION_CODE.toString())

        if (token != null) {
            requestBuilder.header("Authorization", "Bearer $token")
        } else {
            Timber.d("AuthInterceptor: no access token available")
        }

        return chain.proceed(requestBuilder.build())
    }
}
