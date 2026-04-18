# Screenshot tests — Paparazzi

Tests visuels des composants critiques (P&L, valeurs monétaires, badges de statut, widgets)
pour détecter les régressions de design — couleurs, alignement, typographie tabulaire,
contraste dark mode.

## Exécution

Paparazzi est un plugin **opt-in** pour ne pas ralentir les builds courants.

```bash
# Régénérer les snapshots après un changement UI volontaire
./gradlew recordPaparazziDebug -PenablePaparazzi=true

# Vérifier les snapshots en CI (échoue si un composant a changé)
./gradlew verifyPaparazziDebug -PenablePaparazzi=true
```

Les PNG de référence sont commités dans `app/src/test/snapshots/images/`.
Ne pas régénérer sans revoir visuellement la différence — c'est le point.

## Règles

- **Deux snapshots par composant critique** : light ET dark.
- **Valeurs réalistes** : préférer "+1 250,00 €" à "+1.0 €" — les régressions sur les
  espaces insécables / séparateurs de milliers ne se voient que sur des nombres complets.
- **Pas de data random** : les snapshots doivent être déterministes. Pas d'`Instant.now()`,
  pas de `Random`, pas de timestamp.
- **Composants présentables isolément uniquement** : inutile de snapshot un écran entier
  qui dépend de 5 ViewModels.

## Composants couverts

- `AnimatedPnlText` — valeurs positives, négatives, zéro
- `PositionCard` — position ouverte avec P&L +/-
- `MetricsComponents` (CPU/RAM/Temp badges) — seuils warn/crit
- Widgets Glance (QuoteWidget, PnlWidget) — via `GlanceTheme`
