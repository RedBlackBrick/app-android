package com.tradingplatform.app.ui.screens.vigie

import com.tradingplatform.app.domain.model.Vigie
import com.tradingplatform.app.domain.model.VigieCutoffs
import com.tradingplatform.app.domain.model.VigieFamily
import com.tradingplatform.app.domain.model.VigieFlag
import com.tradingplatform.app.domain.model.VigieIndicator
import com.tradingplatform.app.domain.model.VigieIndicatorStatus
import com.tradingplatform.app.domain.model.VigieLevel
import com.tradingplatform.app.domain.model.VigieMostStressedFamily
import com.tradingplatform.app.domain.model.VigieRefugeLeg
import com.tradingplatform.app.domain.model.VigieRefugeState
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Logique de présentation PURE de la Vigie « Refuge » (aucune dépendance Compose/Android), partagée
 * par la carte Accueil, l'écran détail et le widget (qui importe les libellés/tons). Tout est testé
 * en JVM (VigiePresentationTest).
 *
 * Principe non négociable : veille COÏNCIDENTE, jamais une prévision. Un niveau inconnu ou une
 * absence de donnée n'est JAMAIS vert. Le score global est une MOYENNE des 5 familles : une famille
 * très tendue peut le laisser à « Normal » — on affiche donc toujours la famille la plus tendue et
 * les 5 familles à côté du score (règle d'affichage 3 de audit/vigie/API.md).
 */

// ── Textes fixes ─────────────────────────────────────────────────────────────

internal const val VIGIE_TITLE = "Vigie"
internal const val VIGIE_INSUFFICIENT_LABEL = "Données insuffisantes"
internal const val VIGIE_EMPTY_LABEL = "Pas encore de donnée"
internal const val VIGIE_DISCLAIMER = "Veille coïncidente, pas une prévision"
internal const val VIGIE_COINCIDENT_NOTE =
    "Indicateur coïncident : il mesure la tension actuelle, il ne prédit pas"
internal const val VIGIE_STALE_BADGE = "périmé"
internal const val VIGIE_MASK_NOTE =
    "Le score global est une moyenne des 5 familles : une famille très tendue peut rester masquée."
internal const val VIGIE_NO_VALUE = "—"

/** Échelle du score global (0..10) et du score d'une famille (0..2), pour les libellés « x / max ». */
internal const val VIGIE_SCORE_MAX = 10
internal const val VIGIE_FAMILY_SCORE_MAX = 2

// ── Tons sémantiques (jamais de couleur en dur : le composable les mappe sur le thème) ───────

/** Ton d'un niveau / d'une famille. L'ordre de déclaration est l'ordre de gravité (voir [severity]). */
internal enum class VigieTone {
    NEUTRAL,
    NORMAL,
    VIGILANCE,
    ALERTE,
    CRISE,
    ;

    /** Gravité : NEUTRAL (donnée absente) = 0, puis NORMAL < VIGILANCE < ALERTE < CRISE. */
    val severity: Int get() = if (this == NEUTRAL) 0 else ordinal
}

/** normal = succès, vigilance = avertissement, alerte = erreur (nuance), crise = erreur foncé ; inconnu = neutre. */
internal fun VigieLevel.tone(): VigieTone = when (this) {
    VigieLevel.NORMAL -> VigieTone.NORMAL
    VigieLevel.VIGILANCE -> VigieTone.VIGILANCE
    VigieLevel.ALERTE -> VigieTone.ALERTE
    VigieLevel.CRISE -> VigieTone.CRISE
    VigieLevel.UNKNOWN -> VigieTone.NEUTRAL
}

/** `UNKNOWN` → « Données insuffisantes » (jamais « Normal »). */
internal fun vigieLevelLabel(level: VigieLevel): String = when (level) {
    VigieLevel.NORMAL -> "Normal"
    VigieLevel.VIGILANCE -> "Vigilance"
    VigieLevel.ALERTE -> "Alerte"
    VigieLevel.CRISE -> "Crise"
    VigieLevel.UNKNOWN -> VIGIE_INSUFFICIENT_LABEL
}

// Teinte d'une famille (score 0..2 = moyenne des indicateurs 0/1/2 vivants) : MÊME échelle que les
// indicateurs et que le web (`scoreBand`, frontend/src/lib/utils/vigie.ts) — l'entier le plus proche,
// soit 3 bandes : < 0,5 vert · [0,5 ; 1,5[ orange · ≥ 1,5 rouge. Choix de PRÉSENTATION, pas un seuil
// du moteur. (« Crise » n'existe que pour le NIVEAU GLOBAL, jamais pour une famille.)
private const val FAMILY_BAND_ORANGE = 0.5
private const val FAMILY_BAND_RED = 1.5

/** Ton d'une famille d'après son score 0..2 ; `null` (aucun indicateur vivant) → neutre, jamais vert. */
internal fun familyTone(score: Double?): VigieTone = when {
    score == null || !score.isFinite() -> VigieTone.NEUTRAL
    score >= FAMILY_BAND_RED -> VigieTone.ALERTE
    score >= FAMILY_BAND_ORANGE -> VigieTone.VIGILANCE
    else -> VigieTone.NORMAL
}

/** Ton d'un indicateur : 0 = normal, 1 = orange (vigilance), 2 = rouge (alerte) ; score `null` → neutre. */
internal fun indicatorTone(score: Int?): VigieTone = when (score) {
    0 -> VigieTone.NORMAL
    1 -> VigieTone.VIGILANCE
    2 -> VigieTone.ALERTE
    else -> VigieTone.NEUTRAL
}

/**
 * « Le global Normal ne masque pas F1 rouge » : `true` quand la famille la plus tendue est PLUS
 * grave que le niveau global (le score moyenné la cache). Jamais `true` si le niveau est inconnu
 * (le global n'y rassure personne) ni sans famille vivante.
 */
internal fun masksFamilyStress(level: VigieLevel, family: VigieMostStressedFamily?): Boolean {
    if (family == null || level == VigieLevel.UNKNOWN) return false
    return familyTone(family.score).severity > level.tone().severity
}

// ── Formatage ────────────────────────────────────────────────────────────────

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRENCH)

