package com.tradingplatform.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tradingplatform.app.ui.theme.Spacing

/**
 * Shimmer effect brush for skeleton loading placeholders.
 *
 * Creates an animated gradient that slides horizontally to indicate loading.
 * Replaces the opaque [LoadingOverlay] for list/card screens where existing
 * content structure is known.
 */
@Composable
fun shimmerBrush(): Brush {
    val shimmerColors = listOf(
        MaterialTheme.colorScheme.surfaceVariant,
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        MaterialTheme.colorScheme.surfaceVariant,
    )

    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer_translate",
    )

    return Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnim - 200f, 0f),
        end = Offset(translateAnim + 200f, 0f),
    )
}

/**
 * A rounded placeholder box that shimmers.
 */
@Composable
fun ShimmerBox(
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .background(
                brush = shimmerBrush(),
                shape = MaterialTheme.shapes.small,
            ),
    )
}

/**
 * Skeleton placeholder for a dashboard card (NAV, P&L, Quote sections).
 */
@Composable
fun SkeletonDashboardCard(
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            ShimmerBox(width = 120.dp, height = 14.dp)
            ShimmerBox(width = 180.dp, height = 28.dp)
            ShimmerBox(width = 80.dp, height = 12.dp)
        }
    }
}

/**
 * Skeleton placeholder for a position card in a list.
 */
@Composable
fun SkeletonPositionCard(
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                ShimmerBox(width = 80.dp, height = 18.dp)
                ShimmerBox(width = 60.dp, height = 12.dp)
                ShimmerBox(width = 50.dp, height = 20.dp)
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                ShimmerBox(width = 90.dp, height = 16.dp)
                ShimmerBox(width = 70.dp, height = 16.dp)
                ShimmerBox(width = 50.dp, height = 12.dp)
            }
        }
    }
}

/**
 * Skeleton placeholder for an alert card in a list.
 */
@Composable
fun SkeletonAlertCard(
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalAlignment = Alignment.Top,
        ) {
            Spacer(modifier = Modifier.width(8.dp + Spacing.sm))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    ShimmerBox(width = 150.dp, height = 16.dp)
                    ShimmerBox(width = 50.dp, height = 20.dp)
                }
                ShimmerBox(width = 220.dp, height = 12.dp)
                ShimmerBox(width = 80.dp, height = 10.dp)
            }
        }
    }
}

/**
 * Skeleton placeholder for a device card in a list.
 */
@Composable
fun SkeletonDeviceCard(
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShimmerBox(width = 120.dp, height = 18.dp)
                ShimmerBox(width = 60.dp, height = 20.dp)
            }
            Spacer(modifier = Modifier.height(Spacing.sm))
            ShimmerBox(width = 180.dp, height = 14.dp)
            Spacer(modifier = Modifier.height(Spacing.xs))
            ShimmerBox(width = 160.dp, height = 12.dp)
        }
    }
}
