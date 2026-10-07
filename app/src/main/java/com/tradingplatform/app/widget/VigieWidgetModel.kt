package com.tradingplatform.app.widget

import com.tradingplatform.app.data.local.db.CacheTtl
import com.tradingplatform.app.data.local.db.entity.VigieSnapshotEntity
import com.tradingplatform.app.domain.model.VigieLevel
import com.tradingplatform.app.domain.model.VigieRefugeState
import com.tradingplatform.app.ui.screens.vigie.VIGIE_EMPTY_LABEL
import com.tradingplatform.app.ui.screens.vigie.VIGIE_NO_VALUE
import com.tradingplatform.app.ui.screens.vigie.VigieTone
import com.tradingplatform.app.ui.screens.vigie.abbreviateFamily
import com.tradingplatform.app.ui.screens.vigie.formatVigieScore
import com.tradingplatform.app.ui.screens.vigie.masksFamilyStress
import com.tradingplatform.app.ui.screens.vigie.refugeLabel
import com.tradingplatform.app.ui.screens.vigie.tone
import com.tradingplatform.app.ui.screens.vigie.vigieLevelLabel
import com.tradingplatform.app.domain.model.VigieMostStressedFamily

/**
 * Contenu du widget Vigie, dérivé de la ligne Room `vigie_snapshot` — PUR (testé en JVM) : le
 * composable Glance ne fait que le rendre.
 *
 * Règles : aucune ligne → « Pas encore de donnée » neutre, JAMAIS un niveau ni du vert ; niveau
 * `unknown` ou inconnu de l'app → « Données insuffisantes » neutre ; la famille la plus tendue est
 * toujours affichée (un global Normal ne masque pas une famille très tendue) ; l'horodatage de sync
 * est obligatoire, avec le badge « périmé · » au-delà de [widgetStaleThreshold] ([CacheTtl.VIGIE_MS])
 * OU quand le serveur dit `is_stale` (calcul trop ancien).
 */
internal data class VigieWidgetModel(
    val hasData: Boolean,
    val levelLabel: String,
    val tone: VigieTone,
    val scoreText: String,
    val stressedText: String?,
    /** `true` : la famille la plus tendue est plus grave que le niveau global — mise en avant. */
    val masksStress: Boolean,
    val refugeText: String?,
    val syncText: String?,
    val isStale: Boolean,
)

internal fun vigieWidgetModel(
    entity: VigieSnapshotEntity?,
    now: Long = System.currentTimeMillis(),
): VigieWidgetModel {
    if (entity == null) {
        return VigieWidgetModel(
            hasData = false,
            levelLabel = VIGIE_EMPTY_LABEL,
            tone = VigieTone.NEUTRAL,
            scoreText = VIGIE_NO_VALUE,
            stressedText = null,
            masksStress = false,
            refugeText = null,
            syncText = null,
            isStale = false,
        )
    }

    val level = VigieLevel.fromWire(entity.level)
    val hasLevel = level != VigieLevel.UNKNOWN

    val code = entity.mostStressedFamilyCode
    val stressedFamily = if (code != null) {
        VigieMostStressedFamily(code, entity.mostStressedFamilyLabel.orEmpty(), entity.mostStressedFamilyScore)
    } else {
        null
    }
    val stressedText = stressedFamily
        ?.let { abbreviateFamily(it.code, it.label) }
        ?.let { "Tendue : $it" }

    val refuge = VigieRefugeState.fromWire(entity.refugeState)

    val label = formatWidgetSyncTime(entity.syncedAt, widgetStaleThreshold(CacheTtl.VIGIE_MS), now)
    val stale = label.isStale || entity.isStale
    val syncText = when {
        label.isStale -> label.text // déjà préfixé « périmé · »
        entity.isStale -> "$STALE_PREFIX${label.text}"
        else -> "Sync ${label.text}"
    }

    return VigieWidgetModel(
        hasData = true,
        levelLabel = vigieLevelLabel(level),
        tone = level.tone(),
        scoreText = if (hasLevel) formatVigieScore(entity.score) else VIGIE_NO_VALUE,
        stressedText = stressedText,
        masksStress = masksFamilyStress(level, stressedFamily),
        refugeText = refugeLabel(refuge),
        syncText = syncText,
        isStale = stale,
    )
}

/** Ton Vigie → couleur de widget (fond clair / sombre). Le neutre sert aussi à « pas de donnée ». */
internal fun VigieTone.widgetTone(): WidgetColors.Tone = when (this) {
    VigieTone.NEUTRAL -> WidgetColors.Neutral
    VigieTone.NORMAL -> WidgetColors.Positive
    VigieTone.VIGILANCE -> WidgetColors.Warning
    VigieTone.ALERTE -> WidgetColors.Negative
    VigieTone.CRISE -> WidgetColors.Crisis
}
