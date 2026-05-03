package com.tradingplatform.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Formes Material 3 pour la Trading Platform.
 *
 * L2 refresh — moins arrondi pour un rendu plus clinique et data-first :
 * les cartes (large) passent de 16→12dp, les surfaces moyennes de 12→10dp.
 *
 * Référence : docs/design-system.md § Formes (Shape)
 *
 * | Token M3        | dp  | Web équivalent                  |
 * |-----------------|-----|---------------------------------|
 * | ExtraSmall      | 4dp | xs: 4px (badges)                |
 * | Small           | 6dp | sm: 6px (inputs)                |
 * | Medium          | 10dp | md: 8–10px (boutons)           |
 * | Large           | 12dp | lg: 12px (cards)               |
 * | ExtraLarge      | 24dp | xl: 16–24px (dialogs)          |
 */
val TradingShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(24.dp),
)
