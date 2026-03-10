package com.tradingplatform.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Motion
import com.tradingplatform.app.ui.theme.Spacing
import java.math.BigDecimal

/**
 * Mini sparkline chart for P&L visualization on the Dashboard.
 *
 * Draws a line chart with an area fill below. The color is determined by the
 * overall trend (first value vs last value): positive = pnlPositive (emerald),
 * negative = pnlNegative (rose).
 *
 * @param dataPoints list of values to plot (at least 2 points needed)
 * @param height chart height
 */
@Composable
fun SparklineChart(
    dataPoints: List<BigDecimal>,
    modifier: Modifier = Modifier,
    height: Dp = Spacing.xxxl + Spacing.xxl,
) {
    if (dataPoints.size < 2) return

    val extendedColors = LocalExtendedColors.current
    val isPositiveTrend = dataPoints.last() >= dataPoints.first()
    val lineColor = if (isPositiveTrend) extendedColors.pnlPositive else extendedColors.pnlNegative
    val fillColor = lineColor.copy(alpha = 0.15f)
    val transparent = lineColor.copy(alpha = 0f)

    val a11yDescription = buildString {
        append(if (isPositiveTrend) "Tendance positive" else "Tendance négative")
        append(", ${dataPoints.size} points")
        val min = dataPoints.minOf { it }
        val max = dataPoints.maxOf { it }
        append(", min $min, max $max")
    }

    // Draw-reveal animation: line draws from left to right
    val animationProgress = remember { Animatable(0f) }
    LaunchedEffect(dataPoints) {
        animationProgress.snapTo(0f)
        animationProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = Motion.EnterDuration * 2),
        )
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = a11yDescription },
    ) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        val minValue = dataPoints.minOf { it.toFloat() }
        val maxValue = dataPoints.maxOf { it.toFloat() }
        val range = (maxValue - minValue).coerceAtLeast(0.01f)

        // Add padding so the line doesn't touch top/bottom edges
        val verticalPadding = canvasHeight * 0.1f
        val effectiveHeight = canvasHeight - 2 * verticalPadding

        fun xForIndex(index: Int): Float =
            (index.toFloat() / (dataPoints.size - 1)) * canvasWidth

        fun yForValue(value: Float): Float =
            canvasHeight - verticalPadding - ((value - minValue) / range) * effectiveHeight

        // Build the line path
        val linePath = Path().apply {
            dataPoints.forEachIndexed { index, value ->
                val x = xForIndex(index)
                val y = yForValue(value.toFloat())
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
        }

        // Build the fill path (line + close to bottom)
        val fillPath = Path().apply {
            addPath(linePath)
            lineTo(xForIndex(dataPoints.size - 1), canvasHeight)
            lineTo(xForIndex(0), canvasHeight)
            close()
        }

        // Clip to animated progress for draw-reveal effect
        val clipWidth = canvasWidth * animationProgress.value

        clipRect(right = clipWidth) {
            // Draw area fill with gradient
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(fillColor, transparent),
                    startY = 0f,
                    endY = canvasHeight,
                ),
            )

            // Draw line
            drawPath(
                path = linePath,
                color = lineColor,
                style = Stroke(
                    width = 2f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )

            // Draw end-point dot (only visible when fully animated)
            if (animationProgress.value > 0.95f) {
                val lastX = xForIndex(dataPoints.size - 1)
                val lastY = yForValue(dataPoints.last().toFloat())
                drawCircle(
                    color = lineColor,
                    radius = 4f,
                    center = Offset(lastX, lastY),
                )
            }
        }
    }
}
