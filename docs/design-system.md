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

**Police principale : Inter** (bundlée dans l'APK — même police que le web)
**Police mono : JetBrains Mono** (bundlée — évite la variabilité de `FontFamily.Monospace` selon les devices)

```kotlin
// ui/theme/Type.kt
val interFontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)
// Fallback : Roboto (défaut Android) si Inter non chargé

// Police mono bundlée — cohérence visuelle garantie sur tous les devices
// Licence OFL (compatible distribution)
val jetBrainsMonoFamily = FontFamily(
    Font(R.font.jetbrainsmono_regular, FontWeight.Normal),
    Font(R.font.jetbrainsmono_medium, FontWeight.Medium),
)
```

Ajouter dans `res/font/` les fichiers `.ttf` Inter **et** JetBrains Mono. Ces polices sont
**bundlées dans l'APK** (téléchargées une fois par le développeur, pas chargées à runtime).
Sources : fonts.google.com (Inter) et jetbrains.com/lp/mono/ (JetBrains Mono, licence OFL).

> Ne pas utiliser les "Downloadable Fonts" Android (chargement runtime depuis les serveurs Google) —
> cette app est utilisée via VPN et le device peut ne pas avoir accès à Google Fonts au démarrage.

**Ne pas utiliser `FontFamily.Monospace`** — c'est la police mono système du device (Droid Sans Mono,
Courier New, etc.) qui varie selon le fabricant. Utiliser `jetBrainsMonoFamily` partout.

### Scale typographique M3 → web

| Token M3 | Taille | Poids | Usage |
|----------|--------|-------|-------|
| `displayLarge` | 57sp | Regular | — (non exposé en surface mais configuré Inter) |
| `displayMedium` | 45sp | Regular | — (non exposé en surface mais configuré Inter) |
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

**Mono** : `jetBrainsMonoFamily` pour les prix/valeurs financières — **ne pas utiliser `FontFamily.Monospace`** (varie selon le device).

> Tous les slots M3 (y compris `displayLarge`/`displayMedium` marqués "non utilisés") doivent être
> configurés avec Inter dans `TradingTypography`. Si un composant M3 les utilise en interne,
> il tombera sur Roboto par défaut sinon.

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

| Token (doc + code) | dp | Utilisation |
|--------------------|-----|-------------|
| `Spacing.xs` | 4dp | Padding minimal, gap icône/texte |
| `Spacing.sm` | 8dp | Padding interne petit composant |
| `Spacing.md` | 12dp | Padding standard |
| `Spacing.lg` | 16dp | Marges standard (défaut) |
| `Spacing.xl` | 24dp | Espacement entre sections |
| `Spacing.xxl` | 32dp | Espacement majeur |
| `Spacing.xxxl` | 48dp | Séparations de blocs |

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

| Niveau | dp | Usage Android | Web équivalent |
|--------|----|--------------|----------------|
| `Elevation.Level0` | 0dp | Background page | — |
| `Elevation.Level1` | 1dp | Cards au repos | shadow-sm |
| `Elevation.Level2` | 3dp | Cards au hover | shadow-md |
| `Elevation.Level3` | 6dp | FAB, App bars | shadow-lg |
| `Elevation.Level4` | 8dp | Modals | shadow-xl |
| `Elevation.Level5` | 12dp | Dialogs critiques | shadow-2xl |

```kotlin
// ui/theme/Elevation.kt
// Obligatoire — sans cet objet les développeurs utilisent des .dp hardcodés
object Elevation {
    val Level0 = 0.dp
    val Level1 = 1.dp
    val Level2 = 3.dp
    val Level3 = 6.dp
    val Level4 = 8.dp
    val Level5 = 12.dp
}
```

---

## Dark mode

**Approche** : `darkColorScheme` M3 statique (pas de DynamicColor Android 12+) pour
garantir la cohérence exacte avec le thème dark web.

> Les utilisateurs de Pixel/Samsung s'attendent à ce que les apps suivent leur couleur
> Material You. Afficher une note dans `SecuritySettingsScreen` :
> *"Le thème de l'application est fixe pour correspondre à la plateforme web de trading."*

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

### Toggle manuel

Le thème suit `isSystemInDarkTheme()` par défaut — il n'existe pas (et il ne doit pas
exister) de switch "Clair / Sombre / Système" dans `SecuritySettingsScreen`. Changer
le thème dans les paramètres Android (Réglages → Affichage → Thème sombre) ou via le
Quick Settings tile bascule l'app automatiquement. La cohérence avec le thème web est
conservée dans les deux modes.

