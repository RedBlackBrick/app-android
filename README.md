# Trading Platform — Application Android

Application Android native (Kotlin + Jetpack Compose) pour la gestion et la surveillance
de la plateforme de trading algorithmique.

---

## Statut de l'implémentation

| Fonctionnalité | Statut | Description |
|----------------|--------|-------------|
| Authentification sécurisée | ✅ Implémenté | Login JWT + TOTP 2FA + biométrie (5 min inactivité) |
| Tunnel WireGuard intégré | ✅ Implémenté | VpnService Android foreground — pas d'app externe |
| Dashboard investissements | ✅ Implémenté | P&L, positions ouvertes, cours live (polling REST 30s + mises à jour portfolio temps réel via WebSocket) |
| Pairing device Radxa | ✅ Implémenté | Scan QR VPS + QR Radxa (ordre libre) + PIN LAN |
| Accès direct device (LAN) | ✅ Implémenté | isLocalNetwork() guard RFC-1918, HTTP LAN uniquement |
| Widgets écran d'accueil | ✅ Implémenté | 5 widgets Glance, WorkManager 15 min, cache Room |
| Notifications push (FCM) | ✅ Implémenté | FCM → Room → AlertListScreen (deep link) |
| Gestion stratégies / seuils | ⏳ Futur | Non couvert dans cette version |

---

## Sécurité — Principes non négociables

1. **VPN obligatoire** — toute communication avec le VPS passe par le tunnel WireGuard intégré.
   `VpnRequiredInterceptor` bloque les appels si le tunnel n'est pas actif.
2. **Certificate pinning** — les certificats du VPS sont épinglés via OkHttp `CertificatePinner`
   (SHA-256 principal + backup, depuis `local.properties`). Une réponse d'un tiers est rejetée.
3. **Stockage chiffré** — JWT, clés WireGuard et credentials stockés dans `EncryptedDataStore`
   (AES-256-GCM, clé dans Android Keystore, jamais exportable). Corruption gérée (Keystore invalidé).
4. **Biométrie** — déverrouillage requis après 5 minutes d'inactivité (`dispatchTouchEvent` timer).
   `KeyPermanentlyInvalidatedException` → régénération clé + re-enrôlement.
5. **Anti-capture** — `FLAG_SECURE` sur `MainActivity` empêche les captures d'écran,
   l'enregistrement d'écran, et masque le contenu dans le sélecteur d'applications récentes.
6. **Accès device LAN uniquement** — connexion Radxa autorisée uniquement si IP est RFC-1918
   (`isLocalNetwork()` — Patterns.IP_ADDRESS guard + isSiteLocalAddress/isLinkLocalAddress).
7. **Pas de logs sensibles** — tokens, clés privées, session_pin : `[REDACTED]` même en debug.
8. **Root detection** — warning au démarrage si device rooté (RootBeer).
9. **Obfuscation** — ProGuard/R8 activé en release (Timber.d() strippé via `-assumenosideeffects`).

---

## Architecture

Clean Architecture en 3 couches + couches transversales :

```
┌─────────────────────────────────────────────────┐
│                    UI Layer                      │
│  Jetpack Compose screens + ViewModels (MVVM)    │
│  Glance widgets + WorkManager updates            │
└───────────────────┬─────────────────────────────┘
                    │ StateFlow<UiState>
┌───────────────────▼─────────────────────────────┐
│                 Domain Layer                     │
│  UseCases — logique métier pure, testable        │
│  Domain models (séparés des modèles API/DB)      │
└───────────────────┬─────────────────────────────┘
                    │ Repository interfaces + Result<T>
┌───────────────────▼─────────────────────────────┐
│                  Data Layer                      │
│  Repositories (runCatching{} — jamais de throw)  │
│  API (Retrofit + OkHttp — chaîne intercepteurs)  │
│  Local (Room cache TTL + EncryptedDataStore)     │
└─────────────────────────────────────────────────┘

Transversaux :
  vpn/      — WireGuardManager (@Singleton) + WireGuardVpnService (foreground)
  security/ — KeystoreManager, BiometricManager, RootDetector, CertificatePinner, NetworkUtils
  di/       — Hilt modules (App, Security, Vpn, Network, Database, Repository, Widget, WebSocket)
  widget/   — 5 Glance AppWidgets + WidgetUpdateWorker @HiltWorker
  fcm/      — TradingFirebaseMessagingService + FcmTokenManager
  data/websocket/ — PrivateWsClient, WsEvent, WsRepository
```

### Chaîne intercepteurs OkHttp (ordre strict)

```
CsrfInterceptor        → GET /csrf-token (bareHttpClient, Mutex, cache mémoire)
VpnRequiredInterceptor → bloque si VpnState ≠ Connected
AuthInterceptor        → Bearer token + X-App-Version header
TokenAuthenticator     → 401 AUTH_1002 → refresh (Mutex + Deferred, concurrence safe)
HttpLoggingInterceptor → debug uniquement, tokens [REDACTED]
```

### Pattern UiState (obligatoire dans tous les ViewModels)

```kotlin
sealed interface XxxUiState {
    data object Loading : XxxUiState
    data class Success(val data: T) : XxxUiState
    data class Error(val message: String) : XxxUiState
}
```

---

## Navigation

```
LoginScreen
    ├── [totp_enabled] → TotpScreen
    │       └── [2FA OK] → GET /v1/portfolios → Dashboard
    └── [auth OK] → GET /v1/portfolios → DashboardScreen
        ├── Dashboard tab        — P&L summary + cours live
        ├── Positions tab
        │   ├── PositionsScreen
        │   └── PositionDetailScreen
        ├── Devices tab          — admin uniquement (is_admin == true)
        │   ├── DeviceListScreen
        │   ├── EdgeDeviceDashboardScreen  — métriques CPU/RAM/temp/disque + actions reboot/health/update
        │   └── Pairing flow
        │       ScanVpsQrScreen → ScanDeviceQrScreen → PairingProgressScreen → PairingDoneScreen
        ├── Alerts tab
        │   └── AlertListScreen  — FCM → Room (offline-first)
        └── Settings tab
            ├── VpnSettingsScreen
            └── SecuritySettingsScreen
```

