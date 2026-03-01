# CLAUDE.md — app-android

Instructions pour Claude Code lors du travail sur ce projet.

**Trading Platform Android** — Kotlin + Jetpack Compose, WireGuard VPN intégré, Clean Architecture

---

## 1. RÈGLES IMPÉRATIVES

### NE JAMAIS FAIRE

| Interdit | Pourquoi |
|----------|----------|
| Appel réseau sans vérifier `VpnState.Connected` | Toute communication VPS hors tunnel est interdite |
| Stocker token / clé privée dans `SharedPreferences` | Utiliser `EncryptedDataStore` + Android Keystore |
| Logger un token, clé ou donnée de position | Même en debug — utiliser `[REDACTED]` |
| Accès réseau sur le thread principal | Utiliser `Dispatchers.IO` dans les coroutines |
| `ViewModel` qui accède directement au `Repository` | Passer par un `UseCase` |
| `UseCase` qui accède à Room ou Retrofit directement | Passer par le `Repository` |
| Connexion à une IP device non-RFC-1918 | Accès device limité au LAN local |
| Désactiver certificate pinning | Même pour les tests — utiliser un certificat de test |
| Hardcoder URLs ou clés dans le code source | Utiliser `local.properties` + `BuildConfig` |
| Commiter `local.properties` ou `*.jks` | Ces fichiers sont dans `.gitignore` |
| Mutable `StateFlow` exposé depuis un `ViewModel` | Exposer `StateFlow` immuable uniquement |
| `LaunchedEffect` avec des effets de bord non annulables | Toujours gérer l'annulation de coroutine |

### TOUJOURS FAIRE

| Obligatoire | Comment |
|-------------|---------|
| Vérifier `VpnState` avant appel API | Via `VpnManager.state.value is VpnState.Connected` |
| Utiliser `EncryptedDataStore` pour les secrets | Jamais `SharedPreferences` en clair |
| Passer par Android Keystore pour les clés crypto | `KeyGenerator` avec `AndroidKeyStore` provider |
| Respecter la couche Domain | Logique métier dans `UseCase`, jamais dans `ViewModel` |
| Utiliser `UiState` sealed class | `Loading`, `Success`, `Error` pour chaque screen |
| Annuler les jobs Coroutine dans `onCleared()` | Via `viewModelScope` (automatique) ou `cancel()` |
| Tester les UseCases unitairement | Pas de dépendance Android dans les tests unitaires |
| Vérifier le sous-réseau avant accès device LAN | Fonction `isLocalNetwork(ip: String): Boolean` |
| ProGuard activé en release | Fichier `proguard-rules.pro` maintenu |

---

## 2. ARCHITECTURE

### Structure des packages

```
com.tradingplatform.app/
├── di/                    # Hilt modules (AppModule, NetworkModule, VpnModule, SecurityModule)
├── data/
│   ├── api/               # Interfaces Retrofit (AuthApi, PortfolioApi, DeviceApi, PairingApi)
│   ├── repository/        # Implémentations des Repository interfaces du domaine
│   ├── local/
│   │   ├── db/            # Room : AppDatabase, DAOs, Entities
│   │   └── datastore/     # EncryptedDataStore (tokens, config WireGuard)
│   └── model/             # Data Transfer Objects (DTOs) JSON ↔ API
├── domain/
│   ├── model/             # Domain models (purs Kotlin, sans annotations Android/Retrofit/Room)
│   ├── repository/        # Interfaces Repository (définies dans domain, implémentées dans data)
│   └── usecase/
│       ├── auth/          # LoginUseCase, LogoutUseCase, RefreshTokenUseCase
│       ├── portfolio/     # GetPortfolioUseCase, GetPositionsUseCase
│       ├── device/        # GetDevicesUseCase, GetDeviceStatusUseCase
│       └── pairing/       # StartPairingUseCase, ConfirmPairingUseCase
├── ui/
│   ├── theme/             # Color.kt, Theme.kt, Type.kt (Material 3)
│   ├── navigation/        # AppNavGraph.kt — navigation globale
│   ├── components/        # Composables partagés (LoadingOverlay, ErrorBanner, etc.)
│   └── screens/
│       ├── auth/          # LoginScreen + LoginViewModel
│       ├── dashboard/     # DashboardScreen + DashboardViewModel
│       ├── portfolio/     # PositionsScreen, PositionDetailScreen + ViewModels
│       ├── devices/       # DeviceListScreen, DeviceDetailScreen + ViewModels
│       ├── pairing/       # PairingScreen (scan QR + PIN) + PairingViewModel
│       └── settings/      # VpnSettingsScreen, SecuritySettingsScreen + ViewModels
├── vpn/
│   ├── WireGuardVpnService.kt   # VpnService Android — gère le tunnel
│   ├── WireGuardManager.kt      # API publique : connect(), disconnect(), state: StateFlow<VpnState>
│   ├── WireGuardConfig.kt       # Modèle de config (interface, peer)
│   └── VpnState.kt              # sealed class : Disconnected | Connecting | Connected | Error
├── security/
│   ├── BiometricManager.kt      # Abstraction BiometricPrompt
│   ├── RootDetector.kt          # Détection root (RootBeer)
│   ├── CertificatePinner.kt     # SHA-256 pins du certificat VPS
│   └── KeystoreManager.kt       # Android Keystore : génération et récupération clés
└── widget/
    ├── PnlWidget.kt             # Glance widget P&L
    ├── PositionsWidget.kt       # Glance widget positions
    ├── AlertsWidget.kt          # Glance widget alertes
    ├── SystemStatusWidget.kt    # Glance widget état VPS/devices
    └── WidgetUpdateWorker.kt    # WorkManager periodic task
```

