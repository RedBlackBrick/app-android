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
│       ├── alerts/        # GetAlertsUseCase, MarkAlertReadUseCase
│       └── pairing/       # ParseVpsQrUseCase, ScanDeviceQrUseCase, SendPinToDeviceUseCase, ConfirmPairingUseCase
├── ui/
│   ├── theme/             # Color.kt, Theme.kt, Type.kt (Material 3)
│   ├── navigation/        # AppNavGraph.kt — navigation globale
│   ├── components/        # Composables partagés (LoadingOverlay, ErrorBanner, etc.)
│   └── screens/
│       ├── auth/          # LoginScreen + LoginViewModel
│       ├── dashboard/     # DashboardScreen + DashboardViewModel
│       ├── portfolio/     # PositionsScreen, PositionDetailScreen + ViewModels
│       ├── devices/       # DeviceListScreen, DeviceDetailScreen + ViewModels
│       ├── pairing/       # ScanVpsQrScreen, ScanDeviceQrScreen, PairingProgressScreen, PairingDoneScreen + PairingViewModel
│       ├── alerts/        # AlertListScreen + AlertsViewModel
│       ├── totp/          # TotpScreen + TotpViewModel (2FA post-login)
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
    ├── SystemStatusWidget.kt    # Glance widget état VPS/devices (admin)
    ├── QuoteWidget.kt           # Glance widget cours rapide 1x1 (ticker configurable)
    └── WidgetUpdateWorker.kt    # WorkManager periodic task (5 min, toutes sources)
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

### Fonctionnalités conditionnelles — compte admin

Le flag `is_admin` (champ `user` dans la réponse login / `/auth/me`) conditionne l'accès à certaines fonctionnalités :

| Feature | Compte standard | Compte admin |
|---------|-----------------|--------------|
| Dashboard (P&L, positions) | ✅ | ✅ |
| Alertes (FCM → Room) | ✅ | ✅ |
| Device list + detail | ❌ masqué | ✅ |
| Pairing workflow | ❌ masqué | ✅ |
| `SystemStatusWidget` | ❌ masqué | ✅ |
| `DevicesWidget` (si créé) | ❌ masqué | ✅ |

- L'onglet Devices dans la navigation est affiché **uniquement si `user.is_admin == true`**
- Le bouton "Ajouter un device" (démarrage pairing) est réservé aux admins
- Les widgets admin sont enregistrés dans le manifest mais restent vides (placeholder) si `is_admin == false`
- Stocker `is_admin` dans `EncryptedDataStore` après login — relire à chaque démarrage

### Glance widgets + Hilt — pattern obligatoire

Les widgets Glance ne supportent pas l'injection Hilt standard. Utiliser `EntryPointAccessors` :

```kotlin
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun getPortfolioUseCase(): GetPortfolioUseCase
    fun getDevicesUseCase(): GetDevicesUseCase       // null-safe si !is_admin
    fun getAlertsUseCase(): GetAlertsUseCase
    fun getMarketDataUseCase(): GetMarketDataUseCase // pour QuoteWidget
}

// Dans chaque GlanceAppWidget :
override suspend fun provideGlance(context: Context, id: GlanceId) {
    val ep = EntryPointAccessors
        .fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
    val positions = ep.getPortfolioUseCase().invoke(portfolioId)
    // ...
}
```

Déclarer `WidgetEntryPoint` dans `di/WidgetModule.kt` ou `di/AppModule.kt`.

### Stratégie de cache Room

Toutes les données affichées offline ou dans les widgets passent par Room. Le `WidgetUpdateWorker`
(WorkManager 5 min, 15 min en Doze) met à jour le cache. Si le VPN est inactif, les écrans
affichent le cache daté sans déclencher d'erreur bloquante.

| Table Room | TTL indicatif | Utilisée par |
|------------|---------------|-------------|
| `positions` | 5 min | PositionsScreen offline, PositionsWidget |
| `pnl_snapshots` | 5 min | DashboardScreen, PnlWidget |
| `alerts` | permanent | AlertListScreen, AlertsWidget |
| `devices` | 1 min | DeviceListScreen offline (admin) |

Le timestamp de dernière sync est stocké avec chaque entité (`synced_at: Long`).
L'UI affiche "Données du HH:mm" si le cache a plus de 10 min.

