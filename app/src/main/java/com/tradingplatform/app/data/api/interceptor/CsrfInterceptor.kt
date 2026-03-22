package com.tradingplatform.app.data.api.interceptor

import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.ByteString
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
    private val dataStore: EncryptedDataStore,
) : Interceptor {

    companion object {
        private const val TAG = "CsrfInterceptor"

        /**
         * Timeout global pour chaque runBlocking (mutex acquisition + fetch réseau).
         *
         * 10s est suffisant car :
         * - Le bareHttpClient a des timeouts de 5s connect + 5s read = 10s max pour le fetch HTTP.
         * - Le mutex.withLock n'ajoute du temps que si un autre fetch est déjà en vol (rare grâce
         *   au preFetch() post-login).
         * - Sur réseau très lent (VPN 4G), un timeout CSRF ne force PAS de logout : la requête
         *   est envoyée sans header CSRF → le serveur retourne 403 → pas de retry (le 2e runBlocking
         *   pour le retry 403 a son propre budget de 10s).
         */
        private const val CSRF_TIMEOUT_MS = 10_000L
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

        // Buffer the request body upfront so it can be replayed on 403 retry.
        // OkHttp request bodies are one-shot by default — once consumed by chain.proceed(),
        // the body cannot be read again. We snapshot the bytes once and reuse them.
        val bufferedBody = request.body?.let { originalBody ->
            val buffer = Buffer()
            originalBody.writeTo(buffer)
            val snapshot = buffer.snapshot()
            ReplayableRequestBody(snapshot, originalBody.contentType(), originalBody.contentLength())
        }

        val token = runBlocking {
            withTimeoutOrNull(CSRF_TIMEOUT_MS) {
                mutex.withLock {
                    csrfToken ?: loadFromStore() ?: fetchCsrfToken()
                }
            }
        }

        if (token == null) {
            // Timeout atteint : envoyer la requête sans CSRF header.
            // Le serveur retournera 403 — pas de retry ici (le 403 handler ci-dessous
            // tentera un nouveau fetch avec son propre budget de timeout).
            Timber.tag(TAG).w("CsrfInterceptor: timeout after ${CSRF_TIMEOUT_MS}ms — proceeding without CSRF token")
            return chain.proceed(
                request.newBuilder()
                    .method(request.method, bufferedBody ?: request.body)
                    .build()
            )
        }

        val response = chain.proceed(
            request.newBuilder()
                .method(request.method, bufferedBody ?: request.body)
                .header("X-CSRF-Token", token)
                .header("Cookie", "csrf_token=$token")
                .build()
        )

        // Si 403 (CSRF invalide) : invalider le cache, refetch et retry une fois
        if (response.code == 403) {
            response.close()
            val newToken = runBlocking {
                withTimeoutOrNull(CSRF_TIMEOUT_MS) {
                    mutex.withLock {
                        csrfToken = null
                        clearFromStore()
                        fetchCsrfToken()
                    }
                }
            }

            if (newToken == null) {
                // Timeout sur le retry CSRF — abandonner. La requête échouera en 403
                // côté appelant, qui pourra retry manuellement.
                Timber.tag(TAG).w("CsrfInterceptor: timeout on 403 retry — giving up")
                return chain.proceed(
                    request.newBuilder()
                        .method(request.method, bufferedBody ?: request.body)
                        .build()
                )
            }

            return chain.proceed(
                request.newBuilder()
                    .method(request.method, bufferedBody ?: request.body)
                    .header("X-CSRF-Token", newToken)
                    .header("Cookie", "csrf_token=$newToken")
                    .build()
            )
        }

        return response
    }

    /**
     * A [RequestBody] backed by a [ByteString] snapshot, allowing the body to be
     * written multiple times (replayed) without consuming the original stream.
     */
    private class ReplayableRequestBody(
        private val data: ByteString,
        private val mediaType: MediaType?,
        private val length: Long,
    ) : RequestBody() {
        override fun contentType(): MediaType? = mediaType
        override fun contentLength(): Long = length
        override fun isOneShot(): Boolean = false
        override fun writeTo(sink: BufferedSink) { sink.write(data) }
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

    fun clearToken() {
        csrfToken = null
    }

    private suspend fun loadFromStore(): String? {
        return dataStore.readString(DataStoreKeys.CSRF_TOKEN)?.also { csrfToken = it }
    }

    private suspend fun persistToStore(token: String) {
        try { dataStore.writeString(DataStoreKeys.CSRF_TOKEN, token) } catch (_: Exception) {}
    }

    private suspend fun clearFromStore() {
        try { dataStore.remove(DataStoreKeys.CSRF_TOKEN) } catch (_: Exception) {}
    }

    private suspend fun fetchCsrfToken(): String {
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
        persistToStore(token)
        return token
    }
}
