# Trading Platform — Application Android

Application Android native (Kotlin + Jetpack Compose) pour la gestion et la surveillance
de la plateforme de trading algorithmique.

---

## Objectifs

### Fonctionnalités principales

| Fonctionnalité | Statut | Description |
|----------------|--------|-------------|
| Authentification sécurisée | À implémenter | Login JWT + biométrie (empreinte/face) |
| Tunnel WireGuard intégré | À implémenter | VpnService Android — pas d'app externe requise |
| Dashboard investissements | À implémenter | P&L, positions ouvertes, signaux récents |
| Gestion des options | À implémenter | Réglages stratégies, seuils de risque |
| Pairing device Radxa | À implémenter | Scan QR code + confirmation PIN visuelle |
| Accès direct device (LAN) | À implémenter | Connexion locale uniquement, jamais à distance |
| Widgets écran d'accueil | À implémenter | Voir section Widgets ci-dessous |
| Notifications push (FCM) | **Futur** | Voir `fcm/IMPLEMENTATION_NOTES.md` |

---

## Sécurité — Principes non négociables

1. **VPN obligatoire** — toute communication avec le VPS passe par le tunnel WireGuard intégré.
   L'intercepteur HTTP bloque les appels si le tunnel n'est pas actif.
2. **Certificate pinning** — les certificats du VPS sont épinglés via `network_security_config.xml`
   et OkHttp `CertificatePinner`. Une réponse d'un tiers est rejetée même sur HTTPS.
3. **Stockage chiffré** — JWT, clés WireGuard et credentials stockés dans `EncryptedDataStore`
   (AES-256-GCM, clé dans Android Keystore, jamais exportable).
4. **Biométrie** — déverrouillage de l'app requis après 5 minutes d'inactivité.
   La clé de déchiffrement du DataStore est protégée par `BiometricPrompt`.
5. **Accès device LAN uniquement** — la connexion directe à une carte Radxa n'est autorisée
   que si `local_ip` est dans le même sous-réseau (`192.168.x.x` / `10.x.x.x`).
   Aucune route distante pour les devices.
6. **Pas de logs sensibles** — tokens, clés privées et données de positions ne sont jamais
   écrits dans Logcat. `BuildConfig.DEBUG` garde les logs activés uniquement en debug.
7. **Root detection** — l'app refuse de démarrer sur un appareil rooté (RootBeer lib).
8. **Obfuscation** — ProGuard/R8 activé en release avec règles strictes.

---

## Architecture

Clean Architecture en 3 couches + couches transversales :

```
┌─────────────────────────────────────────────────┐
│                    UI Layer                      │
│  Jetpack Compose screens + ViewModels (MVVM)    │
│  Glance widgets + WorkManager updates            │
└───────────────────┬─────────────────────────────┘
                    │ StateFlow / UiState
┌───────────────────▼─────────────────────────────┐
│                 Domain Layer                     │
│  UseCases — logique métier pure, testable        │
│  Domain models (séparés des modèles API/DB)      │
└───────────────────┬─────────────────────────────┘
                    │ Repository interfaces
┌───────────────────▼─────────────────────────────┐
│                  Data Layer                      │
│  Repositories (implémentations)                  │
│  API (Retrofit + OkHttp + WireGuard interceptor) │
│  Local (Room cache + EncryptedDataStore)         │
└─────────────────────────────────────────────────┘

Transversaux :
  vpn/     — WireGuardVpnService (VpnService Android)
  security/ — BiometricManager, RootDetector, CertPinning
  di/       — Hilt modules
  widget/   — Glance AppWidgets
```

---

## Navigation

```
LoginScreen
    └── [auth OK + VPN up]
        └── DashboardScreen (bottom nav)
            ├── Portfolio tab
            │   ├── PositionsScreen
            │   └── PositionDetailScreen
            ├── Devices tab
            │   ├── DeviceListScreen
            │   │   └── DeviceDetailScreen (LAN only)
            │   └── PairingScreen (scan QR + PIN)
            ├── Alerts tab
            │   └── AlertListScreen
            └── Settings tab
                ├── VpnSettingsScreen
                ├── SecuritySettingsScreen
                └── AboutScreen
```

---

## Widgets écran d'accueil (propositions à valider)

Structure prévue pour accueillir plusieurs widgets indépendants (Glance API, Android 12+) :

| Widget | Taille | Données | Rafraîchissement |
|--------|--------|---------|-----------------|
| **P&L du jour** | 2x1 | Gain/perte journalier en € et % | 5 min (WorkManager) |
| **Positions ouvertes** | 2x2 | Liste scrollable des N premières positions | 5 min |
| **Alertes récentes** | 2x1 | Derniers signaux ou alertes de risque | 1 min |
| **État système** | 2x1 | Health VPS + nombre de devices Radxa actifs | 5 min |
| **Cours rapide** | 1x1 | Un seul ticker configurable | 1 min |

> Les widgets n'initialisent pas le tunnel VPN. Ils utilisent un token de session mis en
> cache localement (TTL court) et indiquent "VPN requis" si le cache est expiré.

---

## Connexion device Radxa (LAN)

Quand l'utilisateur navigue vers un device dans la liste :
1. L'app vérifie que `device.local_ip` est dans un sous-réseau privé RFC 1918
2. Elle tente `GET http://<local_ip>:8099/status` avec timeout 2s
3. Si joignable → accès direct (dashboard web lighttpd embarqué, stats, logs)
4. Si non joignable → message "Device hors réseau local"

Aucune route via le VPS pour accéder à un device. Pas de port forwarding.

---

## Implémentations futures

### Notifications push (FCM)
- Dossier dédié : `fcm/`
- Le service `notification` du VPS devra envoyer vers l'API FCM
- L'app s'enregistre et transmet son token FCM au VPS à la connexion
- Types d'alertes : SL/TP déclenché, signal fort, device offline, erreur critique
- Voir `fcm/IMPLEMENTATION_NOTES.md` pour le plan détaillé

---

## Stack technique

| Composant | Technologie | Version cible |
|-----------|-------------|---------------|
| Langage | Kotlin | 2.0+ |
| UI | Jetpack Compose | BOM 2025.x |
| Widgets | Glance API | 1.1+ |
| DI | Hilt | 2.51+ |
| Navigation | Navigation Compose | 2.8+ |
| Réseau | Retrofit + OkHttp | 4.12+ / 5.x |
| DB locale | Room | 2.7+ |
| Stockage sécurisé | EncryptedDataStore | 1.1+ |
| VPN | VpnService Android + WireGuard tunnel | API 26+ |
| Biométrie | BiometricPrompt | AndroidX Biometric |
| Background | WorkManager | 2.9+ |
| Build | Gradle KTS + Version Catalog | 8.x |
| Min SDK | Android 8.0 (API 26) | — |
| Target SDK | Android 15 (API 35) | — |

---

## Démarrage rapide (dev)

```bash
# Ouvrir dans Android Studio (Ladybug ou plus récent)
# ou builder en CLI :
cd app-android
./gradlew assembleDebug

# Tests unitaires
./gradlew test

# Tests instrumentation
./gradlew connectedAndroidTest
```

Variables à configurer dans `app/src/main/res/xml/network_security_config.xml` :
- Empreinte SHA-256 du certificat VPS
- Domaine/IP du VPS

Variables à configurer dans `app/src/debug/kotlin/.../BuildConfig` (via `local.properties`) :
- `VPS_BASE_URL` (ex: `https://10.42.0.1:8013`)
- `WG_VPS_ENDPOINT` (ex: `vps.example.com:51820`)
- `WG_VPS_PUBKEY`
