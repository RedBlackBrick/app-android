package com.tradingplatform.app.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Grille d'espacement 4dp — identique au web (4px grid).
 *
 * Règle : utiliser TOUJOURS Spacing.* dans les Composables.
 * Ne jamais hardcoder des valeurs .dp directement.
 *
 * Référence : docs/design-system.md § Espacements
 *
 * | Token       | dp  | Utilisation                          |
 * |-------------|-----|--------------------------------------|
 * | Spacing.xs  | 4dp | Padding minimal, gap icône/texte     |
 * | Spacing.sm  | 8dp | Padding interne petit composant      |
 * | Spacing.md  | 12dp | Padding standard                    |
 * | Spacing.lg  | 16dp | Marges standard (défaut)            |
 * | Spacing.xl  | 24dp | Espacement entre sections            |
 * | Spacing.xxl | 32dp | Espacement majeur                    |
 * | Spacing.xxxl | 48dp | Séparations de blocs               |
 */
object Spacing {
    val xs   = 4.dp
    val sm   = 8.dp
    val md   = 12.dp
    val lg   = 16.dp
    val xl   = 24.dp
    val xxl  = 32.dp
    val xxxl = 48.dp
}
