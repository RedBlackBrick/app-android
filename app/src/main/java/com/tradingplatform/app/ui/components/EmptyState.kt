package com.tradingplatform.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing

/**
 * Illustrated empty state with title, message and optional CTA.
 *
 * Uses lightweight Canvas-drawn illustrations instead of vector assets
 * to keep the APK lean.
 */
@Composable
fun EmptyState(
    illustration: @Composable () -> Unit,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        illustration()

        Spacer(modifier = Modifier.height(Spacing.xl))

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(Spacing.sm))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(Spacing.lg))
            TextButton(onClick = onAction) {
                Text(text = actionLabel)
            }
        }
    }
}

// ── Canvas illustrations ────────────────────────────────────────────────────

/**
 * Positions empty state: a stylized chart with a flat line.
 */
@Composable
fun EmptyPositionsIllustration(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline

    Canvas(
        modifier = modifier
            .size(80.dp)
            .semantics { contentDescription = "Aucune position" },
    ) {
        val w = size.width
        val h = size.height

        // Chart background
        drawRoundRect(
            color = outline.copy(alpha = 0.15f),
            cornerRadius = CornerRadius(8f, 8f),
            size = Size(w, h),
        )

        // Grid lines
        for (i in 1..3) {
            val y = h * i / 4
            drawLine(
                color = outline.copy(alpha = 0.2f),
                start = Offset(8f, y),
                end = Offset(w - 8f, y),
                strokeWidth = 1f,
            )
        }

        // Flat line (no positions = no movement)
        drawLine(
            color = primary,
            start = Offset(12f, h * 0.6f),
            end = Offset(w - 12f, h * 0.6f),
            strokeWidth = 3f,
            cap = StrokeCap.Round,
        )

        // Small dot at end
        drawCircle(
            color = primary,
            radius = 4f,
            center = Offset(w - 12f, h * 0.6f),
        )
    }
}

/**
 * Alerts empty state: a bell with a checkmark.
 */
@Composable
fun EmptyAlertsIllustration(modifier: Modifier = Modifier) {
    val success = LocalExtendedColors.current.success
    val outline = MaterialTheme.colorScheme.outline

    Canvas(
        modifier = modifier
            .size(80.dp)
            .semantics { contentDescription = "Aucune alerte" },
    ) {
        val w = size.width
        val h = size.height
        val cx = w / 2

        // Bell body (simplified arc)
        val bellPath = Path().apply {
            moveTo(cx - 20f, h * 0.55f)
            quadraticBezierTo(cx - 22f, h * 0.25f, cx, h * 0.2f)
            quadraticBezierTo(cx + 22f, h * 0.25f, cx + 20f, h * 0.55f)
            lineTo(cx + 25f, h * 0.6f)
            lineTo(cx - 25f, h * 0.6f)
            close()
        }
        drawPath(bellPath, color = outline.copy(alpha = 0.3f))

        // Bell clapper
        drawCircle(
            color = outline.copy(alpha = 0.3f),
            radius = 4f,
            center = Offset(cx, h * 0.67f),
        )

        // Checkmark
        val checkPath = Path().apply {
            moveTo(cx - 8f, h * 0.45f)
            lineTo(cx - 2f, h * 0.52f)
            lineTo(cx + 10f, h * 0.32f)
        }
        drawPath(
            checkPath,
            color = success,
            style = Stroke(width = 3f, cap = StrokeCap.Round),
        )
    }
}

/**
 * Devices empty state: a device icon with a plus.
 */
@Composable
fun EmptyDevicesIllustration(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline

    Canvas(
        modifier = modifier
            .size(80.dp)
            .semantics { contentDescription = "Aucun device" },
    ) {
        val w = size.width
        val h = size.height
        val cx = w / 2
        val cy = h / 2

        // Device box
        drawRoundRect(
            color = outline.copy(alpha = 0.25f),
            topLeft = Offset(cx - 24f, cy - 18f),
            size = Size(48f, 36f),
            cornerRadius = CornerRadius(6f, 6f),
            style = Stroke(width = 2f),
        )

        // Antenna
        drawLine(
            color = outline.copy(alpha = 0.25f),
            start = Offset(cx, cy - 18f),
            end = Offset(cx, cy - 28f),
            strokeWidth = 2f,
            cap = StrokeCap.Round,
        )

        // Plus sign
        drawLine(
            color = primary,
            start = Offset(cx, cy + 26f),
            end = Offset(cx, cy + 40f),
            strokeWidth = 3f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = primary,
            start = Offset(cx - 7f, cy + 33f),
            end = Offset(cx + 7f, cy + 33f),
            strokeWidth = 3f,
            cap = StrokeCap.Round,
        )
    }
}
