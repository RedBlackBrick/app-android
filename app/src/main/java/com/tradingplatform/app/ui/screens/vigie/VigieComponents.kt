package com.tradingplatform.app.ui.screens.vigie

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.tradingplatform.app.domain.model.VigieCutoffs
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing
import androidx.compose.ui.graphics.Path as ComposePath

// ── Couleurs : jetons du thème Material / ExtendedColors, jamais de valeur en dur ──────────────

/** Couleur de remplissage d'une pastille de ton (paire avec [onContainerColor], contraste garanti). */
@Composable
internal fun VigieTone.containerColor(): Color {
    val ext = LocalExtendedColors.current
    return when (this) {
        VigieTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant
        VigieTone.NORMAL -> ext.successContainer
        VigieTone.VIGILANCE -> ext.warningContainer
        VigieTone.ALERTE -> MaterialTheme.colorScheme.errorContainer
        VigieTone.CRISE -> MaterialTheme.colorScheme.error
    }
}

@Composable
internal fun VigieTone.onContainerColor(): Color {
    val ext = LocalExtendedColors.current
    return when (this) {
        VigieTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
        VigieTone.NORMAL -> ext.onSuccessContainer
        VigieTone.VIGILANCE -> ext.onWarningContainer
        VigieTone.ALERTE -> MaterialTheme.colorScheme.onErrorContainer
        VigieTone.CRISE -> MaterialTheme.colorScheme.onError
    }
}

/** Couleur de trait / de barre / de texte d'accent (alerte = erreur nuancée, crise = erreur plein). */
@Composable
internal fun VigieTone.accentColor(): Color {
    val ext = LocalExtendedColors.current
    return when (this) {
        VigieTone.NEUTRAL -> MaterialTheme.colorScheme.outline
        VigieTone.NORMAL -> ext.success
        VigieTone.VIGILANCE -> ext.warning
        VigieTone.ALERTE -> MaterialTheme.colorScheme.error.copy(alpha = ALERTE_ACCENT_ALPHA)
        VigieTone.CRISE -> MaterialTheme.colorScheme.error
    }
}

private const val ALERTE_ACCENT_ALPHA = 0.75f

// ── Pastille de niveau ─────────────────────────────────────────────────────────────────────────

/** Pastille « Normal / Vigilance / Alerte / Crise / Données insuffisantes ». Le texte porte le sens (pas la couleur seule). */
@Composable
internal fun VigieLevelChip(
    label: String,
    tone: VigieTone,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = tone.containerColor(),
        contentColor = tone.onContainerColor(),
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        )
    }
}

/** Badge « périmé » (`is_stale` serveur). */
@Composable
internal fun VigieStaleBadge(modifier: Modifier = Modifier) {
    val ext = LocalExtendedColors.current
    Surface(
        modifier = modifier,
        color = ext.warningContainer,
        contentColor = ext.onWarningContainer,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(
            text = VIGIE_STALE_BADGE,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.xxs),
        )
    }
}

// ── Mini-barres des 5 familles ─────────────────────────────────────────────────────────────────

/**
 * Les familles en mini-barres verticales (hauteur = score / 2). La famille la plus tendue est en
 * gras : un score global Normal avec une barre haute ne doit jamais se lire « tout va bien ».
 * Un score absent = barre vide neutre (jamais verte).
 */
@Composable
internal fun VigieFamilyBars(
    families: List<VigieFamilyBarModel>,
    modifier: Modifier = Modifier,
) {
    if (families.isEmpty()) return
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.Bottom,
    ) {
        families.forEach { family ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
            ) {
                Box(
                    modifier = Modifier
                        .width(Spacing.md)
                        .height(Spacing.xxl)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(Spacing.xs)),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    if (family.fraction > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(family.fraction)
                                .background(family.tone.accentColor(), RoundedCornerShape(Spacing.xs)),
                        )
                    }
                }
                Text(
                    text = family.code,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (family.isHighlighted) FontWeight.Bold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// ── Courbe 90 jours ────────────────────────────────────────────────────────────────────────────

/**
 * Courbe du score global sur les derniers jours ouvrés. Axe de 0 à [sparklineAxisMax] (jamais
 * « zoomé » sur du bruit) ; les seuils vigilance / alerte / crise visibles sont tracés en pointillés.
 * Un score absent crée un TROU (la ligne n'est pas interpolée au travers d'une donnée inconnue).
 * [description] : phrase TalkBack (la courbe n'est pas lisible autrement).
 */
@Composable
internal fun VigieSparkline(
    values: List<Double?>,
    cutoffs: VigieCutoffs?,
    lineColor: Color,
    description: String,
    modifier: Modifier = Modifier,
) {
    if (values.count { it != null } < 2) return
    val axisMax = sparklineAxisMax(values, cutoffs).toFloat()
    val guideColor = MaterialTheme.colorScheme.outlineVariant
    val guides = sparklineGuides(cutoffs, axisMax.toDouble())

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(Spacing.xxxl + Spacing.xl)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        val width = size.width
        val height = size.height
        val pad = height * SPARK_PADDING_FRACTION
        val usable = height - 2 * pad

        fun x(index: Int): Float =
            if (values.size <= 1) 0f else index.toFloat() / (values.size - 1) * width

        fun y(value: Double): Float =
            height - pad - (value.toFloat().coerceIn(0f, axisMax) / axisMax) * usable

        guides.forEach { guide ->
            val gy = y(guide)
            drawLine(
                color = guideColor,
                start = Offset(0f, gy),
                end = Offset(width, gy),
                strokeWidth = SPARK_GUIDE_WIDTH,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(SPARK_DASH, SPARK_DASH)),
            )
        }

        val path = ComposePath()
        var penDown = false
        values.forEachIndexed { index, value ->
            if (value == null || !value.isFinite()) {
                penDown = false
            } else {
                val px = x(index)
                val py = y(value)
                if (penDown) path.lineTo(px, py) else path.moveTo(px, py)
                penDown = true
            }
        }
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = SPARK_LINE_WIDTH, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        // Point final = dernier score connu.
        val lastIndex = values.indexOfLast { it != null && it.isFinite() }
        if (lastIndex >= 0) {
            drawCircle(color = lineColor, radius = SPARK_DOT_RADIUS, center = Offset(x(lastIndex), y(values[lastIndex]!!)))
        }
    }
}

private const val SPARK_PADDING_FRACTION = 0.1f
private const val SPARK_LINE_WIDTH = 3f
private const val SPARK_GUIDE_WIDTH = 1.5f
private const val SPARK_DASH = 8f
private const val SPARK_DOT_RADIUS = 5f