### Données de marché — stratégie polling

Pas de WebSocket — polling REST uniquement :
- **Dashboard** : `GET /v1/market-data/quote/{symbol}` toutes les **30 secondes** (via `repeatOnLifecycle`)
- **Widgets** : rafraîchissement inclus dans le cycle WorkManager **5 min**

Pas de table Room dédiée pour les cours — données non persistées (toujours fraîches ou absentes).

### Alertes — source de données (FCM → Room)

Les alertes proviennent exclusivement de notifications FCM persistées localement :
- À la réception d'un FCM, stocker dans la table Room `alerts`
- `AlertListScreen` et `AlertsWidget` lisent `alerts` en local (fonctionne offline)
- Pas d'endpoint VPS de listing — historique local uniquement

```
domain/usecase/alerts/
├── GetAlertsUseCase    — Flow<List<Alert>> depuis Room
└── MarkAlertReadUseCase — marque une alerte comme lue (Room update)
```

### Pattern Result<T> — Repository et UseCase (obligatoire)

```kotlin
// Toutes les méthodes des interfaces Repository retournent Result<T> (stdlib Kotlin)
// Jamais de throw directement depuis un Repository — toujours wrapper dans runCatching {}
interface PortfolioRepository {
    suspend fun getPositions(portfolioId: Int, status: PositionStatus): Result<List<Position>>
    suspend fun getPnl(portfolioId: Int, period: PnlPeriod): Result<PnlSummary>
}

// UseCase : propage Result<T> sans transformation (sauf logique métier)
class GetPositionsUseCase @Inject constructor(private val repo: PortfolioRepository) {
    suspend operator fun invoke(portfolioId: Int): Result<List<Position>> =
        repo.getPositions(portfolioId, PositionStatus.OPEN)
}

// ViewModel : convertit Result en UiState
viewModelScope.launch {
    _uiState.value = PortfolioUiState.Loading
    getPositionsUseCase(portfolioId)
        .onSuccess { _uiState.value = PortfolioUiState.Success(it) }
        .onFailure { _uiState.value = PortfolioUiState.Error(it.localizedMessage ?: "Erreur") }
}
```

---

## 3. VPN — RÈGLES SPÉCIFIQUES

