package com.tradingplatform.app.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * `GET /v1/vigie` (backend `VigieCurrentResponse`, contrat `audit/vigie/API.md`).
 *
 * `available = false` (table serveur vide, HTTP 200) : tous les autres champs sont `null` ou des
 * listes vides — d'où des champs de premier niveau nullables avec défaut. Les listes sont nullables
 * (un `null` explicite ferait lever Moshi sur une `List` non-nullable) et lues par `orEmpty()`.
 * Les dates (`as_of`, `date`) sont des `YYYY-MM-DD` gardées en `String` ; `computed_at` est un
 * instant ISO lu par `parseInstantOrNull` (Python `.isoformat()`).
 *
 * La même classe sert de format de stockage Room (`VigieSnapshotEntity.payloadJson`) : le JSON mis
 * en cache est exactement ce DTO.
 */
@JsonClass(generateAdapter = true)
data class VigieDto(
    @Json(name = "available") val available: Boolean = false,
    @Json(name = "as_of") val asOf: String? = null,
    @Json(name = "computed_at") val computedAt: String? = null,
    @Json(name = "is_stale") val isStale: Boolean = true,
    @Json(name = "methodology_version") val methodologyVersion: String? = null,
    @Json(name = "score") val score: Double? = null,
    @Json(name = "level") val level: String? = null,
    @Json(name = "level_label") val levelLabel: String? = null,
    @Json(name = "level_raw") val levelRaw: String? = null,
    @Json(name = "coverage") val coverage: Double? = null,
    @Json(name = "live_indicators") val liveIndicators: Int? = null,
    @Json(name = "expected_indicators") val expectedIndicators: Int? = null,
    @Json(name = "live_families") val liveFamilies: Int? = null,
    @Json(name = "expected_families") val expectedFamilies: Int? = null,
    @Json(name = "most_stressed_family") val mostStressedFamily: VigieMostStressedFamilyDto? = null,
    @Json(name = "cutoffs") val cutoffs: VigieCutoffsDto? = null,
    @Json(name = "families") val families: List<VigieFamilyDto>? = null,
    @Json(name = "indicators") val indicators: List<VigieIndicatorDto>? = null,
    @Json(name = "regime") val regime: VigieRegimeDto? = null,
    @Json(name = "refuge") val refuge: VigieRefugeDto? = null,
    @Json(name = "sparkline") val sparkline: List<VigieSparklinePointDto>? = null,
)

@JsonClass(generateAdapter = true)
data class VigieMostStressedFamilyDto(
    @Json(name = "code") val code: String,
    @Json(name = "label") val label: String,
    @Json(name = "score") val score: Double? = null,
)

@JsonClass(generateAdapter = true)
data class VigieCutoffsDto(
    @Json(name = "vigilance") val vigilance: Double? = null,
    @Json(name = "alerte") val alerte: Double? = null,
    @Json(name = "crise") val crise: Double? = null,
)

@JsonClass(generateAdapter = true)
data class VigieFamilyDto(
    @Json(name = "code") val code: String,
    @Json(name = "label") val label: String,
    @Json(name = "score") val score: Double? = null,
    @Json(name = "live") val live: Int = 0,
    @Json(name = "expected") val expected: Int = 0,
    @Json(name = "coverage") val coverage: Double? = null,
)

@JsonClass(generateAdapter = true)
data class VigieIndicatorDto(
    @Json(name = "code") val code: String,
    @Json(name = "family") val family: String,
    @Json(name = "label") val label: String,
    @Json(name = "unit") val unit: String,
    @Json(name = "value") val value: Double? = null,
    @Json(name = "score") val score: Int? = null,
    @Json(name = "status") val status: String,
    @Json(name = "age_days") val ageDays: Int? = null,
    @Json(name = "orange") val orange: Double,
    @Json(name = "red") val red: Double,
    @Json(name = "higher_is_worse") val higherIsWorse: Boolean,
    @Json(name = "detail") val detail: String,
)

/** `regime` : `as_of` + les 4 indicateurs informatifs (clés fixes du backend `RegimeResult`). */
@JsonClass(generateAdapter = true)
data class VigieRegimeDto(
    @Json(name = "as_of") val asOf: String? = null,
    @Json(name = "sahm") val sahm: VigieRegimeIndicatorDto? = null,
    @Json(name = "t10y3m") val t10y3m: VigieRegimeIndicatorDto? = null,
    @Json(name = "spy_tlt_corr_change") val spyTltCorrChange: VigieRegimeIndicatorDto? = null,
    @Json(name = "baa10y") val baa10y: VigieRegimeIndicatorDto? = null,
)

@JsonClass(generateAdapter = true)
data class VigieRegimeIndicatorDto(
    @Json(name = "code") val code: String,
    @Json(name = "label") val label: String,
    @Json(name = "unit") val unit: String,
    @Json(name = "value") val value: Double? = null,
    @Json(name = "status") val status: String,
    @Json(name = "age_days") val ageDays: Int? = null,
    @Json(name = "flag") val flag: String? = null,
    @Json(name = "detail") val detail: String,
)

@JsonClass(generateAdapter = true)
data class VigieRefugeDto(
    @Json(name = "as_of") val asOf: String? = null,
    @Json(name = "state") val state: String? = null,
    @Json(name = "legs") val legs: List<VigieRefugeLegDto>? = null,
    @Json(name = "legs_met") val legsMet: Int = 0,
    @Json(name = "legs_unknown") val legsUnknown: Int = 0,
)

@JsonClass(generateAdapter = true)
data class VigieRefugeLegDto(
    @Json(name = "code") val code: String,
    @Json(name = "label") val label: String,
    @Json(name = "met") val met: Boolean? = null,
    @Json(name = "value") val value: Double? = null,
    @Json(name = "sigma") val sigma: Double? = null,
    @Json(name = "threshold") val threshold: Double? = null,
    @Json(name = "detail") val detail: String,
)

@JsonClass(generateAdapter = true)
data class VigieSparklinePointDto(
    @Json(name = "date") val date: String,
    @Json(name = "score") val score: Double? = null,
    @Json(name = "level") val level: String? = null,
)
