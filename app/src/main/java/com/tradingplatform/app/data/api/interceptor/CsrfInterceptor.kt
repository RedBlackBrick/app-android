package com.tradingplatform.app.data.api.interceptor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Injecte le token CSRF sur tous les POST/PUT/DELETE/PATCH.
 *
 * Le VPS utilise le pattern double-submit cookie :
 * - Header X-CSRF-Token ET cookie csrf_token doivent être présents et identiques.
 * - GET /csrf-token retourne un JSON { "csrf_token": "..." } + Set-Cookie csrf_token=...
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
    private val applicationScope: CoroutineScope,
) : Interceptor {

    companion object {
        private const val TAG = "CsrfInterceptor"
    }

    private val mutex = Mutex()
    @Volatile private var csrfToken: String? = null

    private val csrfMethods = setOf("POST", "PUT", "DELETE", "PATCH")

    // Miroir des exemptions côté VPS (csrf.py CSRF_EXEMPT_PATHS).
    // /v1/auth/refresh n'utilise que le cookie httpOnly refresh_token — pas de CSRF requis.
    private val csrfExemptPaths = setOf(
        "/v1/auth/refresh",
        "/v1/auth/login",
        "/v1/auth/csrf-token",
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (request.method !in csrfMethods || request.url.encodedPath in csrfExemptPaths) {
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
                .header("Cookie", "csrf_token=$token")
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
                    .header("Cookie", "csrf_token=$newToken")
                    .build()
            )
        }

        return response
    }

    /**
     * Pré-fetch le token CSRF après login pour éviter runBlocking contention
     * lors des premières requêtes POST parallèles.
     * No-op si un token est déjà en cache.
     */
    fun preFetch() {
        applicationScope.launch(Dispatchers.IO) {
            try {
                mutex.withLock {
                    if (csrfToken == null) fetchCsrfToken()
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "CsrfInterceptor: preFetch failed (non-blocking)")
            }
        }
    }

    private fun fetchCsrfToken(): String {
        Timber.tag(TAG).d("CsrfInterceptor: fetching new CSRF token")
        val req = Request.Builder()
            .url("$baseUrl/csrf-token")
            .get()
            .build()
        val token = try {
            bareHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Timber.tag(TAG).e("CsrfInterceptor: fetch failed (HTTP ${resp.code})")
                    null
                } else {
                    val body = resp.body?.string()
                    if (body.isNullOrBlank()) {
                        null
                    } else {
                        // Le endpoint retourne {"csrf_token":"...","header_name":"...","cookie_name":"..."}
                        JSONObject(body).getString("csrf_token")
                    }
                }
            }
        } catch (e: IOException) {
            Timber.tag(TAG).e(e, "CsrfInterceptor: fetch failed (network error)")
            null
        }
        if (token == null) {
            Timber.tag(TAG).e("CsrfInterceptor: unable to obtain CSRF token")
            throw IOException("Failed to fetch CSRF token")
        }
        csrfToken = token
        return token
    }
}
