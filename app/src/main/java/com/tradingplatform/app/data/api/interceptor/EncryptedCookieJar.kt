package com.tradingplatform.app.data.api.interceptor

import com.tradingplatform.app.data.api.AuthPaths
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persiste le cookie refresh_token httpOnly dans EncryptedDataStore.
 *
 * Contraintes :
 * - Sauvegarde uniquement sur les paths exacts de [AuthPaths.COOKIE_SAVE] (login, refresh,
 *   2fa/verify + alias verify-2fa — le backend pose refresh_token après une 2FA réussie)
 * - Filtre sur le nom exact "refresh_token" (pas tous les cookies)
 * - Envoie uniquement sur [AuthPaths.REFRESH]
 *
 * Performance : le cookie est maintenu en cache mémoire ([cachedRefreshToken])
 * pour que [loadForRequest] et [saveFromResponse] soient non-bloquants sur le
 * thread OkHttp. La persistance sur disque est effectuée de manière asynchrone
 * dans [applicationScope].
 *
 * Le cache est pré-chargé par [TradingApplication.onCreate] via [preload].
 */
@Singleton
class EncryptedCookieJar @Inject constructor(
    private val dataStore: EncryptedDataStore,
    private val applicationScope: CoroutineScope,
) : CookieJar {

    /** Cache mémoire du refresh_token. null si non chargé ou absent. */
    private val cachedRefreshToken = AtomicReference<String?>(null)

    /**
     * Pré-charge le cookie refresh_token depuis le disque vers le cache mémoire.
     * Appelé par [TradingApplication.onCreate] au démarrage pour que le premier
     * POST /v1/auth/refresh ne bloque pas un thread OkHttp.
     */
    suspend fun preload() {
        val cookies = dataStore.loadCookies()
        // loadCookies() retourne toutes les valeurs des clés "cookie_*" — le seul
        // cookie persisté est "cookie_refresh_token", donc on prend la première.
        val refresh = cookies.firstOrNull()
        if (refresh != null) {
            cachedRefreshToken.set(refresh)
            Timber.d("EncryptedCookieJar: preloaded refresh_token into memory cache")
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (url.encodedPath in AuthPaths.COOKIE_SAVE) {
            cookies
                .filter { it.name == "refresh_token" }
                .forEach { cookie ->
                    Timber.d("EncryptedCookieJar: caching refresh_token cookie (async persist)")
                    // Mise à jour immédiate du cache mémoire (visible par loadForRequest)
                    cachedRefreshToken.set(cookie.value)
                    // Persistance disque asynchrone — le thread OkHttp n'attend pas
                    applicationScope.launch(Dispatchers.IO) {
                        try {
                            dataStore.saveCookie(cookie.name, cookie.value)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.w(e, "EncryptedCookieJar: async saveCookie failed")
                        }
                    }
                }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (url.encodedPath != AuthPaths.REFRESH) return emptyList()

        val value = cachedRefreshToken.get() ?: return emptyList()
        return try {
            listOf(
                Cookie.Builder()
                    .name("refresh_token")
                    .value(value)
                    .domain(url.host)
                    .path("/")
                    .httpOnly()
                    .secure()
                    .build()
            )
        } catch (e: Exception) {
            Timber.e(e, "EncryptedCookieJar: failed to reconstruct cookie")
            emptyList()
        }
    }

    /** Efface le cache mémoire (appelé lors du logout). */
    fun clear() {
        cachedRefreshToken.set(null)
    }
}