- **Bibliothèque WireGuard : `wireguard-android`** (officielle, maintenue par l'équipe WireGuard)
  Fournit `Tunnel`, `Backend` (userspace BoringTun) et `GoBackend` — s'interface directement avec `VpnService`.
  À ajouter dans `build.gradle.kts` : `implementation("com.wireguard.android:tunnel:1.0.20230706")`
- `WireGuardVpnService` est un service Android foreground avec notification persistante
- `WireGuardManager` est injecté par Hilt comme `@Singleton`
- **Avant chaque appel Retrofit** : vérifier `vpnManager.state.value is VpnState.Connected`
  → via un `OkHttp Interceptor` dédié (`VpnRequiredInterceptor`)
- La clé privée WireGuard est générée une seule fois, stockée dans `EncryptedDataStore`,
  protégée par Android Keystore. Elle ne sort jamais de l'app.
- La clé publique est partagée avec le VPS lors du pairing uniquement.

### Chaîne d'intercepteurs OkHttp (ordre obligatoire)

```
CsrfInterceptor           → récupère et injecte le token CSRF (header X-CSRF-Token)
VpnRequiredInterceptor    → bloque si VpnState ≠ Connected
AuthInterceptor           → injecte Authorization: Bearer <access_token>
TokenAuthenticator        → sur 401 AUTH_1002 : refresh puis retry
HttpLoggingInterceptor    → debug uniquement, tokens [REDACTED]
```

Le middleware CSRF du VPS **ne fait pas d'exemption** sur les requêtes Bearer — le `CsrfInterceptor` est obligatoire pour tous les `POST/PUT/DELETE/PATCH`.

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

### Verrou biométrique — comportement (Option B1 : validité Keystore 5 min)

- Clé Keystore créée avec `setUserAuthenticationValidityDurationSeconds(300)`
- **Déclencheur** : inactivité UI (pas de toucher écran) pendant 5 min — pas un timer absolu
- En cas de verrou : overlay opaque sur l'écran, données non visibles
- L'authentification biométrique réussie déverrouille pour 5 min supplémentaires
- `WireGuardVpnService` reste actif pendant le verrou (service foreground indépendant)
- **Widgets** : n'ont pas de verrou biométrique — ils lisent des données Room en cache via WorkManager
  (le cache Room est mis à jour en arrière-plan par `WidgetUpdateWorker`, pas par l'écran principal)

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

### Conventions UI (design)

| Règle | Détail |
|-------|--------|
| Couleurs | Toujours `MaterialTheme.colorScheme.*` ou `LocalExtendedColors.current.*` |
| Pas de couleur hardcodée | Interdire `Color(0xFF...)` dans les Composables |
| Police Inter | Configurée dans `TradingTypography` — s'applique automatiquement |
| Valeurs monétaires | `FontFamily.Monospace` + `fontFeatureSettings = "tnum"`, aligné à droite |
| P&L positif/négatif | `LocalExtendedColors.current.pnlPositive/pnlNegative` selon signe `BigDecimal` |
| Spacing | `Spacing.lg` (16dp) par défaut — via `Spacing.*` (jamais de `.dp` hardcodé) |
| Dark mode | Tester chaque composant en light et dark avant de le merger |
| DynamicColor | Désactivé — thème fixe pour cohérence avec le web |

Référence complète : `docs/design-system.md`

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

## 8. PAIRING — RÈGLES SPÉCIFIQUES

### Mode app Android dans le flux de pairing

L'app joue un rôle de **pont sécurisé** entre le VPS (qui génère le PIN) et la Radxa (qui
l'applique). Elle ne stocke jamais le `session_pin` — elle le transmet en un seul appel LAN.

```
VPS QR → scan → {session_id, session_pin, device_wg_ip}
Radxa QR → scan → {device_id, wg_pubkey, local_ip:8099}
App → POST http://radxa_ip:8099/pin {session_id, session_pin}  (LAN direct)
App → poll GET http://radxa_ip:8099/status jusqu'à "paired"
```

**Règles critiques :**
- Valider que `radxa_ip` est RFC-1918 avant d'envoyer le PIN (`isLocalNetwork()`)
- Le `session_pin` ne doit jamais être loggé (`[REDACTED]` si debug nécessaire)
- La connexion vers `radxa_ip:8099` doit être faite uniquement si `VpnState.Connected`
  (le VPN garantit qu'on est sur le bon réseau avant de contacter le LAN)
- Timeout 120s sur l'opération complète (durée de vie de la session VPS)

### QR codes

**QR VPS** : affiché sur `(app)/admin/edge-devices/` via `GET /v1/pairing/{session_id}/qr`
```json
{ "session_id": "uuid", "session_pin": "472938", "device_wg_ip": "10.42.0.5" }
```

**QR Radxa** : affiché sur l'écran e-ink du device
```
pairing://radxa?id={device_id}&pub={wg_pubkey}&ip={local_ip}&port=8099
```

L'app scanne les deux QR dans n'importe quel ordre, puis connecte les infos.

> **Notes d'implémentation :**
> - `device_wg_ip` (du QR VPS) est affiché à l'utilisateur pour confirmation uniquement — il n'est envoyé ni à la Radxa ni au VPS par l'app.
> - `wg_pubkey` dans le QR Radxa est la clé publique **complète** (44 chars base64). La mention "tronquée" ne s'applique qu'à l'affichage sur l'e-ink, pas aux données QR.
> - La connexion vers `radxa_ip:8099` est **HTTP non chiffré** (LAN uniquement). Le `session_pin` transite en clair sur ce tronçon — risque accepté (LAN requis, TTL 120s, 3 tentatives VPS).

### UseCases à créer

```
domain/usecase/pairing/
├── ParseVpsQrUseCase            — parse QR VPS → PairingSession(session_id, session_pin, device_wg_ip)
├── ScanDeviceQrUseCase          — parse QR Radxa → DevicePairingInfo(device_id, wg_pubkey, local_ip)
├── SendPinToDeviceUseCase       — POST http://radxa_ip:8099/pin avec {session_id, session_pin}
└── ConfirmPairingUseCase        — poll GET http://radxa_ip:8099/status jusqu'à "paired" | "failed" (timeout 120s)
```

### Screens à créer

```
ui/screens/pairing/
├── ScanVpsQrScreen      — caméra → parse QR VPS
├── ScanDeviceQrScreen   — caméra → parse QR Radxa
├── PairingProgressScreen — progression temps réel (3 étapes animées)
└── PairingDoneScreen    — succès ✓ / échec avec retry
```

---

## 9. RÉFÉRENCES

| Sujet | Fichier / Doc |
|-------|---------------|
| Plan de pairing complet (VPS + sécurité) | `/home/thomas/Codes/trading-platform/PAIRING-PLAN.md` |
| Plan de pairing côté Radxa (HAT UI, pages e-ink) | `/home/thomas/Codes/radxa-ramboot/PAIRING-PLAN.md` |
| API edge-control (heartbeats, commandes, OTA) | `trading-platform/services/edge-control/README.md` |
| API Gateway (portfolio, stratégies) | `trading-platform/docs/08_reference/API_ENDPOINTS.md` |
| Notifications futures (FCM) | `app-android/fcm/IMPLEMENTATION_NOTES.md` |
| Dashboard web device (stats, logs) | `radxa-ramboot/rootfs/overlay/var/www/` |
| Contrats API Android (payloads, types, tokens) | `app-android/docs/api-contracts.md` |
| Setup Gradle (version catalog complet) | `app-android/docs/gradle-setup.md` |
| Décisions d'architecture (session, CSRF, biométrie) | `app-android/docs/architecture-decisions.md` |
| Design system (couleurs, typo, spacing — cohérence web) | `app-android/docs/design-system.md` |

---

## 10. BUILD — MANIFEST ET DÉPENDANCES

### Permissions (AndroidManifest.xml)

```xml
<!-- Réseau -->
<uses-permission android:name="android.permission.INTERNET" />
<!-- VPN foreground service -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<!-- Caméra (scan QR pairing) -->
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera" android:required="false" />
<!-- Biométrie -->
<uses-permission android:name="android.permission.USE_BIOMETRIC" />
<!-- Notifications (Android 13+, API 33+) -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<!-- WorkManager — relance après reboot -->
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.VIBRATE" />
```

### Déclarations de services (AndroidManifest.xml)

```xml
<!-- WireGuardVpnService — foregroundServiceType="specialUse" obligatoire Android 14+ (API 34) -->
<service
    android:name=".vpn.WireGuardVpnService"
    android:permission="android.permission.BIND_VPN_SERVICE"
    android:foregroundServiceType="specialUse"
    android:exported="false">
    <intent-filter>
        <action android:name="android.net.VpnService" />
    </intent-filter>
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="VPN tunnel WireGuard — toutes les requêtes API passent par ce tunnel" />
</service>
<!-- Widgets Glance : déclarer chaque AppWidgetProvider ici (1 <receiver> par widget) -->
<!-- WorkManager : ne pas déclarer manuellement, géré par la lib -->
```

### network_security_config.xml (`app/src/main/res/xml/`)

Le certificate pinning est géré **uniquement via OkHttp** (`CertificatePinner` + `BuildConfig`),
pas via ce fichier (les valeurs `local.properties` ne peuvent pas être injectées dans des XML
Android au build time). Ce fichier se limite au blocage cleartext.

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <!-- Cleartext permis globalement : nécessaire pour POST http://radxa_ip:8099/pin
         (pairing LAN). Les IPs Radxa sont RFC-1918 dynamiques, non listables statiquement.
         Risque atténué : VpnRequiredInterceptor exige le tunnel actif, isLocalNetwork()
         valide l'IP avant envoi. Décision architecture §C = Option 1. -->
    <base-config cleartextTrafficPermitted="true" />
</network-security-config>
```

### Dépendances — voir `docs/gradle-setup.md`

> ⚠ Libs en alpha — **ne pas upgrader sans tester** :
> - `androidx.security:security-crypto-ktx:1.1.0-alpha06` (EncryptedDataStore)
> - `androidx.biometric:biometric-ktx:1.2.0-alpha05` (BiometricPrompt)

---

## 11. SESSION ET PERSISTANCE

### Principe général

L'utilisateur **ne doit jamais avoir à se reconnecter** tant que son refresh token est valide.
Le token refresh est renouvelé silencieusement en arrière-plan par OkHttp — l'app et les widgets
restent fonctionnels sans interaction utilisateur.

### EncryptedCookieJar — refresh token httpOnly

Le `refresh_token` est un cookie httpOnly (non accessible en JS/Kotlin directement).
Il est persisté via un `CookieJar` OkHttp qui écrit dans `EncryptedDataStore` :

```kotlin
class EncryptedCookieJar(private val dataStore: EncryptedDataStore) : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        // Persister uniquement les cookies de /v1/auth/* dans EncryptedDataStore
        if (url.pathSegments.contains("auth")) {
            cookies.forEach { dataStore.save("cookie_${it.name}", it.toString()) }
        }
    }
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        // Restaurer pour /v1/auth/refresh uniquement
        return if (url.pathSegments.contains("refresh")) dataStore.loadCookies() else emptyList()
    }
}
```

### CsrfInterceptor — token double-submit

Le VPS requiert un token CSRF pour tous les `POST/PUT/DELETE/PATCH` (pas d'exemption Bearer).

```kotlin
class CsrfInterceptor(private val authApi: AuthApi) : Interceptor {
    @Volatile private var csrfToken: String? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method in listOf("POST", "PUT", "DELETE", "PATCH")) {
            val token = csrfToken ?: fetchCsrfToken()  // GET /csrf-token
            return chain.proceed(request.newBuilder()
                .header("X-CSRF-Token", token)
                .build())
        }
        return chain.proceed(request)
    }
}
```

- Le token CSRF est mis en cache en mémoire (durée de vie = session)
- Sur réponse `403` CSRF invalide : refetch et retry une fois

### Token refresh transparent — TokenAuthenticator

```
[Requête API] → 401 AUTH_1002
    → TokenAuthenticator.authenticate()
        → POST /v1/auth/refresh (cookie envoyé automatiquement par EncryptedCookieJar)
        → Succès : nouveau access_token → retry requête originale
        → Échec 401 AUTH_1003 : logout forcé → LoginScreen
