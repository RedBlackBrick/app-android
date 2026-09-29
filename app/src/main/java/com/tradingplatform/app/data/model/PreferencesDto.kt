package com.tradingplatform.app.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * `GET /v1/auth/preferences`. Le backend n'impose aucun schéma sur `ui` (dict libre écrit par le
 * frontend web) : on le garde comme arbre JSON brut pour pouvoir le réécrire SANS perdre une seule
 * clé inconnue (le PATCH fusionne de façon superficielle, cf. `NotificationPreferencesRepositoryImpl`).
 * Les nombres arrivent en `Double` (Moshi), objets en `Map`, tableaux en `List`.
 * Le bloc `trading` de la réponse n'est pas lu (jamais réécrit depuis le mobile).
 */
@JsonClass(generateAdapter = true)
data class PreferencesResponseDto(
    @Json(name = "ui") val ui: Map<String, Any?>? = null,
)
