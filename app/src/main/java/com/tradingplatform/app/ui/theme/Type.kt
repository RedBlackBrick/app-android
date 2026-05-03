package com.tradingplatform.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.tradingplatform.app.R

/**
 * Police principale : Inter (bundlée dans l'APK — même police que le web).
 * Fallback automatique sur Roboto si Inter non chargé.
 *
 * NE PAS utiliser FontFamily.SansSerif — la police varie selon le fabricant.
 */
val interFontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/**
 * Police mono : JetBrains Mono (bundlée — évite la variabilité de FontFamily.Monospace).
 * Utiliser pour les prix, valeurs financières et tout contenu tabulaire.
 *
 * NE PAS utiliser FontFamily.Monospace — Droid Sans Mono, Courier New, etc. varient
 * selon le fabricant du device.
 */
val jetBrainsMonoFamily = FontFamily(
    Font(R.font.jetbrainsmono_regular, FontWeight.Normal),
    Font(R.font.jetbrainsmono_medium, FontWeight.Medium),
)

/**
 * Scale typographique Material 3 configurée avec Inter.
 *
 * Tous les slots M3 sont configurés avec Inter — même ceux non exposés en surface
 * (displayLarge, displayMedium) — pour éviter que les composants M3 internes tombent
 * sur Roboto par défaut.
 *
 * L2 refresh — densité data-first :
 * - line-heights de body/label resserrés (-2sp) pour densifier les listes et les tableaux
 * - letter-spacing des titres remis à 0sp ou légèrement négatif (Inter rend mieux ainsi
 *   que la baseline M3 calibrée pour Roboto)
 * - les niveaux display gardent le tracking M3 (titres marketing peu utilisés)
 *
 * Référence : docs/design-system.md § Typographie
 */
val TradingTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = interFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = interFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = interFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = interFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.25).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = interFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.2).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = interFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.15).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = interFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.1).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = interFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = interFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = interFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = interFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = interFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = interFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = interFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.25.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = interFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.25.sp,
    ),
)

/**
 * Styles spécifiques pour les données numériques (Prix, P&L, Quantités).
 * Utilise JetBrains Mono avec tabular figures pour un alignement parfait.
 */
object TradingNumbers {
    val bodyLarge = TradingTypography.bodyLarge.copy(
        fontFamily = jetBrainsMonoFamily,
        fontFeatureSettings = "tnum",
    )
    val bodyMedium = TradingTypography.bodyMedium.copy(
        fontFamily = jetBrainsMonoFamily,
        fontFeatureSettings = "tnum",
    )
    val bodySmall = TradingTypography.bodySmall.copy(
        fontFamily = jetBrainsMonoFamily,
        fontFeatureSettings = "tnum",
    )
    val titleLarge = TradingTypography.titleLarge.copy(
        fontFamily = jetBrainsMonoFamily,
        fontFeatureSettings = "tnum",
    )
    val titleMedium = TradingTypography.titleMedium.copy(
        fontFamily = jetBrainsMonoFamily,
        fontFeatureSettings = "tnum",
    )
}