```

Grace period 5s pour requêtes concurrentes (un seul refresh en vol via `Mutex`).

### Découverte du portfolio_id

Après login, avant d'afficher le Dashboard :
1. `POST /v1/auth/login` → `user.id`, `user.is_admin`, `tokens.access_token`
2. `GET /v1/portfolios` → liste des portfolios → stocker `portfolioId` dans `EncryptedDataStore`
3. Si `user.totp_enabled == true` → naviguer vers `TotpScreen` avant l'étape 2

Le `portfolioId` est réutilisé pour tous les appels portfolio sans re-fetch.

### 2FA — flow TotpScreen

```
LoginScreen → [auth OK + totp_enabled] → TotpScreen → [POST /v1/auth/2fa/verify OK]
    → GET /v1/portfolios → Dashboard
```

`TotpScreen` reçoit le `session_token` (issu de la réponse login TOTP) via navigation args.
`TotpViewModel` expose un `UiState` : `AwaitingInput | Verifying | Success | Error`.

### Widgets — accès sans verrou biométrique

Les widgets Glance accèdent aux données via `WidgetUpdateWorker` (WorkManager périodique) :
- Le Worker lit l'access token depuis `EncryptedDataStore` et fait les appels API
- Si le token est expiré, `TokenAuthenticator` le renouvelle silencieusement
- **Aucune biométrie** n'est demandée depuis un widget — l'utilisateur voit les données en cache
- En cas d'erreur auth non récupérable : le widget affiche "Session expirée — ouvrez l'app"

### Clés EncryptedDataStore (référence)

| Clé | Contenu |
|-----|---------|
| `auth_access_token` | JWT access token (Bearer) |
| `auth_user_id` | `user.id` (Long) |
| `auth_is_admin` | `user.is_admin` (Boolean) |
| `auth_portfolio_id` | `portfolioId` (Int) |
| `wg_private_key` | Clé privée WireGuard (base64) |
| `wg_config` | Config WireGuard complète (JSON) |
| `cookie_*` | Cookies auth (refresh token httpOnly) |