---

## Widgets écran d'accueil

5 widgets indépendants (Glance API), rafraîchis par `WidgetUpdateWorker` (WorkManager 15 min) :

| Widget | Données | Cache Room (TTL) |
|--------|---------|-----------------|
| **P&L du jour** (`PnlWidget`) | Gain/perte total en € et % | 5 min |
| **Positions** (`PositionsWidget`) | Top 5 positions ouvertes | 5 min |
| **Alertes** (`AlertsWidget`) | Dernières alertes + badge non lu | permanent (30j/500 max) |
| **État système** (`SystemStatusWidget`) | Health VPS + devices (admin only) | 1 min |
| **Cours rapide** (`QuoteWidget`) | Ticker configurable par instance | 10 min |

- Tous les widgets affichent `synced_at` (ex : "Données du 14:32") — jamais de cours muet
- `SystemStatusWidget` désactivé dans le picker si `is_admin == false` (`PackageManager`)
- `QuoteWidget` : ticker persisté en `SharedPreferences` keyed par `appWidgetId`
- WorkManager : `Result.success()` si VPN absent (cache daté conservé), `Result.retry()` si erreur réseau

---

## Connexion device Radxa (LAN)

Flux de pairing :
1. Scan QR VPS (`pairing://` + `session_id` + `session_pin`) via l'interface admin web
2. Scan QR Radxa (e-ink) : `pairing://radxa?id=…&pub=…&ip=…&port=8099`
3. Validation `isLocalNetwork(ip)` — rejet si IP non-RFC-1918
4. `POST http://radxa_ip:8099/pin {session_id, session_pin}` — HTTP LAN uniquement (TTL 120s)
5. Poll `GET /status` toutes les 2s — terminé quand `paired` ou timeout 120s

La connexion LAN utilise un `OkHttpClient` dédié sans les intercepteurs VPS (pas de CSRF, pas d'Auth).

---

## Stack technique

| Composant | Technologie | Version |
|-----------|-------------|---------|
| Langage | Kotlin | 2.2.20 |
| UI | Jetpack Compose | BOM 2026.02.01 |
| Widgets | Glance API | 1.1.1 |
| DI | Hilt | 2.58 |
| Navigation | Navigation Compose | 2.8.5 |
| Réseau | Retrofit + OkHttp | 2.11.0 / 4.12.0 |
| Sérialisation | Moshi (codegen, pas de réflexion) | 1.15.1 |
| DB locale | Room | 2.7.0 |
| Stockage sécurisé | EncryptedDataStore (security-crypto-ktx) | 1.1.0-alpha06 ⚠ |
| Biométrie | BiometricPrompt (biometric-ktx) | 1.2.0-alpha05 ⚠ |
| VPN | wireguard-android (BoringTun/GoBackend) | 1.0.20230706 |
| Background | WorkManager | 2.10.0 |
| Push | Firebase FCM | BOM 33.7.0 |
| Crashlytics | Firebase Crashlytics | BOM 33.7.0 |
| Sécurité | RootBeer | 0.1.0 |
| Logging | Timber (strippé en release) | 5.0.1 |
| Build | AGP 9.0.1 + Gradle 9.2.1 | — |
| Min SDK | Android 8.0 | API 26 |
| Target SDK | Android 15 | API 35 |

> ⚠ Libs en alpha — ne pas upgrader sans tester (EncryptedDataStore, BiometricPrompt)

---

## Tests

| Scope | Outil | Fichiers |
|-------|-------|---------|
| UseCases (domain) | Mockk + Turbine | `usecase/**/*Test.kt` |
| Intercepteurs OkHttp | MockWebServer | `interceptor/**/*Test.kt` |
| ViewModels | Mockk + Turbine + MainDispatcherRule | `ui/screens/**/*Test.kt` |
| WidgetUpdateWorker | ApplicationProvider (JVM) | `widget/WidgetUpdateWorkerTest.kt` |

```bash
# Tests unitaires
./gradlew test

# Couverture (objectif ≥ 80% sur domain/ + ui/screens/ ViewModels + data/repository/)
./gradlew jacocoTestReport

# Tests instrumentation (émulateur requis — Room DAOs, Compose UI)
./gradlew connectedAndroidTest
```

---

## Démarrage rapide (dev)

```bash
# Copier et remplir les variables de configuration
cp local.properties.example local.properties
# Éditer local.properties avec vos valeurs VPS/WireGuard/keystore

# Build debug
./gradlew assembleDebug

# Lint
./gradlew lint

# Audit vulnérabilités dépendances
./gradlew dependencyCheckAnalyze
```

Clés requises dans `local.properties` :

```properties
VPS_BASE_URL=https://10.42.0.1:443
WG_VPS_ENDPOINT=vps.example.com:51820
WG_VPS_PUBKEY=<clé_publique_wg_du_vps>
CERT_PIN_SHA256=sha256/<empreinte_courante>
CERT_PIN_SHA256_BACKUP=sha256/<empreinte_backup>
# Keystore release (optionnel en dev)
KEYSTORE_PATH=../keystore/release.jks
KEYSTORE_PASSWORD=...
KEY_ALIAS=...
KEY_PASSWORD=...
```

Fichier `google-services.json` requis dans `app/` pour FCM + Crashlytics (non commité).
