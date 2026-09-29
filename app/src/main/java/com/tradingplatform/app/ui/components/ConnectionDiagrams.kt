package com.tradingplatform.app.ui.components

import android.content.Context
import android.content.res.Configuration
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.tradingplatform.app.ui.theme.IconSize
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing
import com.tradingplatform.app.ui.theme.TradingPlatformTheme
import com.tradingplatform.app.vpn.VpnState
import kotlin.math.sin

/*
 * Schémas animés de connexion : `VpnTunnelDiagram` (téléphone — tunnel — serveur),
 * `DeviceLinkDiagram` (Radxa ↔ serveur via WireGuard) et `LoginBackdrop` (décor de l'écran de
 * connexion). La logique d'état (libellés, teinte, animé ou non) est dans `ConnectionDiagramModel.kt`.
 *
 * Règles de performance (appareils à 2 Go) :
 * - Canvas uniquement, aucune dépendance ; UNE seule `InfiniteTransition` par composable, créée
 *   seulement quand le schéma bouge (aucune boucle d'images pour un schéma statique) ;
 * - la phase animée est un `State<Float>` lu UNIQUEMENT dans la phase de dessin : une image =
 *   un redessin, jamais une recomposition ;
 * - le Canvas vit dans son propre calque (`graphicsLayer`) : le redessin ne ré-enregistre pas
 *   l'écran parent ;
 * - aucune allocation par image : `Stroke`/`Path`/dégradé sont mémorisés (`remember` /
 *   `drawWithCache`), `Offset`/`Size`/`CornerRadius` sont des value classes.
 * - « réduire les animations » (`ANIMATOR_DURATION_SCALE == 0`) et `LocalInspectionMode` → rendu
 *   statique, sans transition.
 */

private const val ACTIVE_PERIOD_MS = 2_400
private const val PENDING_PERIOD_MS = 1_600
private const val BACKDROP_PERIOD_MS = 9_000

/** Points par voie du tube ; 2 voies → 8 cercles par image au plus. */
private const val FLOW_DOTS_PER_LANE = 4
private const val DASH_SEGMENTS = 8
private const val PI_F = 3.1415927f
private const val TWO_PI_F = 6.2831855f

/** Phase figée des rendus statiques : points répartis, aucun invisible (alpha = sin(π·t) ≠ 0). */
private const val STATIC_FLOW_PHASE = 0.125f

/** Phase figée du décor de connexion : courbe entièrement tracée, pleine opacité. */
private const val BACKDROP_STATIC_PHASE = 0.75f

/** Part de la période où la courbe se trace ; le reste, elle reste visible puis s'efface. */
private const val BACKDROP_DRAW_FRACTION = 0.7f
private const val BACKDROP_FADE_START = 0.85f

/** Forme normalisée (0 = bas, 1 = haut) de la courbe de cours du décor : tendance haussière. */
private val BACKDROP_POINTS = floatArrayOf(
    0.30f, 0.34f, 0.31f, 0.38f, 0.36f, 0.42f, 0.40f, 0.35f, 0.44f, 0.50f, 0.47f, 0.53f, 0.49f,
    0.58f, 0.55f, 0.52f, 0.60f, 0.66f, 0.62f, 0.70f, 0.67f, 0.74f, 0.71f, 0.78f, 0.76f, 0.83f,
)

// ── API publique ──────────────────────────────────────────────────────────────

/**
 * Schéma « téléphone — tunnel — serveur » selon [state] (état VPN **affiché**).
 *
 * - `Connected` / `SystemVpnActive` : points qui circulent dans le tunnel (vert) ;
 * - `Connecting` : tunnel en pointillés qui pulse (ambre) ;
 * - `Disconnected` / `Error` / `ConsentRequired` : ligne grise coupée d'une croix.
 *
 * Un libellé (« Tunnel chiffré actif », « VPN externe actif », « Connexion… », « Non connecté »)
 * est affiché sous le schéma ; TalkBack lit une seule description de l'état.
 */
