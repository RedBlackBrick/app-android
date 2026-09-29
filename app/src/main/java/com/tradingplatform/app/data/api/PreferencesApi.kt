package com.tradingplatform.app.data.api

import com.tradingplatform.app.data.model.PreferencesResponseDto
import retrofit2.Response
import retrofit2.http.GET

/** Lecture des préférences du compte (Retrofit normal). L'écriture est dans [PreferencesWriteApi]. */
interface PreferencesApi {
    @GET("v1/auth/preferences")
    suspend fun getPreferences(): Response<PreferencesResponseDto>
}
