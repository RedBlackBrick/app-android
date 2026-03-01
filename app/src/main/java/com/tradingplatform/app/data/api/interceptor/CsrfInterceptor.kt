package com.tradingplatform.app.data.api.interceptor

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Injecte le token CSRF sur tous les POST/PUT/DELETE/PATCH.
 *
 * Contraintes critiques :
 * 1. Pas d'AuthApi en paramètre — dépendance circulaire (AuthApi → OkHttpClient → CsrfInterceptor → AuthApi)
 *    Utiliser un @Named("bare") OkHttpClient sans interceptors.
 * 2. Mutex obligatoire — un seul fetch CSRF simultané même si N requêtes parallèles.
 * 3. Toujours lire csrfToken DANS le lock — pas de double-check hors lock.
 */
@Singleton
class CsrfInterceptor @Inject constructor(
    @Named("bare") private val bareHttpClient: OkHttpClient,
    @Named("base_url") private val baseUrl: String,
) : Interceptor {

    private val mutex = Mutex()
    private var csrfToken: String? = null

    private val csrfMethods = setOf("POST", "PUT", "DELETE", "PATCH")

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (request.method !in csrfMethods) {
            return chain.proceed(request)
        }

        val token = runBlocking {
            mutex.withLock {
                csrfToken ?: fetchCsrfToken()
            }
        }

        val response = chain.proceed(
            request.newBuilder()
                .header("X-CSRF-Token", token)
                .build()
        )

        // Si 403 (CSRF invalide) : invalider le cache, refetch et retry une fois
        if (response.code == 403) {
            response.close()
            val newToken = runBlocking {
                mutex.withLock {
                    csrfToken = null
                    fetchCsrfToken()
                }
            }
            return chain.proceed(
                request.newBuilder()
                    .header("X-CSRF-Token", newToken)
                    .build()
            )
        }

        return response
    }

    private fun fetchCsrfToken(): String {
        Timber.d("CsrfInterceptor: fetching new CSRF token")
        val req = Request.Builder()
            .url("$baseUrl/csrf-token")
            .get()
            .build()
        return bareHttpClient.newCall(req).execute().use { resp ->
            resp.body?.string() ?: ""
        }.also { token ->
            csrfToken = token
        }
    }
}