@Composable
fun VpnTunnelDiagram(
    state: VpnState,
    modifier: Modifier = Modifier,
) {
    ConnectionDiagram(
        spec = vpnTunnelSpec(state),
        startIcon = Icons.Default.PhoneAndroid,
        startCaption = "Téléphone",
        endIcon = Icons.Default.Cloud,
        endCaption = "Serveur",
        compact = false,
        modifier = modifier,
    )
}

/**
 * Schéma « Radxa ↔ serveur via WireGuard » : points qui circulent si [online], ligne grise
 * coupée sinon.
 *
 * @param compact variante miniature pour une carte de liste (icônes seules, sans légendes ni
 *   libellé ; la description TalkBack reste lue).
 */
@Composable
fun DeviceLinkDiagram(
    online: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    ConnectionDiagram(
        spec = deviceLinkSpec(online),
        startIcon = Icons.Default.DeveloperBoard,
        startCaption = "Radxa",
        endIcon = Icons.Default.Cloud,
        endCaption = "Serveur",
        compact = compact,
        modifier = modifier,
    )
}

/**
 * Décor discret de l'écran de connexion : une courbe de cours qui se trace lentement en boucle,
 * à très basse opacité, derrière le formulaire. Purement décoratif (aucune sémantique).
 * L'appelant fournit la taille (typiquement `Modifier.fillMaxSize()`).
 */
