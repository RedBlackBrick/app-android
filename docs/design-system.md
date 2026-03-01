# Design System — app-android

Cohérence visuelle avec le frontend web (`trading-platform/frontend`).
Traduit en Material 3 (Jetpack Compose).

---

## Palette de couleurs

Le frontend web utilise Tailwind CSS avec des tokens sémantiques. La table ci-dessous
donne les équivalents Android (valeurs hex directes, pas de DynamicColor — le thème
est fixe pour garder la cohérence avec le web).

### Tokens sémantiques → Material 3

| Rôle M3 | Light | Dark | Web équivalent |
|---------|-------|------|----------------|
| `primary` | `#4f46e5` | `#818cf8` | indigo-600 / indigo-400 |
| `onPrimary` | `#ffffff` | `#1e1b4b` | white / indigo-950 |
| `primaryContainer` | `#e0e7ff` | `#3730a3` | indigo-100 / indigo-800 |
| `onPrimaryContainer` | `#312e81` | `#e0e7ff` | indigo-900 / indigo-100 |
| `secondary` | `#475569` | `#94a3b8` | slate-600 / slate-400 |
| `onSecondary` | `#ffffff` | `#0f172a` | white / slate-900 |
| `secondaryContainer` | `#f1f5f9` | `#1e293b` | slate-100 / slate-800 |
| `onSecondaryContainer` | `#0f172a` | `#e2e8f0` | slate-900 / slate-200 |
| `error` | `#e11d48` | `#fb7185` | rose-600 / rose-400 |
| `onError` | `#ffffff` | `#4c0519` | white / rose-950 |
| `errorContainer` | `#ffe4e6` | `#9f1239` | rose-100 / rose-800 |
| `onErrorContainer` | `#881337` | `#ffe4e6` | rose-800 / rose-100 |
| `surface` | `#ffffff` | `#0f172a` | white / slate-900 |
| `onSurface` | `#0f172a` | `#f1f5f9` | slate-900 / slate-100 |
| `surfaceVariant` | `#f1f5f9` | `#1e293b` | slate-100 / slate-800 |
| `onSurfaceVariant` | `#475569` | `#94a3b8` | slate-600 / slate-400 |
| `outline` | `#cbd5e1` | `#334155` | slate-300 / slate-700 |
| `outlineVariant` | `#e2e8f0` | `#1e293b` | slate-200 / slate-800 |
| `background` | `#f8fafc` | `#020617` | slate-50 / slate-950 |
| `onBackground` | `#0f172a` | `#f1f5f9` | slate-900 / slate-100 |
| `inverseSurface` | `#1e293b` | `#f1f5f9` | slate-800 / slate-100 |
| `inverseOnSurface` | `#f8fafc` | `#0f172a` | slate-50 / slate-900 |
| `inversePrimary` | `#818cf8` | `#4f46e5` | indigo-400 / indigo-600 |
| `scrim` | `#0f172a` | `#000000` | slate-900 / black |

### Couleurs custom (hors M3 standard)

Material 3 n'a pas de rôles `success`, `warning`, `info` — ils sont définis manuellement
et injectés via `MaterialTheme.colorScheme` extensions.

```kotlin
// ui/theme/ExtendedColors.kt
data class ExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val info: Color,
    val onInfo: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
    val pnlPositive: Color,    // gains — même que success
    val pnlNegative: Color,    // pertes — même que error
)

val LocalExtendedColors = staticCompositionLocalOf { lightExtendedColors }
```

| Rôle custom | Light | Dark | Web équivalent |
|-------------|-------|------|----------------|
| `success` | `#059669` | `#34d399` | emerald-600 / emerald-400 |
| `onSuccess` | `#ffffff` | `#022c22` | white / emerald-950 |
| `successContainer` | `#d1fae5` | `#064e3b` | emerald-100 / emerald-900 |
| `onSuccessContainer` | `#064e3b` | `#d1fae5` | emerald-900 / emerald-100 |
| `warning` | `#d97706` | `#fbbf24` | amber-600 / amber-400 |
| `onWarning` | `#ffffff` | `#1c0a00` | white / amber-950 |
| `warningContainer` | `#fef3c7` | `#78350f` | amber-100 / amber-900 |
| `onWarningContainer` | `#78350f` | `#fef3c7` | amber-900 / amber-100 |
| `info` | `#0284c7` | `#38bdf8` | sky-600 / sky-400 |
| `onInfo` | `#ffffff` | `#082f49` | white / sky-950 |
| `infoContainer` | `#e0f2fe` | `#0c4a6e` | sky-100 / sky-900 |
| `onInfoContainer` | `#0c4a6e` | `#e0f2fe` | sky-900 / sky-100 |
| `pnlPositive` | `#059669` | `#34d399` | = success |
| `pnlNegative` | `#e11d48` | `#fb7185` | = error |

---

## Typographie

**Police principale : Inter** (Google Fonts downloadable font — même police que le web)

```kotlin
// ui/theme/Type.kt
val interFontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)
// Fallback : Roboto (défaut Android) si Inter non chargé
```

Ajouter dans `res/font/` les fichiers `.ttf` Inter (téléchargés depuis fonts.google.com).

### Scale typographique M3 → web