/** `0` à [maxDecimals] décimales, zéros de fin supprimés, virgule française ; non fini / `null` → « — ». */
internal fun formatVigieNumber(value: Double?, maxDecimals: Int = 2): String {
    if (value == null || !value.isFinite()) return VIGIE_NO_VALUE
    val rounded = BigDecimal.valueOf(value).setScale(maxDecimals, RoundingMode.HALF_UP).stripTrailingZeros()
    val plain = if (rounded.scale() < 0) rounded.setScale(0) else rounded
    return plain.toPlainString().replace('.', ',')
}

/** Score global sur 10, UNE décimale (arrondi HALF_UP), virgule française ; `null` → « — ». */
internal fun formatVigieScore(score: Double?): String {
    if (score == null || !score.isFinite()) return VIGIE_NO_VALUE
    return BigDecimal.valueOf(score).setScale(1, RoundingMode.HALF_UP).toPlainString().replace('.', ',')
}

/** « 14/14 indicateurs » — la couverture est TOUJOURS affichée ; [expected] 0 → « — ». */
internal fun formatCoverage(live: Int, expected: Int): String =
    if (expected <= 0) "Couverture $VIGIE_NO_VALUE" else "$live/$expected indicateurs"

internal fun formatAsOf(asOf: LocalDate?): String? = asOf?.let { DATE_FORMAT.format(it) }

/** Valeur d'un indicateur avec son unité (« 53 pb », « 4,2 % ») ; valeur absente → « — ». */
internal fun formatIndicatorValue(value: Double?, unit: String): String {
    val number = formatVigieNumber(value)
    return if (number == VIGIE_NO_VALUE || unit.isBlank()) number else "$number $unit"
}

/** « Taux et refuge obligataire · 1,25 / 2 » ; score absent → libellé seul. */
internal fun formatFamilyLine(label: String, score: Double?): String =
    if (score == null || !score.isFinite()) label
    else "$label · ${formatVigieNumber(score)} / $VIGIE_FAMILY_SCORE_MAX"