@Composable
fun LoginBackdrop(modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    val motion = rememberMotionAllowed()
    val phase = rememberLoopPhase(
        animated = motion,
        periodMs = BACKDROP_PERIOD_MS,
        staticPhase = BACKDROP_STATIC_PHASE,
        label = "login_backdrop",
    )

    Spacer(
        modifier = modifier
            .graphicsLayer { }
            .drawWithCache {
                // Bloc de cache : reconstruit seulement si la taille change (jamais par image).
                val width = size.width
                val height = size.height
                val count = BACKDROP_POINTS.size
                val stroke = Stroke(
                    width = Spacing.xxs.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                )
                val dotRadius = Spacing.xs.toPx()
                val linePath = Path()
                for (i in 0 until count) {
                    val x = width * i / (count - 1)
                    val y = backdropY(height, BACKDROP_POINTS[i])
                    if (i == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
                }
                val fillPath = Path().apply {
                    addPath(linePath)
                    lineTo(width, height)
                    lineTo(0f, height)
                    close()
                }
                val fillBrush = Brush.verticalGradient(
                    colors = listOf(lineColor.copy(alpha = 0.10f), lineColor.copy(alpha = 0f)),
                    startY = height * 0.3f,
                    endY = height,
                )

                onDrawBehind {
                    val p = phase.value
                    val progress = minOf(p / BACKDROP_DRAW_FRACTION, 1f)
                    val fade = if (p < BACKDROP_FADE_START) 1f else (1f - p) / (1f - BACKDROP_FADE_START)
                    clipRect(right = width * progress) {
                        drawPath(path = fillPath, brush = fillBrush, alpha = fade)
                        drawPath(path = linePath, color = lineColor, alpha = 0.16f * fade, style = stroke)
                    }
                    if (progress < 1f) {
                        // Pointe de la courbe : interpolation linéaire, cohérente avec la polyligne.
                        val position = progress * (count - 1)
                        val index = minOf(position.toInt(), count - 2)
                        val fraction = position - index
                        val value = BACKDROP_POINTS[index] +
                            (BACKDROP_POINTS[index + 1] - BACKDROP_POINTS[index]) * fraction
                        drawCircle(
                            color = lineColor,
                            radius = dotRadius,
                            center = Offset(width * progress, backdropY(height, value)),
                            alpha = 0.30f * fade,
                        )
                    }
                }
            },
    )
}

// ── Implémentation ────────────────────────────────────────────────────────────

/** Ordonnée d'un point du décor : de 85 % de la hauteur (valeur 0) à 35 % (valeur 1). */
private fun backdropY(height: Float, value: Float): Float = height * (0.85f - 0.5f * value)

@Composable
private fun ConnectionDiagram(
    spec: ConnectionDiagramSpec,
    startIcon: ImageVector,
    startCaption: String,
    endIcon: ImageVector,
    endCaption: String,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val extended = LocalExtendedColors.current
    val toneColor = when (spec.tone) {
        DiagramTone.ACTIVE -> extended.success
        DiagramTone.PENDING -> extended.warning
        DiagramTone.INACTIVE -> scheme.onSurfaceVariant
    }
    val iconTint = if (spec.tone == DiagramTone.INACTIVE) scheme.onSurfaceVariant else scheme.onSurface
    val crossColor = extended.statusOffline

    val motionAllowed = rememberMotionAllowed()
    val moving = spec.animated && motionAllowed
    val phase = rememberLoopPhase(
        animated = moving,
        periodMs = if (spec.link == LinkStyle.DASHED) PENDING_PERIOD_MS else ACTIVE_PERIOD_MS,
        staticPhase = STATIC_FLOW_PHASE,
        label = "connection_diagram",
    )
    val density = LocalDensity.current
    val stroke = remember(density) {
        Stroke(width = with(density) { Spacing.xxs.toPx() }, cap = StrokeCap.Round)
    }
    val linkHeight = if (compact) IconSize.md else IconSize.lg

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = spec.description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.Top,
        ) {
            DiagramNode(
                icon = startIcon,
                caption = startCaption,
                toneColor = toneColor,
                iconTint = iconTint,
                compact = compact,
            )
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .height(linkHeight)
                    .graphicsLayer { },
            ) {
                // Seul point de lecture de la phase : redessin sans recomposition.
                val p = phase.value
                when (spec.link) {
                    LinkStyle.FLOWING -> drawFlowingLink(color = toneColor, stroke = stroke, phase = p)
                    LinkStyle.DASHED -> drawDashedLink(color = toneColor, stroke = stroke, phase = p, moving = moving)
                    LinkStyle.CUT -> drawCutLink(color = toneColor, crossColor = crossColor, stroke = stroke)
                }
            }
            DiagramNode(
                icon = endIcon,
                caption = endCaption,
                toneColor = toneColor,
                iconTint = iconTint,
                compact = compact,
            )
        }
        if (!compact) {
            Text(
                text = spec.label,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Extrémité du schéma : pastille teintée + icône + légende (miniature : icône seule). */
@Composable
private fun DiagramNode(
    icon: ImageVector,
    caption: String,
    toneColor: Color,
    iconTint: Color,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    if (compact) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = modifier.size(IconSize.md),
        )
        return
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Box(
            modifier = Modifier
                .size(IconSize.lg)
                .clip(CircleShape)
                .background(toneColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(IconSize.md),
            )
        }
        Text(
            text = caption,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.xxs),
        )
    }
}

/**
 * Phase `0..1` d'une boucle. Animée : `InfiniteTransition` linéaire (créée seulement dans ce cas) ;
 * statique : valeur figée [staticPhase], aucune boucle d'images.
 */
@Composable
private fun rememberLoopPhase(
    animated: Boolean,
    periodMs: Int,
    staticPhase: Float,
    label: String,
): State<Float> =
    if (animated) {
        val transition = rememberInfiniteTransition(label = label)
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = periodMs, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = label,
        )
    } else {
        remember(staticPhase) { mutableStateOf(staticPhase) }
    }

/** Animations autorisées ? Faux hors durée d'animation système à 0 ou en aperçu. */
@Composable
private fun rememberMotionAllowed(): Boolean {
    val inspection = LocalInspectionMode.current
    val context = LocalContext.current
    return remember(context, inspection) {
        if (inspection) {
            false
        } else {
            isMotionAllowed(animatorDurationScale(context), inspectionMode = false)
        }
    }
}

private fun animatorDurationScale(context: Context): Float =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

/** Alpha en cloche `0 → 1 → 0` sur `t ∈ [0, 1]` (fondu aux extrémités du tube). */
private fun bellAlpha(t: Float): Float = sin(t * PI_F).coerceAtLeast(0f)