| Token M3 | Taille | Poids | Usage |
|----------|--------|-------|-------|
| `displayLarge` | 57sp | Regular | — (non utilisé) |
| `displayMedium` | 45sp | Regular | — (non utilisé) |
| `headlineLarge` | 32sp | SemiBold | Titres de page (≈ 3xl web 30px) |
| `headlineMedium` | 28sp | SemiBold | Sections |
| `headlineSmall` | 24sp | SemiBold | Sous-sections (≈ 2xl 24px) |
| `titleLarge` | 22sp | SemiBold | Cards header |
| `titleMedium` | 16sp | SemiBold | Labels importants (≈ xl 20px) |
| `titleSmall` | 14sp | Medium | Labels (≈ sm 14px) |
| `bodyLarge` | 16sp | Normal | Corps de texte (≈ base 16px) |
| `bodyMedium` | 14sp | Normal | Texte secondaire (≈ sm 14px) |
| `bodySmall` | 12sp | Normal | Texte tertiaire (≈ xs 12px) |
| `labelLarge` | 14sp | Medium | Boutons |
| `labelMedium` | 12sp | Medium | Badges, chips |
| `labelSmall` | 11sp | Medium | Captions |

**Mono** : `FontFamily.Monospace` pour les prix/valeurs financières (même logique que le web).

---

## Formes (Shape)

| Token M3 | dp | Web équivalent |
|----------|----|----------------|
| `ExtraSmall` | 4dp | xs: 4px (badges) |
| `Small` | 8dp | sm: 6px (inputs) |
| `Medium` | 12dp | md: 8–12px (boutons) |
| `Large` | 16dp | lg: 12px (cards) |
| `ExtraLarge` | 28dp | xl: 16px (dialogs) |
| `Full` | 50dp | full: 9999px (pills, avatars) |

---

## Espacements

Grille **4dp** identique au web (4px grid).

| Token | dp | Utilisation |
|-------|----|-------------|
| `spacing.xs` | 4dp | Padding minimal, gap icône/texte |
| `spacing.sm` | 8dp | Padding interne petit composant |
| `spacing.md` | 12dp | Padding standard |
| `spacing.lg` | 16dp | Marges standard (défaut) |
| `spacing.xl` | 24dp | Espacement entre sections |
| `spacing.2xl` | 32dp | Espacement majeur |
| `spacing.3xl` | 48dp | Séparations de blocs |

```kotlin
// ui/theme/Spacing.kt
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp
}
```

---

## Élévations et ombres

Material 3 utilise des couleurs de surface tintées (pas des ombres CSS).
Pour les cards en dark mode, M3 ajoute automatiquement une teinte de la couleur primaire.

| Niveau | Usage Android | Web équivalent |
|--------|--------------|----------------|
| `Elevation.Level0` | Background page | — |
| `Elevation.Level1` | Cards au repos | shadow-sm |
| `Elevation.Level2` | Cards au hover | shadow-md |
| `Elevation.Level3` | FAB, App bars | shadow-lg |
| `Elevation.Level4` | Modals | shadow-xl |
| `Elevation.Level5` | Dialogs critiques | shadow-2xl |

---

## Dark mode

**Approche** : `darkColorScheme` M3 statique (pas de DynamicColor Android 12+) pour
garantir la cohérence exacte avec le thème dark web.

```kotlin
// ui/theme/Theme.kt
@Composable
fun TradingPlatformTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extendedColors = if (darkTheme) darkExtendedColors else lightExtendedColors

    CompositionLocalProvider(LocalExtendedColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = TradingTypography,
            shapes = TradingShapes,
            content = content
        )
    }
}

// Accès aux couleurs custom depuis n'importe quel Composable :
val colors = LocalExtendedColors.current
Text(color = colors.pnlPositive, text = "+1,250.00 €")
```

---

## Patterns UI spécifiques trading

### Affichage P&L

```kotlin
// Couleur conditionnelle selon signe de la valeur
fun pnlColor(value: BigDecimal): Color {
    val ext = LocalExtendedColors.current
    return when {
        value > BigDecimal.ZERO -> ext.pnlPositive
        value < BigDecimal.ZERO -> ext.pnlNegative
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}
```

### Badges de statut

| Statut | Couleur fond | Couleur texte |
|--------|-------------|---------------|
| `open` / actif | `successContainer` | `onSuccessContainer` |
| `closed` / inactif | `surfaceVariant` | `onSurfaceVariant` |
| `pending` / syncing | `warningContainer` | `onWarningContainer` |
| `error` / offline | `errorContainer` | `onErrorContainer` |
| `info` | `infoContainer` | `onInfoContainer` |

### Valeurs monétaires

Toujours afficher en police **mono** avec alignement à droite :

```kotlin
Text(
    text = formatAmount(position.unrealizedPnl),
    style = MaterialTheme.typography.bodyLarge.copy(
        fontFamily = FontFamily.Monospace,
        fontFeatureSettings = "tnum"  // chiffres tabulaires
    ),
    color = pnlColor(position.unrealizedPnl),
    textAlign = TextAlign.End
)
```

---

## Règles à respecter

| Règle | Détail |
|-------|--------|
| Pas de couleur hardcodée | Toujours `MaterialTheme.colorScheme.*` ou `LocalExtendedColors.current.*` |
| Pas de `Color.Red` / `Color.Green` | Utiliser `error` / `success` des tokens |
| Police Inter partout | Même le texte secondaire — cohérence web |
| Mono pour les valeurs numériques | Prix, P&L, quantités, pourcentages |
| Dark mode testé | Chaque composant doit fonctionner en light et dark |
| Pas de `DynamicColor` | On ne suit pas la couleur du wallpaper Android |
| Spacing via `Spacing.*` | Pas de `.dp` hardcodé dans les Composables |
