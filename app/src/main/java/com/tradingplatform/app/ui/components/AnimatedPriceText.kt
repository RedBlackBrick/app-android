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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Motion
import com.tradingplatform.app.ui.theme.TradingNumbers
import kotlinx.coroutines.delay
import java.math.BigDecimal

/**
 * Animated price text that flashes on value changes.
 *
 * Flashes green when the price increases, red when it decreases.
 * Returns to [MaterialTheme.colorScheme.onSurface] after the flash.
 */
@Composable
fun AnimatedPriceText(
    value: BigDecimal,
    modifier: Modifier = Modifier,
    currencySymbol: String = "€",
    decimals: Int = 2,
    style: TextStyle = TradingNumbers.bodyLarge,
) {
    val extendedColors = LocalExtendedColors.current
    val neutralColor = MaterialTheme.colorScheme.onSurface

    // Flash color based on price direction
    var flashColor by remember { mutableStateOf(neutralColor) }
    var isFlashing by remember { mutableStateOf(false) }
    var previousValue by remember { mutableStateOf(value) }

    LaunchedEffect(value) {
        if (value != previousValue) {
            flashColor = if (value > previousValue) {
                extendedColors.pnlPositive
            } else {
                extendedColors.pnlNegative
            }
            isFlashing = true
            delay(Motion.ValueUpdateDuration.toLong())
            isFlashing = false
            previousValue = value
        }
    }

    val animatedColor by animateColorAsState(
        targetValue = if (isFlashing) flashColor else neutralColor,
        animationSpec = tween(
            durationMillis = if (isFlashing) Motion.ShortDuration else Motion.ValueUpdateDuration,
        ),
        label = "price_color_flash",
    )

    val formatted = remember(value, currencySymbol, decimals) {
        formatMoneyAmount(value, currencySymbol, decimals)
    }

    AnimatedContent(
        targetState = formatted,
        transitionSpec = {
            fadeIn(tween(Motion.ValueUpdateDuration)) togetherWith
                fadeOut(tween(Motion.ExitDuration))
        },
        label = "price_value_transition",
    ) { targetFormatted ->
        Text(
            text = targetFormatted,
            style = style.copy(
                textAlign = TextAlign.End,
            ),
            color = animatedColor,
            textAlign = TextAlign.End,
            modifier = modifier.semantics {
                contentDescription = "Prix : $targetFormatted"
            },
        )
    }
}
