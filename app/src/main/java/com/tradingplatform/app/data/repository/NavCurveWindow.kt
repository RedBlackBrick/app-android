package com.tradingplatform.app.data.repository

import com.tradingplatform.app.domain.model.PnlPeriod
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.roundToInt

/** Plafond de points d'une courbe de NAV renvoyée à l'UI (échantillonnage régulier au-delà). */
internal const val NAV_CURVE_MAX_POINTS = 120

/**
 * Fenêtre de requête bornée de `GET /value-history` pour une période.
 * - [startDate] : `start_date` (jour UTC, inclus) ;
 * - [granularity] : `raw` (un snapshot toutes les 3-5 min) ou `daily` (un point par jour UTC) ;
 * - [keepFrom] : borne basse exacte appliquée côté client (`start_date` n'a qu'une précision au
 *   jour), `null` si `start_date` suffit.
 */
internal data class NavCurveWindow(
    val startDate: LocalDate,
    val granularity: String,
    val keepFrom: Instant?,
)

/**
 * DAY → 24 h glissantes en `raw` (jamais vide juste après minuit UTC ; ≈ 300-600 lignes, ramenées
 * à 120 points) ; WEEK → 7 jours ; MONTH → 30 jours ; ALL / YEAR → 365 jours calendaires au plus
 * (365 points), les trois en `daily` — un `raw` de plusieurs jours pèserait des milliers de lignes
 * pour un aperçu. Le backend n'a ni pagination ni limite : cette fenêtre est la seule borne.
 */
internal fun navCurveWindow(period: PnlPeriod, now: Instant): NavCurveWindow {
    val today = now.atZone(ZoneOffset.UTC).toLocalDate()
    return when (period) {
        PnlPeriod.DAY -> {
            val since = now.minusSeconds(SECONDS_PER_DAY)
            NavCurveWindow(since.atZone(ZoneOffset.UTC).toLocalDate(), "raw", since)
        }
        PnlPeriod.WEEK -> NavCurveWindow(today.minusDays(7), "daily", null)
        PnlPeriod.MONTH -> NavCurveWindow(today.minusDays(30), "daily", null)
        PnlPeriod.YEAR, PnlPeriod.ALL -> NavCurveWindow(today.minusDays(364), "daily", null)
    }
}

private const val SECONDS_PER_DAY = 24L * 60 * 60

/**
 * Échantillonnage régulier à [maxPoints] éléments (premier et dernier conservés, ordre inchangé).
 * Renvoie [items] tel quel s'il ne dépasse pas [maxPoints].
 */
internal fun <T> downsample(items: List<T>, maxPoints: Int): List<T> {
    if (maxPoints < 2 || items.size <= maxPoints) return items
    val step = (items.size - 1).toDouble() / (maxPoints - 1)
    return List(maxPoints) { i -> items[(i * step).roundToInt().coerceAtMost(items.lastIndex)] }
}
