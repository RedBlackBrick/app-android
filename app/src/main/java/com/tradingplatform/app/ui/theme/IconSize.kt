package com.tradingplatform.app.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Tokens de tailles d'icônes standardisés pour la Trading Platform.
 *
 * Remplace les .dp hardcodés dans les composants pour assurer la cohérence.
 *
 * | Token | dp  | Usage                                      |
 * |-------|-----|--------------------------------------------|
 * | xs    | 12dp | Status dots, micro indicators             |
 * | sm    | 16dp | Inline icons, badges                      |
 * | md    | 24dp | Standard icons (cards, rows, navigation)   |
 * | lg    | 48dp | Empty state icons, feature icons           |
 * | xl    | 80dp | Hero icons (success, error, illustrations) |
 */
object IconSize {
    val xs = 12.dp
    val sm = 16.dp
    val md = 24.dp
    val lg = 48.dp
    val xl = 80.dp
}
