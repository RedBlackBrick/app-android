package com.tradingplatform.app.data.api.interceptor

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TimeoutInterceptor @Inject constructor() : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val path = chain.request().url.encodedPath
        val profile = resolveProfile(path)
        return chain
            .withConnectTimeout(profile.connectMs, TimeUnit.MILLISECONDS)
            .withReadTimeout(profile.readMs, TimeUnit.MILLISECONDS)
            .withWriteTimeout(profile.writeMs, TimeUnit.MILLISECONDS)
            .proceed(chain.request())
    }

    companion object {
        internal fun resolveProfile(path: String): TimeoutProfile = when {
            path.startsWith("/v1/market-data/") -> TimeoutProfile.FAST
            path.startsWith("/v1/portfolios") -> TimeoutProfile.MEDIUM
            path.startsWith("/v1/edge-control/") -> TimeoutProfile.SLOW
            path.startsWith("/v1/edge/") -> TimeoutProfile.SLOW
            path.startsWith("/v1/auth/") -> TimeoutProfile.STANDARD
            path.startsWith("/v1/notifications/") -> TimeoutProfile.STANDARD
            path == "/csrf-token" -> TimeoutProfile.STANDARD
            else -> TimeoutProfile.STANDARD
        }
    }
}
