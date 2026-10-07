package com.tradingplatform.app.domain.model

import java.time.Instant
import java.time.LocalDate

/**
 * Niveau de la Vigie (`level` = niveau CONFIRMÉ sur 2 jours, `level_raw` = niveau brut du jour).
 *
 * [UNKNOWN] couvre à la fois le niveau `unknown` du serveur (moins de 3 familles vivantes, pas de
 * score) ET toute valeur que l'app ne connaît pas : une valeur inconnue n'est JAMAIS lue comme
 * « normal ».
 */
enum class VigieLevel(val wire: String) {
    NORMAL("normal"),
    VIGILANCE("vigilance"),
    ALERTE("alerte"),
    CRISE("crise"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWire(raw: String?): VigieLevel =
            entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

/** État du drapeau « refuge » (`none | partial | full | unknown`) ; valeur inconnue → [UNKNOWN]. */
enum class VigieRefugeState(val wire: String) {
    NONE("none"),
    PARTIAL("partial"),
    FULL("full"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWire(raw: String?): VigieRefugeState =
            entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

/** Fraîcheur d'un indicateur (`ok | stale | missing`) ; valeur inconnue → [UNKNOWN] (jamais « ok »). */
enum class VigieIndicatorStatus(val wire: String) {
    OK("ok"),
    STALE("stale"),
    MISSING("missing"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWire(raw: String?): VigieIndicatorStatus =
            entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

/** Drapeau d'un indicateur de régime (`ok | orange | red`) ; valeur inconnue → [UNKNOWN]. */
enum class VigieFlag(val wire: String) {
    OK("ok"),
    ORANGE("orange"),
    RED("red"),
    UNKNOWN("unknown"),
    ;

    companion object {
        /** `null` (pas de seuil / inconnu côté serveur) reste `null` : aucun drapeau n'est inventé. */
        fun fromWire(raw: String?): VigieFlag? =
            raw?.let { value -> entries.firstOrNull { it.wire.equals(value.trim(), ignoreCase = true) } ?: UNKNOWN }
    }
}

/** Seuils de niveau du score global (0..10) : `vigilance` < `alerte` < `crise`. */
data class VigieCutoffs(
    val vigilance: Double?,
    val alerte: Double?,
    val crise: Double?,
)

/** Une des 5 familles (`F1`..`F5`) : [score] 0..2 (moyenne des indicateurs vivants), `null` si aucun. */
data class VigieFamily(
    val code: String,
    val label: String,
    val score: Double?,
    val live: Int,
    val expected: Int,
    val coverage: Double?,
)

/** Famille vivante au score le plus haut (égalité : plus petit code) — jamais masquée par le global. */
data class VigieMostStressedFamily(
    val code: String,
    val label: String,
    val score: Double?,
)

/**
 * Un indicateur scoré 0/1/2. [score] `null` = inconnu / périmé / insuffisant (ignoré du score ET
 * compté « inconnu » dans la couverture, jamais présenté comme 0 ni comme vert).
 */
data class VigieIndicator(
    val code: String,
    val family: String,
    val label: String,
    val unit: String,
    val value: Double?,
    val score: Int?,
    val status: VigieIndicatorStatus,
    val ageDays: Int?,
    val orange: Double,
    val red: Double,
    val higherIsWorse: Boolean,
    val detail: String,
)

/** Indicateur du panneau « régime » (informatif, hors score). */
data class VigieRegimeIndicator(
    val code: String,
    val label: String,
    val unit: String,
    val value: Double?,
    val status: VigieIndicatorStatus,
    val ageDays: Int?,
    val flag: VigieFlag?,
    val detail: String,
)

/** Panneau régime (Sahm, T10Y3M, corrélation SPY/TLT, BAA10Y) — informatif, jamais dans le score. */
data class VigieRegime(
    val asOf: LocalDate?,
    val indicators: List<VigieRegimeIndicator>,
)

/** Une jambe du drapeau refuge. [met] `null` = inconnue. */
data class VigieRefugeLeg(
    val code: String,
    val label: String,
    val met: Boolean?,
    val value: Double?,
    val sigma: Double?,
    val threshold: Double?,
    val detail: String,
)

data class VigieRefuge(
    val asOf: LocalDate?,
    val state: VigieRefugeState,
    val legs: List<VigieRefugeLeg>,
    val legsMet: Int,
    val legsUnknown: Int,
)

/** Point de la courbe 90 jours ; [score] `null` possible, [level] = niveau confirmé. */
data class VigieSparklinePoint(
    val date: LocalDate?,
    val score: Double?,
    val level: VigieLevel,
)

/**
 * Vigie « Refuge » : veille COÏNCIDENTE de la tension de marché (jamais une prévision).
 *
 * [available] = `false` : aucun calcul n'existe encore côté serveur (« pas de donnée », HTTP 200) —
 * tous les autres champs sont vides / `null` et [level] vaut [VigieLevel.UNKNOWN] (jamais un vert).
 * Le score global est une MOYENNE des 5 familles : un global Normal ne dit rien d'une famille très
 * tendue → toujours afficher [mostStressedFamily] et [families] à côté du score.
 */
data class Vigie(
    val available: Boolean,
    val asOf: LocalDate?,
    val computedAt: Instant?,
    val isStale: Boolean,
    val methodologyVersion: String?,
    val score: Double?,
    val level: VigieLevel,
    val levelRaw: VigieLevel,
    val coverage: Double?,
    val liveIndicators: Int,
    val expectedIndicators: Int,
    val liveFamilies: Int,
    val expectedFamilies: Int,
    val mostStressedFamily: VigieMostStressedFamily?,
    val cutoffs: VigieCutoffs?,
    val families: List<VigieFamily>,
    val indicators: List<VigieIndicator>,
    val regime: VigieRegime?,
    val refuge: VigieRefuge?,
    val sparkline: List<VigieSparklinePoint>,
) {
    companion object {
        /** « Pas de donnée » : table serveur vide (avant le premier calcul). Jamais « Normal ». */
        val UNAVAILABLE = Vigie(
            available = false,
            asOf = null,
            computedAt = null,
            isStale = true,
            methodologyVersion = null,
            score = null,
            level = VigieLevel.UNKNOWN,
            levelRaw = VigieLevel.UNKNOWN,
            coverage = null,
            liveIndicators = 0,
            expectedIndicators = 0,
            liveFamilies = 0,
            expectedFamilies = 0,
            mostStressedFamily = null,
            cutoffs = null,
            families = emptyList(),
            indicators = emptyList(),
            regime = null,
            refuge = null,
            sparkline = emptyList(),
        )
    }
}

/**
 * Résultat de la lecture « carte Accueil » : le réglage de compte `ui.appearance.showVigie` décide.
 * [Hidden] = réglage faux (ou absent) — AUCUN appel `v1/vigie` n'a été fait.
 */
sealed interface HomeVigie {
    data object Hidden : HomeVigie
    data class Shown(val vigie: Cached<Vigie>) : HomeVigie
}
