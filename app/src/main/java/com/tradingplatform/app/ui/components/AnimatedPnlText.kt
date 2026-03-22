package com.tradingplatform.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import com.tradingplatform.app.ui.theme.Emerald200
import com.tradingplatform.app.ui.theme.Motion
import com.tradingplatform.app.ui.theme.pnlColor
import com.tradingplatform.app.ui.theme.Rose100
import com.tradingplatform.app.ui.theme.jetBrainsMonoFamily
import kotlinx.coroutines.delay
import java.math.BigDecimal

/**
 * Animated P&L text that flashes on value changes.
 *
 * When the value changes, the text briefly flashes a brighter version of the
 * P&L color before settling to the standard color. Uses [AnimatedContent] for
 * the value transition and [animateColorAsState] for the color flash.
 *
 * Uses [Motion.ValueUpdateDuration] (500ms) for the flash animation as defined
 * in the design system.
 */
@Composable
fun AnimatedPnlText(
    value: BigDecimal,
    modifier: Modifier = Modifier,
    currencySymbol: String = "€",
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val targetColor = pnlColor(value)

    // Flash color: brighter version for the brief highlight
    val flashColor = when {
        value > BigDecimal.ZERO -> Emerald200
        value < BigDecimal.ZERO -> Rose100
        else -> MaterialTheme.colorScheme.onSurface
    }

    // Track whether we're in the "flash" state
    var isFlashing by remember { mutableStateOf(false) }
    var previousValue by remember { mutableStateOf(value) }

    // Detect value changes and trigger flash
    LaunchedEffect(value) {
        if (value != previousValue) {
            isFlashing = true
            delay(Motion.ValueUpdateDuration.toLong())
            isFlashing = false
            previousValue = value
        }
    }

    val animatedColor by animateColorAsState(
        targetValue = if (isFlashing) flashColor else targetColor,
        animationSpec = tween(
            durationMillis = if (isFlashing) Motion.ShortDuration else Motion.ValueUpdateDuration,
        ),
        label = "pnl_color_flash",
    )

    // Memoize formatted strings — formatPnlAmount allocates a NumberFormat and
    // concatenates strings on each call. With WS quotes arriving every ~1s, this
    // avoids redundant allocations when value/currencySymbol haven't changed.
    // Keys: value (BigDecimal, structural equality) + currencySymbol (String).
    val (formatted, verboseDescription) = remember(value, currencySymbol) {
        val fmt = formatPnlAmount(value, currencySymbol)
        val label = when {
            value > BigDecimal.ZERO -> "Gain"
            value < BigDecimal.ZERO -> "Perte"
            else -> "Gain/Perte"
        }
        fmt to "$label : $fmt"
    }

    AnimatedContent(
        targetState = formatted,
        transitionSpec = {
            fadeIn(tween(Motion.ValueUpdateDuration)) togetherWith
                fadeOut(tween(Motion.ExitDuration))
        },
        label = "pnl_value_transition",
    ) { targetFormatted ->
        Text(
            text = targetFormatted,
            style = style.copy(
                fontFamily = jetBrainsMonoFamily,
                fontFeatureSettings = "tnum",
                textAlign = TextAlign.End,
            ),
            color = animatedColor,
            textAlign = TextAlign.End,
            modifier = modifier.semantics {
                contentDescription = verboseDescription
            },
        )
    }
}
