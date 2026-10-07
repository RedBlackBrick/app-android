package com.tradingplatform.app.data.model

import com.tradingplatform.app.domain.model.Vigie
import com.tradingplatform.app.domain.model.VigieCutoffs
import com.tradingplatform.app.domain.model.VigieFamily
import com.tradingplatform.app.domain.model.VigieFlag
import com.tradingplatform.app.domain.model.VigieIndicator
import com.tradingplatform.app.domain.model.VigieIndicatorStatus
import com.tradingplatform.app.domain.model.VigieLevel
import com.tradingplatform.app.domain.model.VigieMostStressedFamily
import com.tradingplatform.app.domain.model.VigieRefuge
import com.tradingplatform.app.domain.model.VigieRefugeLeg
import com.tradingplatform.app.domain.model.VigieRefugeState
import com.tradingplatform.app.domain.model.VigieRegime
import com.tradingplatform.app.domain.model.VigieRegimeIndicator
import com.tradingplatform.app.domain.model.VigieSparklinePoint
import com.tradingplatform.app.domain.util.parseInstantOrNull
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * DTO → domaine de `GET /v1/vigie`.
 *
 * Règles de sûreté (jamais de vert par défaut) :
 * - `available = false`, ou `as_of` absent / illisible → [Vigie.UNAVAILABLE] ;
 * - niveau / refuge / statut / drapeau inconnus → `UNKNOWN` ;
 * - `level = unknown` n'a pas de score : un score éventuellement présent est écarté (cohérence
 *   avec le contrat — « score null quand level vaut unknown »).
 */
internal fun VigieDto.toDomain(): Vigie {
    val parsedAsOf = asOf.toLocalDateOrNull()
    if (!available || parsedAsOf == null) return Vigie.UNAVAILABLE

    val confirmed = VigieLevel.fromWire(level)
    return Vigie(
        available = true,
        asOf = parsedAsOf,
        computedAt = computedAt.parseInstantOrNull(),
        isStale = isStale,
        methodologyVersion = methodologyVersion,
        score = if (confirmed == VigieLevel.UNKNOWN) null else score,
        level = confirmed,
        levelRaw = VigieLevel.fromWire(levelRaw),
        coverage = coverage,
        liveIndicators = liveIndicators ?: 0,
        expectedIndicators = expectedIndicators ?: 0,
        liveFamilies = liveFamilies ?: 0,
        expectedFamilies = expectedFamilies ?: 0,
        mostStressedFamily = mostStressedFamily?.let { VigieMostStressedFamily(it.code, it.label, it.score) },
        cutoffs = cutoffs?.let { VigieCutoffs(it.vigilance, it.alerte, it.crise) },
        families = families.orEmpty().map { it.toDomain() },
        indicators = indicators.orEmpty().map { it.toDomain() },
        regime = regime?.toDomain(),
        refuge = refuge?.toDomain(),
        sparkline = sparkline.orEmpty().map { it.toDomain() },
    )
}

private fun VigieFamilyDto.toDomain() = VigieFamily(
    code = code,
    label = label,
    score = score,
    live = live,
    expected = expected,
    coverage = coverage,
)

private fun VigieIndicatorDto.toDomain() = VigieIndicator(
    code = code,
    family = family,
    label = label,
    unit = unit,
    value = value,
    score = score,
    status = VigieIndicatorStatus.fromWire(status),
    ageDays = ageDays,
    orange = orange,
    red = red,
    higherIsWorse = higherIsWorse,
    detail = detail,
)

private fun VigieRegimeDto.toDomain() = VigieRegime(
    asOf = asOf.toLocalDateOrNull(),
    // Ordre d'affichage fixe (celui du backend) ; un indicateur absent n'est pas inventé.
    indicators = listOfNotNull(sahm, t10y3m, spyTltCorrChange, baa10y).map { it.toDomain() },
)

private fun VigieRegimeIndicatorDto.toDomain() = VigieRegimeIndicator(
    code = code,
    label = label,
    unit = unit,
    value = value,
    status = VigieIndicatorStatus.fromWire(status),
    ageDays = ageDays,
    flag = VigieFlag.fromWire(flag),
    detail = detail,
)

private fun VigieRefugeDto.toDomain() = VigieRefuge(
    asOf = asOf.toLocalDateOrNull(),
    state = VigieRefugeState.fromWire(state),
    legs = legs.orEmpty().map { it.toDomain() },
    legsMet = legsMet,
    legsUnknown = legsUnknown,
)

private fun VigieRefugeLegDto.toDomain() = VigieRefugeLeg(
    code = code,
    label = label,
    met = met,
    value = value,
    sigma = sigma,
    threshold = threshold,
    detail = detail,
)

private fun VigieSparklinePointDto.toDomain() = VigieSparklinePoint(
    date = date.toLocalDateOrNull(),
    score = score,
    level = VigieLevel.fromWire(level),
)

/** `YYYY-MM-DD` → [LocalDate] ; absent ou illisible → `null`. */
internal fun String?.toLocalDateOrNull(): LocalDate? {
    val raw = this?.trim().orEmpty()
    if (raw.isEmpty()) return null
    return try {
        LocalDate.parse(raw)
    } catch (_: DateTimeParseException) {
        null
    }
}
