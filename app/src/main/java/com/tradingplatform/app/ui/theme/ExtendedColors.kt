package com.tradingplatform.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import java.math.BigDecimal

/**
 * Couleurs custom hors Material 3 standard.
 * Material 3 n'a pas de rôles success, warning, info — définis ici et injectés
 * via CompositionLocalProvider(LocalExtendedColors provides ...).
 *
 * Accès depuis un Composable : LocalExtendedColors.current
 */
@Immutable
data class ExtendedColors(
    // ── P&L ──────────────────────────────────────────────────────────────────
    val pnlPositive: Color,
    val pnlNegative: Color,

    // ── Success ───────────────────────────────────────────────────────────────
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,

    // ── Warning ───────────────────────────────────────────────────────────────
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,

    // ── Info ──────────────────────────────────────────────────────────────────
    val info: Color,
    val onInfo: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,

    // ── Status devices / alertes ──────────────────────────────────────────────
    val statusOnline: Color,
    val statusOnlineContainer: Color,
    val statusOffline: Color,
    val statusOfflineContainer: Color,
    val statusWarning: Color,
    val statusWarningContainer: Color,

    // ── Surface trading (cartes, panels) ──────────────────────────────────────
    val cardSurface: Color,
    val cardSurfaceElevated: Color,
    val divider: Color,
)

// ── Valeurs light ─────────────────────────────────────────────────────────────
// Sources : docs/design-system.md § Couleurs custom (hors M3 standard)
val lightExtendedColors = ExtendedColors(
    pnlPositive = Emerald600,           // #059669 (emerald-600) — = success
    pnlNegative = Rose600,              // #e11d48 (rose-600)   — = error

    success = Emerald600,               // #059669 (emerald-600)
    onSuccess = White,                  // #ffffff
    successContainer = Emerald100,     // #d1fae5 (emerald-100)
    onSuccessContainer = Emerald900,   // #064e3b (emerald-900)

    warning = Amber600,                 // #d97706 (amber-600)
    onWarning = White,                  // #ffffff
    warningContainer = Amber100,       // #fef3c7 (amber-100)
    onWarningContainer = Amber900,     // #78350f (amber-900)

    info = Sky600,                      // #0284c7 (sky-600)
    onInfo = White,                     // #ffffff
    infoContainer = Sky100,            // #e0f2fe (sky-100)
    onInfoContainer = Sky900,          // #0c4a6e (sky-900)

    statusOnline = Emerald600,
    statusOnlineContainer = Emerald100,
    statusOffline = Rose600,
    statusOfflineContainer = Rose100,
    statusWarning = Amber600,
    statusWarningContainer = Amber100,

    cardSurface = White,
    cardSurfaceElevated = Slate50,
    divider = Slate200,
)

// ── Valeurs dark ──────────────────────────────────────────────────────────────
// Sources : docs/design-system.md § Couleurs custom (hors M3 standard)
val darkExtendedColors = ExtendedColors(
    pnlPositive = Emerald400,          // #34d399 (emerald-400) — = success dark
    pnlNegative = Rose400,             // #fb7185 (rose-400)   — = error dark

    success = Emerald400,              // #34d399 (emerald-400)
    onSuccess = Emerald950,            // #022c22 (emerald-950)
    successContainer = Emerald900,    // #064e3b (emerald-900)
    onSuccessContainer = Emerald100,  // #d1fae5 (emerald-100)

    warning = Amber400,                // #fbbf24 (amber-400)
    onWarning = Amber950,             // #1c0a00 (amber-950)
    warningContainer = Amber900,      // #78350f (amber-900)
    onWarningContainer = Amber100,   // #fef3c7 (amber-100)

    info = Sky400,                     // #38bdf8 (sky-400)
    onInfo = Sky950,                  // #082f49 (sky-950)
    infoContainer = Sky900,          // #0c4a6e (sky-900)
    onInfoContainer = Sky100,        // #e0f2fe (sky-100)

    statusOnline = Emerald400,
    statusOnlineContainer = Emerald900,
    statusOffline = Rose400,
    statusOfflineContainer = Rose800,
    statusWarning = Amber400,
    statusWarningContainer = Amber900,

    cardSurface = Slate900,
    cardSurfaceElevated = Slate800,
    divider = Slate700,
)

val LocalExtendedColors = staticCompositionLocalOf { darkExtendedColors }

// ── Helpers P&L ───────────────────────────────────────────────────────────────

/**
 * Version @Composable — pour usage dans les Composables.
 * Utilise automatiquement les couleurs du thème courant.
 *
 * ```kotlin
 * Text(
 *     text = formatAmount(position.unrealizedPnl),
 *     color = pnlColor(position.unrealizedPnl),
 * )
 * ```
 */
@Composable
fun pnlColor(value: BigDecimal): Color =
    pnlColor(value, LocalExtendedColors.current, MaterialTheme.colorScheme)

/**
 * Version non-Composable — pour tests unitaires et TextStyle pré-construits.
 *
 * ```kotlin
 * // Dans un test unitaire
 * val color = pnlColor(BigDecimal("150.00"), lightExtendedColors, lightColorScheme)
 * assertEquals(lightExtendedColors.pnlPositive, color)
 * ```
 */
fun pnlColor(
    value: BigDecimal,
    ext: ExtendedColors,
    scheme: ColorScheme,
): Color = when {
    value > BigDecimal.ZERO -> ext.pnlPositive
    value < BigDecimal.ZERO -> ext.pnlNegative
    else -> scheme.onSurfaceVariant
}
