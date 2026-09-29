package com.tradingplatform.app.data.api

import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.PATCH

/**
 * Écriture des préférences du compte. Interface DÉDIÉE construite sur le Retrofit `@Named("write")`
 * (`retryOnConnectionFailure(false)`, jamais rejouée), fournie par `di/InboxRiskModule.kt`.
 *
 * Le corps est un [RequestBody] JSON pré-sérialisé par le repository (`{"ui": {...}}`) : le contenu
 * de `ui` est un arbre libre dont les `null` explicites doivent être conservés (sérialisation avec
 * `serializeNulls`), ce que le convertisseur Moshi partagé ne fait pas. La réponse 200 (préférences
 * fusionnées) est ignorée : seul le code HTTP compte, l'appelant relit ensuite l'état.
 */
interface PreferencesWriteApi {
    @PATCH("v1/auth/preferences")
    suspend fun updatePreferences(@Body body: RequestBody): Response<Unit>
}