Pour tester rapidement en développement :
- Émulateur : `adb shell "cmd uimode night yes"` / `adb shell "cmd uimode night no"`
- Device physique : Quick Settings → tuile "Dark theme"
- Android Studio preview : annoter `@Preview(uiMode = UI_MODE_NIGHT_YES)` en complément
  du `@Preview` standard (light). Voir exemples dans `app/src/main/java/com/tradingplatform/app/ui/components/*.kt`.

### Screenshot tests (Paparazzi)

Les composants visuellement sensibles (P&L, valeurs monétaires, métriques device,
widgets) sont couverts par des snapshots Paparazzi dans
`app/src/test/java/com/tradingplatform/app/snapshots/`. Chaque composant a deux
snapshots — light + dark — stockés dans `app/src/test/snapshots/images/`.

Le plugin Paparazzi est **opt-in** (n'alourdit pas les builds courants) :

```bash
# Régénérer les snapshots après un changement UI volontaire
./gradlew recordPaparazziDebug -PenablePaparazzi=true

# Vérifier en CI que rien n'a changé visuellement
./gradlew verifyPaparazziDebug -PenablePaparazzi=true
```

Workflow :
1. Modifier un composant → `verifyPaparazziDebug` échoue avec un diff PNG
2. Inspecter visuellement la différence (Gradle affiche le chemin de l'image)
3. Si le changement est intentionnel → `recordPaparazziDebug` pour régénérer
4. Commit les nouveaux PNG avec le changement de code

Composants couverts actuellement :
- `PnlText` (3 valeurs × 2 modes = 6 snapshots)
- `AnimatedPnlText` (4 valeurs × 2 modes)
- `MetricsComponents` (3 seuils × 2 modes)

Ajouter un nouveau composant critique → créer `XxxSnapshotTest.kt` dans le même dossier,
pattern `TradingPlatformTheme(darkTheme = ...) { ... }` pour forcer le mode.

### Tester chaque composant en dark mode — checklist avant merge

Chaque nouveau composant Compose DOIT être vérifié en light ET dark avant merge.
Le rendu dark n'est pas équivalent au rendu light : un code couleur mal choisi
(ex : `Color.Black` au lieu de `MaterialTheme.colorScheme.onSurface`) ne saute
aux yeux qu'en dark mode.

Procédure minimale :

1. **Preview** : annoter le composable avec `@Preview` et `@Preview(uiMode = UI_MODE_NIGHT_YES)`.
   Vérifier visuellement le rendu dans les deux panneaux Android Studio.
2. **Screenshot test Paparazzi** (si composant critique : P&L, monnaies, badges,
   widgets) : deux snapshots par composant — light + dark. Voir
   `app/src/test/java/com/tradingplatform/app/snapshots/` pour les exemples.
3. **Émulateur** : lancer l'app en light, basculer dark via `adb shell "cmd uimode night yes"`,
   parcourir les écrans modifiés.
4. **Contraste** : WCAG AA ≥ 4.5:1 pour le texte sur fond. Matériel 3 garantit le
   contraste sur `colorScheme.*` mais pas sur les couleurs custom (`ExtendedColors`).
   Vérifier `pnlPositive` / `pnlNegative` sur `surface` en dark avec
   [webaim.org/resources/contrastchecker](https://webaim.org/resources/contrastchecker/).

Les quatre règles critiques qui cassent en dark et pas en light :

| Symptôme en dark | Cause probable | Correction |
|------------------|----------------|------------|
| Texte invisible / blanc sur blanc | `Color.Black` ou `Color(0xFF...)` hardcodé | Utiliser `MaterialTheme.colorScheme.onSurface` |
| Card invisible sur fond | Pas d'élévation + `surface == background` | Utiliser `surfaceVariant` ou `elevation` M3 |
| Badge illisible | `Color.Red` / `Color.Green` au lieu des tokens P&L | `LocalExtendedColors.current.pnlPositive/Negative` |
| Icône qui disparaît | `tint = Color.Black` ou `tint = Color.Unspecified` sur icon sombre | `tint = MaterialTheme.colorScheme.onSurface` |

### Composants non-Compose (widgets Glance, notifications)

- **Glance widgets** : utiliser `GlanceTheme.colors.*` (mappe automatiquement sur la
  palette M3 du theme). Les couleurs custom P&L widget sont définies dans
  `WidgetColors` — tester sur l'écran d'accueil en light ET dark.
- **Notifications FCM** : `NotificationCompat.Builder` utilise la couleur d'accentuation
  du launcher — ne pas essayer de forcer un thème. L'icône `ic_dialog_info` est neutre.

---

## Patterns UI spécifiques trading

### Affichage P&L

```kotlin
// Version @Composable — pour usage dans les Composables
@Composable
fun pnlColor(value: BigDecimal): Color =
    pnlColor(value, LocalExtendedColors.current, MaterialTheme.colorScheme)

// Version non-Composable — pour tests unitaires et TextStyle pré-construits
fun pnlColor(value: BigDecimal, ext: ExtendedColors, scheme: ColorScheme): Color = when {
    value > BigDecimal.ZERO -> ext.pnlPositive
    value < BigDecimal.ZERO -> ext.pnlNegative
    else -> scheme.onSurfaceVariant
}
```

La version avec paramètres explicites permet de tester `pnlColor()` sans contexte Compose :
```kotlin
// Dans un test unitaire
val color = pnlColor(BigDecimal("150.00"), lightExtendedColors, lightColorScheme)
assertEquals(lightExtendedColors.pnlPositive, color)
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
// pnlColor est @Composable — appel valide depuis un Composable uniquement
Text(
    text = formatAmount(position.unrealizedPnl),
    style = MaterialTheme.typography.bodyLarge.copy(
        fontFamily = jetBrainsMonoFamily,   // pas FontFamily.Monospace (varie selon device)
        fontFeatureSettings = "tnum"        // chiffres tabulaires
    ),
    color = pnlColor(position.unrealizedPnl),
    textAlign = TextAlign.End
)
```

---

## Animations

Durées et easing standards — à respecter pour la cohérence entre screens :

| Type | Durée | Easing M3 | Usage |
|------|-------|-----------|-------|
| Entrée d'un élément | 300ms | `EmphasizedDecelerator` | Screen entrant, card apparaissant |
| Sortie d'un élément | 200ms | `EmphasizedAccelerator` | Screen sortant, card disparaissant |
| Transition de valeur (P&L) | 500ms | `EmphasizedEasing` | Mise à jour chiffre en direct |
| Feedback immédiat | 100ms | `LinearOutSlowIn` | Ripple, état pressed |

```kotlin
// ui/theme/Motion.kt
object Motion {
    val EnterDuration = 300
    val ExitDuration = 200
    val ValueUpdateDuration = 500

    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)
}
```

**`AnimatedContent` vs `AnimatedVisibility` — distinction obligatoire :**

| Composable | Usage correct |
|------------|--------------|
| `AnimatedVisibility` | Apparition/disparition d'un élément (show/hide) |
| `AnimatedContent` | Transition entre deux **contenus différents** (Loading → Success, valeur qui change) |

```kotlin
// CORRECT — transition Loading → Success → Error
AnimatedContent(
    targetState = uiState,
    transitionSpec = { fadeIn(tween(Motion.EnterDuration)) togetherWith fadeOut(tween(Motion.ExitDuration)) }
) { state ->
    when (state) {
        is QuoteUiState.Loading -> LoadingPlaceholder()
        is QuoteUiState.Success -> QuoteCard(state.data)
        is QuoteUiState.Error   -> ErrorBanner(state.message)
    }
}

// INCORRECT — AnimatedVisibility pour du crossfade entre états
// (produit un flash blanc entre les deux états)
```

Pour les transitions de navigation : `NavHost` avec `EnterTransition.fadeIn(tween(Motion.EnterDuration))`.
Pour les valeurs P&L en polling : `AnimatedContent` sur la valeur elle-même (crossfade chiffre à chiffre).

## Règles à respecter

| Règle | Détail |
|-------|--------|
| Pas de couleur hardcodée | Toujours `MaterialTheme.colorScheme.*` ou `LocalExtendedColors.current.*` |
| Pas de `Color.Red` / `Color.Green` | Utiliser `error` / `success` des tokens |
| Police Inter partout | Même le texte secondaire — cohérence web |
| Mono pour les valeurs numériques | `jetBrainsMonoFamily` — jamais `FontFamily.Monospace` |
| Dark mode testé | Chaque composant doit fonctionner en light et dark |
| Pas de `DynamicColor` | On ne suit pas la couleur du wallpaper Android |
| Spacing via `Spacing.*` | Pas de `.dp` hardcodé dans les Composables |