internal fun refugeLabel(state: VigieRefugeState): String = when (state) {
    VigieRefugeState.NONE -> "Refuge : aucun"
    VigieRefugeState.PARTIAL -> "Refuge : partiel"
    VigieRefugeState.FULL -> "Refuge : complet"
    VigieRefugeState.UNKNOWN -> "Refuge : inconnu"
}

/** Le refuge n'est « vert » que quand il est PROUVÉ absent ; partiel → vigilance, complet → alerte, inconnu → neutre. */
internal fun refugeTone(state: VigieRefugeState): VigieTone = when (state) {
    VigieRefugeState.NONE -> VigieTone.NORMAL
    VigieRefugeState.PARTIAL -> VigieTone.VIGILANCE
    VigieRefugeState.FULL -> VigieTone.ALERTE
    VigieRefugeState.UNKNOWN -> VigieTone.NEUTRAL
}

/** Abréviation de la famille pour un widget étroit : « F1 · Taux et refuge obligataire » → « F1 ». */
internal fun abbreviateFamily(code: String?, label: String?): String? = when {
    !code.isNullOrBlank() -> code
    !label.isNullOrBlank() -> label.take(WIDGET_FAMILY_FALLBACK_CHARS)
    else -> null
}

private const val WIDGET_FAMILY_FALLBACK_CHARS = 12

// ── Carte Accueil ────────────────────────────────────────────────────────────

internal data class VigieFamilyBarModel(
    val code: String,
    val label: String,
    /** Fraction 0..1 de la barre (score / 2), `0f` quand le score est absent. */
    val fraction: Float,
    val tone: VigieTone,
    val scoreText: String,
    val isHighlighted: Boolean,
)

internal data class VigieCardModel(
    /** `false` : `available = false` ou niveau inconnu → état neutre « Données insuffisantes ». */
    val hasLevel: Boolean,
    val scoreText: String,
    val levelLabel: String,
    val tone: VigieTone,
    val asOfText: String?,
    val coverageText: String,
    val stressedText: String?,
    val stressedTone: VigieTone,
    /** `true` : le global (Normal…) est plus doux que la famille la plus tendue. */
    val masksStress: Boolean,
    val refugeText: String?,
    val refugeTone: VigieTone,
    val showStaleBadge: Boolean,
    val families: List<VigieFamilyBarModel>,
    /** Scores de la courbe 90 j (`null` = trou) ; `null` si moins de 2 points exploitables. */
    val sparkline: List<Double?>?,
    val spokenDescription: String,
)

/** Modèle de la carte Accueil et de l'en-tête de l'écran détail. */
internal fun vigieCardModel(vigie: Vigie): VigieCardModel {
    val hasLevel = vigie.available && vigie.level != VigieLevel.UNKNOWN
    val level = if (vigie.available) vigie.level else VigieLevel.UNKNOWN
    val stressed = if (vigie.available) vigie.mostStressedFamily else null
    val masks = masksFamilyStress(level, stressed)
    val scoreText = if (hasLevel) formatVigieScore(vigie.score) else VIGIE_NO_VALUE
    val levelLabel = vigieLevelLabel(level)
    val stressedText = stressed?.let { "Famille la plus tendue : ${formatFamilyLine(it.label, it.score)}" }
    val refuge = vigie.refuge?.takeIf { vigie.available }
    val coverage = formatCoverage(vigie.liveIndicators, vigie.expectedIndicators)
    val asOfText = formatAsOf(vigie.asOf)

    val sparkValues = vigie.sparkline.map { it.score }
    val sparkline = sparkValues.takeIf { values -> values.count { it != null } >= 2 }

    val spoken = buildString {
        append("Vigie, veille de la tension de marché. ")
        if (hasLevel) append("Niveau $levelLabel, score $scoreText sur $VIGIE_SCORE_MAX. ")
        else append("$VIGIE_INSUFFICIENT_LABEL. ")
        stressedText?.let { append("$it. ") }
        if (masks) append("$VIGIE_MASK_NOTE ")
        refuge?.let { append("${refugeLabel(it.state)}. ") }
        append("$coverage. ")
        if (vigie.available && vigie.isStale) append("Donnée périmée. ")
        append("$VIGIE_DISCLAIMER.")
    }

    return VigieCardModel(
        hasLevel = hasLevel,
        scoreText = scoreText,
        levelLabel = levelLabel,
        tone = level.tone(),
        asOfText = asOfText,
        coverageText = coverage,
        stressedText = stressedText,
        stressedTone = familyTone(stressed?.score),
        masksStress = masks,
        refugeText = refuge?.let { refugeLabel(it.state) },
        refugeTone = refuge?.let { refugeTone(it.state) } ?: VigieTone.NEUTRAL,
        showStaleBadge = vigie.available && vigie.isStale,
        families = if (vigie.available) vigie.families.map { familyBarModel(it, stressed) } else emptyList(),
        sparkline = sparkline,
        spokenDescription = spoken,
    )
}

