package com.tradingplatform.app.data.api.interceptor

import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persiste le cookie refresh_token httpOnly dans EncryptedDataStore.
 *
 * Contraintes :
 * - Sauvegarde uniquement sur les paths d'auth exacts (pas .contains("auth"))
 * - Filtre sur le nom exact "refresh_token" (pas tous les cookies)
 * - Envoie uniquement sur /v1/auth/refresh
 */
@Singleton
class EncryptedCookieJar @Inject constructor(
    private val dataStore: EncryptedDataStore,
) : CookieJar {

    // Paths exacts — ne pas utiliser .contains() qui matcherait n'importe quel futur endpoint
    private val AUTH_SAVE_PATHS = setOf("/v1/auth/login", "/v1/auth/refresh")
    private val REFRESH_PATH = "/v1/auth/refresh"

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (url.encodedPath in AUTH_SAVE_PATHS) {
            cookies
                .filter { it.name == "refresh_token" }  // filtrer sur le nom exact
                .forEach { cookie ->
                    Timber.d("EncryptedCookieJar: saving refresh_token cookie")
                    runBlocking { dataStore.saveCookie(cookie.name, cookie.toString()) }
                }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (url.encodedPath != REFRESH_PATH) return emptyList()

        return runBlocking { dataStore.loadCookies() }
            .mapNotNull { cookieStr ->
                Cookie.parse(url, cookieStr)
            }
    }
}
