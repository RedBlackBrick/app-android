package com.tradingplatform.app.data.repository

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.tradingplatform.app.data.api.PreferencesApi
import com.tradingplatform.app.data.api.PreferencesWriteApi
import com.tradingplatform.app.data.model.PreferencesResponseDto
import com.tradingplatform.app.domain.exception.HttpStatusException
import com.tradingplatform.app.domain.model.NotifCategory
import com.tradingplatform.app.domain.model.NotificationPreferences
import com.tradingplatform.app.domain.model.WriteOutcome
import com.tradingplatform.app.domain.repository.NotificationPreferencesRepository
import com.tradingplatform.app.domain.util.runCatchingCancellable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Préférences de notification. Lecture : Retrofit normal ([PreferencesApi]). Écriture :
 * [PreferencesWriteApi] sur le Retrofit `@Named("write")`, jamais rejouée.
 *
 * [setPushEnabled] = read-modify-write (voir [NotificationPreferencesJson]) : une seule relecture,
 * une seule requête PATCH. Le backend fusionne `ui` de façon superficielle, donc le corps envoyé
 * contient `ui.notifications` et `ui.appearance` COMPLETS ; les `null` explicites de l'arbre lu sont
 * réécrits tels quels (`serializeNulls`).
 */
@Singleton
class NotificationPreferencesRepositoryImpl @Inject constructor(
    private val api: PreferencesApi,
    private val writeApi: PreferencesWriteApi,
    moshi: Moshi,
) : NotificationPreferencesRepository {

    private val bodyAdapter: JsonAdapter<Map<String, Any?>> = moshi
        .adapter<Map<String, Any?>>(
            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
        )
        .serializeNulls()

    override suspend fun get(): Result<NotificationPreferences> = runCatchingCancellable {
        NotificationPreferencesJson.parse(fetchPreferences().ui)
    }

    override suspend fun setPushEnabled(
        category: NotifCategory,
        enabled: Boolean,
    ): Result<WriteOutcome> {
        // 1. Relecture. Un échec ici = rien n'a été envoyé : failure franc.
        val current = runCatchingCancellable { fetchPreferences() }
            .getOrElse { return Result.failure(it) }

        // 2. Modification pure : seul `push` de la catégorie change.
        val uiBody = NotificationPreferencesJson.withPushEnabled(current.ui, category, enabled)
        val request = bodyAdapter
            .toJson(mapOf<String, Any?>("ui" to uiBody))
            .toRequestBody(JSON_MEDIA_TYPE)

        // 3. Écriture : un seul PATCH, jamais rejoué.
        return OrdersStrategiesWriteSupport.execute(
            endpoint = ENDPOINT,
            conflictMessage = CONFLICT_MESSAGE,
        ) { writeApi.updatePreferences(request) }
    }

    private suspend fun fetchPreferences(): PreferencesResponseDto {
        val response = api.getPreferences()
        if (!response.isSuccessful) throw HttpStatusException(response.code(), ENDPOINT)
        return response.body() ?: error("Empty preferences response")
    }

    private companion object {
        const val ENDPOINT = "v1/auth/preferences"
        const val CONFLICT_MESSAGE = "Préférences modifiées entre-temps — réessayez"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