private fun familyBarModel(family: VigieFamily, stressed: VigieMostStressedFamily?): VigieFamilyBarModel {
    val score = family.score?.takeIf { it.isFinite() }
    return VigieFamilyBarModel(
        code = family.code,
        label = family.label,
        fraction = if (score == null) 0f else (score / VIGIE_FAMILY_SCORE_MAX).coerceIn(0.0, 1.0).toFloat(),
        tone = familyTone(score),
        scoreText = if (score == null) VIGIE_NO_VALUE else formatVigieNumber(score),
        isHighlighted = stressed != null && stressed.code == family.code,
    )
}

// ── Courbe 90 jours ──────────────────────────────────────────────────────────

/**
 * Borne haute de l'axe de la courbe : le plus grand score observé ou le seuil de vigilance (au
 * moins 1), +10 % de marge. Axe fixe depuis 0 — jamais « zoomé » sur du bruit.
 */
internal fun sparklineAxisMax(values: List<Double?>, cutoffs: VigieCutoffs?): Double {
    val observed = values.filterNotNull().filter { it.isFinite() }.maxOrNull() ?: 0.0
    val vigilance = cutoffs?.vigilance?.takeIf { it.isFinite() } ?: 0.0
    return maxOf(observed, vigilance, 1.0) * SPARK_AXIS_MARGIN
}

/** Seuils (vigilance / alerte / crise) qui tiennent dans l'axe, à tracer en pointillés. */
internal fun sparklineGuides(cutoffs: VigieCutoffs?, axisMax: Double): List<Double> =
    listOfNotNull(cutoffs?.vigilance, cutoffs?.alerte, cutoffs?.crise)
        .filter { it.isFinite() && it > 0.0 && it < axisMax }

private const val SPARK_AXIS_MARGIN = 1.1

// ── Écran détail ─────────────────────────────────────────────────────────────

internal fun indicatorStatusLabel(status: VigieIndicatorStatus): String = when (status) {
    VigieIndicatorStatus.OK -> "à jour"
    VigieIndicatorStatus.STALE -> "périmé"
    VigieIndicatorStatus.MISSING -> "manquant"
    VigieIndicatorStatus.UNKNOWN -> "inconnu"
}

/** « 0 · calme », « 1 · orange », « 2 · rouge » ; `null` (périmé / manquant / insuffisant) → « inconnu ». */
internal fun indicatorScoreLabel(score: Int?): String = when (score) {
    0 -> "0 · calme"
    1 -> "1 · orange"
    2 -> "2 · rouge"
    else -> "inconnu"
}

/** « 4 j » ; `null` → « — ». */
internal fun formatAgeDays(ageDays: Int?): String = if (ageDays == null) VIGIE_NO_VALUE else "$ageDays j"

/**
 * Seuils d'un indicateur : « orange ≥ 25 · rouge ≥ 50 pb » (`higherIsWorse`) ou « orange ≤ -3 · rouge
 * ≤ -5 % » sinon.
 */
internal fun formatThresholds(indicator: VigieIndicator): String {
    val op = if (indicator.higherIsWorse) "≥" else "≤"
    val unit = indicator.unit.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
    return "orange $op ${formatVigieNumber(indicator.orange)} · rouge $op ${formatVigieNumber(indicator.red)}$unit"
}

