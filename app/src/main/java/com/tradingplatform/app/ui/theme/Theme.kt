package com.tradingplatform.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Thème principal de la Trading Platform.
 *
 * DynamicColor intentionnellement désactivé — thème fixe pour cohérence avec le web.
 * Les utilisateurs Pixel/Samsung verront un thème indigo fixe, pas leurs couleurs Material You.
 * Note à afficher dans SecuritySettingsScreen :
 * "Le thème de l'application est fixe pour correspondre à la plateforme web de trading."
 *
 * Usage :
 * ```kotlin
 * TradingPlatformTheme {
 *     // Contenu de l'app
 * }
 * ```
 *
 * Accès aux couleurs custom depuis un Composable :
 * ```kotlin
 * val colors = LocalExtendedColors.current
 * Text(color = colors.pnlPositive, text = "+1,250.00 €")
 * ```
 */
@Composable
fun TradingPlatformTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extendedColors = if (darkTheme) darkExtendedColors else lightExtendedColors

    CompositionLocalProvider(LocalExtendedColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = TradingTypography,
            shapes = TradingShapes,
            content = content,
        )
    }
}
