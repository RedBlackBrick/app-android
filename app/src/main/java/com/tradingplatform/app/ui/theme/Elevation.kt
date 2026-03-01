package com.tradingplatform.app.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Niveaux d'élévation Material 3 pour la Trading Platform.
 *
 * Material 3 utilise des couleurs de surface tintées (pas des ombres CSS).
 * En dark mode, M3 ajoute automatiquement une teinte de la couleur primaire.
 *
 * Référence : docs/design-system.md § Élévations et ombres
 *
 * | Niveau       | dp  | Usage Android              | Web équivalent |
 * |--------------|-----|----------------------------|----------------|
 * | Level0       | 0dp | Background page            | —              |
 * | Level1       | 1dp | Cards au repos             | shadow-sm      |
 * | Level2       | 3dp | Cards au hover             | shadow-md      |
 * | Level3       | 6dp | FAB, App bars              | shadow-lg      |
 * | Level4       | 8dp | Modals                     | shadow-xl      |
 * | Level5       | 12dp | Dialogs critiques         | shadow-2xl     |
 */
object Elevation {
    val Level0 = 0.dp
    val Level1 = 1.dp
    val Level2 = 3.dp
    val Level3 = 6.dp
    val Level4 = 8.dp
    val Level5 = 12.dp

    // Aliases sémantiques
    val none    = Level0
    val xs      = Level1
    val sm      = Level2
    val md      = Level3
    val lg      = Level4
    val xl      = Level5
}