/** Un indicateur dont la valeur n'est pas prouvée fraîche est affiché grisé, jamais comme une valeur normale. */
internal fun isIndicatorLive(indicator: VigieIndicator): Boolean =
    indicator.status == VigieIndicatorStatus.OK && indicator.score != null

/** Indicateurs d'une famille, dans l'ordre du backend. */
internal fun indicatorsOf(vigie: Vigie, familyCode: String): List<VigieIndicator> =
    vigie.indicators.filter { it.family == familyCode }

internal fun regimeFlagLabel(flag: VigieFlag?): String = when (flag) {
    null -> VIGIE_NO_VALUE
    VigieFlag.OK -> "ok"
    VigieFlag.ORANGE -> "orange"
    VigieFlag.RED -> "rouge"
    VigieFlag.UNKNOWN -> "inconnu"
}

internal fun regimeFlagTone(flag: VigieFlag?): VigieTone = when (flag) {
    VigieFlag.OK -> VigieTone.NORMAL
    VigieFlag.ORANGE -> VigieTone.VIGILANCE
    VigieFlag.RED -> VigieTone.ALERTE
    VigieFlag.UNKNOWN, null -> VigieTone.NEUTRAL
}

internal fun refugeLegLabel(leg: VigieRefugeLeg): String = when (leg.met) {
    true -> "remplie"
    false -> "non remplie"
    null -> "inconnue"
}

internal fun refugeLegTone(leg: VigieRefugeLeg): VigieTone = when (leg.met) {
    true -> VigieTone.ALERTE
    false -> VigieTone.NORMAL
    null -> VigieTone.NEUTRAL
}

/** « 2 jambes remplies sur 3 · 0 inconnue » */
internal fun refugeSummary(legsMet: Int, legsTotal: Int, legsUnknown: Int): String {
    val met = if (legsMet > 1) "jambes remplies" else "jambe remplie"
    val unknown = if (legsUnknown > 1) "inconnues" else "inconnue"
    return "$legsMet $met sur $legsTotal · $legsUnknown $unknown"
}

/**
 * « Vigilance ≥ 2,4 · Alerte ≥ 3,75 · Crise ≥ 5,85 » ; `null` si aucun seuil n'est connu (jamais de
 * seuil inventé côté app — ils viennent du serveur).
 */
internal fun cutoffsLine(cutoffs: VigieCutoffs?): String? {
    if (cutoffs == null) return null
    val parts = listOfNotNull(
        cutoffs.vigilance?.takeIf { it.isFinite() }?.let { "Vigilance ≥ ${formatVigieNumber(it)}" },
        cutoffs.alerte?.takeIf { it.isFinite() }?.let { "Alerte ≥ ${formatVigieNumber(it)}" },
        cutoffs.crise?.takeIf { it.isFinite() }?.let { "Crise ≥ ${formatVigieNumber(it)}" },
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/**
 * Le niveau affiché est CONFIRMÉ sur 2 jours ; quand le niveau brut du jour diffère, on le dit
 * (« Niveau brut du jour : Alerte — non encore confirmé »). `null` quand ils concordent ou que le
 * brut est inconnu.
 */
internal fun rawLevelNote(vigie: Vigie): String? {
    if (!vigie.available) return null
    val raw = vigie.levelRaw
    if (raw == VigieLevel.UNKNOWN || raw == vigie.level) return null
    return "Niveau brut du jour : ${vigieLevelLabel(raw)} — non encore confirmé"
}

/** Méthodologie courte de l'écran détail (version serveur incluse quand elle est connue). */
internal fun methodologyText(version: String?): String {
    val base = "Chaque indicateur reçoit 0 (calme), 1 (orange) ou 2 (rouge) selon des seuils fixes. " +
        "Un indicateur absent ou périmé est ignoré et compté « inconnu » dans la couverture, jamais " +
        "lu comme calme. Le score global (0 à $VIGIE_SCORE_MAX) moyenne les 5 familles ; le niveau " +
        "affiché est confirmé sur 2 jours. Régime et refuge sont informatifs, hors score."
    return if (version.isNullOrBlank()) base else "$base Méthodologie v$version."
}
