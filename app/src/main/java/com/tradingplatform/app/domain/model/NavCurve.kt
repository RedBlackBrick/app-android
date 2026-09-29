package com.tradingplatform.app.domain.model

import java.math.BigDecimal
import java.time.Instant

/** Un point de la courbe de valeur liquidative (NAV, cash inclus) d'un portefeuille. */
data class NavPoint(val at: Instant, val value: BigDecimal)

/**
 * Courbe de NAV d'un portefeuille sur une période : [points] triés par temps croissant, plafonnés
 * (échantillonnage régulier). Peut être vide (portefeuille neuf ou sans historique sur la fenêtre).
 */
data class NavCurve(val points: List<NavPoint>)