### Flux de données

```
Screen → ViewModel → UseCase → Repository → (API | Room)
                       ↕
                  Domain Model
                       ↕
Screen ← UiState ← ViewModel
```

### Pattern UiState (obligatoire)

```kotlin
// Dans chaque ViewModel :
sealed interface PortfolioUiState {
    data object Loading : PortfolioUiState
    data class Success(val positions: List<Position>) : PortfolioUiState
    data class Error(val message: String) : PortfolioUiState
}

private val _uiState = MutableStateFlow<PortfolioUiState>(PortfolioUiState.Loading)
val uiState: StateFlow<PortfolioUiState> = _uiState.asStateFlow()
```

---

## 3. VPN — RÈGLES SPÉCIFIQUES

- `WireGuardVpnService` est un service Android foreground avec notification persistante
- `WireGuardManager` est injecté par Hilt comme `@Singleton`
- **Avant chaque appel Retrofit** : vérifier `vpnManager.state.value is VpnState.Connected`
  → via un `OkHttp Interceptor` dédié (`VpnRequiredInterceptor`)
- La clé privée WireGuard est générée une seule fois, stockée dans `EncryptedDataStore`,
  protégée par Android Keystore. Elle ne sort jamais de l'app.
- La clé publique est partagée avec le VPS lors du pairing uniquement.

---

## 4. SÉCURITÉ — RÈGLES SPÉCIFIQUES

### Certificate Pinning

```kotlin
// Dans CertificatePinner.kt — ne jamais bypasser
OkHttpClient.Builder()
    .certificatePinner(
        CertificatePinner.Builder()
            .add("vps.example.com", "sha256/<EMPREINTE_DU_CERT_VPS>")
            .build()
    )
```

### Stockage sécurisé

```kotlin
// CORRECT — EncryptedDataStore
val key = MasterKey.Builder(context)
    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
    .build()

// INTERDIT
getSharedPreferences("prefs", MODE_PRIVATE).edit().putString("token", jwt)
```

### Accès device LAN

```kotlin
// Toujours valider avant connexion
fun isLocalNetwork(ip: String): Boolean {
    val addr = InetAddress.getByName(ip)
    return addr.isSiteLocalAddress || addr.isLinkLocalAddress || addr.isLoopbackAddress
}
```

---

## 5. CONVENTIONS

| Élément | Convention | Exemple |
|---------|------------|---------|
| Fichiers Kotlin | PascalCase | `LoginViewModel.kt` |
| Composables | PascalCase, paramètre `modifier: Modifier = Modifier` | `PositionCard(...)` |
| ViewModels | Suffix `ViewModel` | `DashboardViewModel` |
| UseCases | Verbe + suffix `UseCase` | `GetPositionsUseCase` |
| Repositories | Suffix `Repository` | `PortfolioRepository` |
| Flows/StateFlow | Préfixe `_` (private mutable) | `_uiState` / `uiState` |
| Constantes | `UPPER_SNAKE_CASE` dans companion object | `TOKEN_TTL_MS` |
| Tests | Suffix `Test` | `LoginUseCaseTest.kt` |

---

## 6. TESTS

```bash
# Tests unitaires (JVM, rapides)
./gradlew test

# Tests instrumentation (émulateur/device requis)
./gradlew connectedAndroidTest

# Coverage
./gradlew jacocoTestReport
```

Structure :
- `test/` — UseCases, ViewModels (Mockk + Turbine pour StateFlow)
- `androidTest/` — UI tests Compose (ComposeTestRule), Room DAOs

Objectif couverture : ≥ 80% sur `domain/usecase/` et `ui/screens/` ViewModels.

---

## 7. BUILD & RELEASE

```bash
# Debug
./gradlew assembleDebug

# Release (nécessite keystore configuré dans local.properties)
./gradlew assembleRelease

# Lint
./gradlew lint

# Détection vulnérabilités dépendances
./gradlew dependencyCheckAnalyze
```

`local.properties` (non commité) :
```properties
VPS_BASE_URL=https://10.42.0.1:8013
WG_VPS_ENDPOINT=vps.example.com:51820
WG_VPS_PUBKEY=<clé_publique_wg_du_vps>
CERT_PIN_SHA256=sha256/<empreinte>
KEYSTORE_PATH=../keystore/release.jks
KEYSTORE_PASSWORD=...
KEY_ALIAS=...
KEY_PASSWORD=...
```

---

## 8. RÉFÉRENCES

| Sujet | Fichier / Doc |
|-------|---------------|
| Plan de pairing mobile ↔ VPS | `/home/thomas/Codes/trading-platform/PAIRING-PLAN.md` |
| API edge-control (heartbeats, commandes, OTA) | `trading-platform/services/edge-control/README.md` |
| API Gateway (portfolio, stratégies) | `trading-platform/docs/08_reference/API_ENDPOINTS.md` |
| Notifications futures (FCM) | `app-android/fcm/IMPLEMENTATION_NOTES.md` |
| Dashboard web device (stats, logs) | `radxa-ramboot/rootfs/overlay/var/www/` |