/** Tube plein ; points qui circulent vers le serveur (voie haute) et en retour (voie basse). */
private fun DrawScope.drawFlowingLink(color: Color, stroke: Stroke, phase: Float) {
    val width = size.width
    val centerY = size.height / 2f
    val tubeHalf = size.height * 0.3f
    drawRoundRect(
        color = color.copy(alpha = 0.5f),
        topLeft = Offset(0f, centerY - tubeHalf),
        size = Size(width, tubeHalf * 2f),
        cornerRadius = CornerRadius(tubeHalf),
        style = stroke,
    )
    val start = tubeHalf
    val length = width - 2f * tubeHalf
    val laneOffset = tubeHalf * 0.45f
    val radius = tubeHalf * 0.3f
    for (i in 0 until FLOW_DOTS_PER_LANE) {
        val t = (i.toFloat() / FLOW_DOTS_PER_LANE + phase) % 1f
        drawCircle(
            color = color,
            radius = radius,
            center = Offset(start + t * length, centerY - laneOffset),
            alpha = bellAlpha(t),
        )
        val back = 1f - ((t + 0.5f) % 1f)
        drawCircle(
            color = color,
            radius = radius,
            center = Offset(start + back * length, centerY + laneOffset),
            alpha = bellAlpha(back),
        )
    }
}

/** Tunnel en pointillés : une onde d'opacité parcourt les tirets quand [moving], sinon alpha fixe. */
private fun DrawScope.drawDashedLink(color: Color, stroke: Stroke, phase: Float, moving: Boolean) {
    val width = size.width
    val centerY = size.height / 2f
    val step = width / DASH_SEGMENTS
    val dash = step * 0.55f
    for (i in 0 until DASH_SEGMENTS) {
        val alpha = if (moving) {
            0.3f + 0.7f * (0.5f + 0.5f * sin(TWO_PI_F * (phase - i.toFloat() / DASH_SEGMENTS)))
        } else {
            0.6f
        }
        val x = i * step + (step - dash) / 2f
        drawLine(
            color = color,
            start = Offset(x, centerY),
            end = Offset(x + dash, centerY),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
            alpha = alpha,
        )
    }
}

/** Ligne grise interrompue au centre, avec une croix. */
private fun DrawScope.drawCutLink(color: Color, crossColor: Color, stroke: Stroke) {
    val width = size.width
    val centerX = width / 2f
    val centerY = size.height / 2f
    val gap = minOf(size.height * 0.45f, width * 0.2f)
    drawLine(
        color = color,
        start = Offset(0f, centerY),
        end = Offset(centerX - gap, centerY),
        strokeWidth = stroke.width,
        cap = StrokeCap.Round,
        alpha = 0.6f,
    )
    drawLine(
        color = color,
        start = Offset(centerX + gap, centerY),
        end = Offset(width, centerY),
        strokeWidth = stroke.width,
        cap = StrokeCap.Round,
        alpha = 0.6f,
    )
    val arm = gap * 0.5f
    drawLine(
        color = crossColor,
        start = Offset(centerX - arm, centerY - arm),
        end = Offset(centerX + arm, centerY + arm),
        strokeWidth = stroke.width,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = crossColor,
        start = Offset(centerX - arm, centerY + arm),
        end = Offset(centerX + arm, centerY - arm),
        strokeWidth = stroke.width,
        cap = StrokeCap.Round,
    )
}

// ── Aperçus (clair + sombre) ──────────────────────────────────────────────────

@Preview(showBackground = true, widthDp = 360)
@Preview(showBackground = true, widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ConnectionDiagramsPreview() {
    TradingPlatformTheme {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface)
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            VpnTunnelDiagram(state = VpnState.Connected())
            VpnTunnelDiagram(state = VpnState.Connecting)
            VpnTunnelDiagram(state = VpnState.Disconnected)
            DeviceLinkDiagram(online = true)
            DeviceLinkDiagram(online = false, compact = true)
        }
    }
}
