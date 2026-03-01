package com.tradingplatform.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Formes Material 3 pour la Trading Platform.
 *
 * Référence : docs/design-system.md § Formes (Shape)
 *
 * | Token M3        | dp  | Web équivalent                  |
 * |-----------------|-----|---------------------------------|
 * | ExtraSmall      | 4dp | xs: 4px (badges)                |
 * | Small           | 8dp | sm: 6px (inputs)                |
 * | Medium          | 12dp | md: 8–12px (boutons)           |
 * | Large           | 16dp | lg: 12px (cards)               |
 * | ExtraLarge      | 28dp | xl: 16px (dialogs)             |
 */
val TradingShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
