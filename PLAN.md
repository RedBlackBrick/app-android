# Plan d'implémentation — app-android

Chaque phase liste ses agents, les fichiers à créer, le contexte à lire, et les consignes.
Les agents d'une même phase sont parallélisables sauf mention contraire.

---

## Phase 0 — Build Infrastructure (1 agent, séquentiel)

> **Bloquant** : tout le reste en dépend. Doit compiler à vide.

### Agent 0A : Projet Android + Gradle

**Contexte à lire :**
- `CLAUDE.md` §7 (Build & Release), §11 (Build — Manifest), §10 (Références pour les chemins)
- `docs/gradle-setup.md` (intégralité — version catalog, plugins, notes KSP/Hilt/Room/Compose BOM)

**Fichiers à créer :**
```
gradle/libs.versions.toml          ← copier le contenu exact de docs/gradle-setup.md
settings.gradle.kts
build.gradle.kts                   ← root : plugins AGP/Kotlin/Hilt/KSP apply false, OWASP depcheck
app/build.gradle.kts               ← toutes dépendances, buildConfigFields, KSP, Room schema, signing
gradle.properties                  ← org.gradle.jvmargs, android.useAndroidX, android.nonTransitiveRClass
app/src/main/AndroidManifest.xml   ← permissions, services, receivers (stubs), network_security_config
app/src/main/res/xml/network_security_config.xml
app/proguard-rules.pro             ← copier les règles de CLAUDE.md §11 (inclut Timber strip)
dependency-check-suppressions.xml  ← fichier vide ou squelette XML
app/schemas/                       ← répertoire vide (Room schema export)
.gitignore                         ← ajouter local.properties, *.jks, google-services.json
```

**Consignes :**
- Le projet doit compiler avec `./gradlew assembleDebug` (même si vide)
- `buildConfigField` pour `VPS_BASE_URL`, `CERT_PIN_SHA256`, `CERT_PIN_SHA256_BACKUP`, `WG_VPS_ENDPOINT`, `WG_VPS_PUBKEY`
- Créer un `local.properties.example` (sans secrets) pour documenter les clés attendues
- `AndroidManifest.xml` doit déclarer le `WireGuardVpnService` avec `foregroundServiceType="specialUse"` et `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`
- `minSdk = 26`, `targetSdk = 34`, `compileSdk = 35`
- Hilt : déclarer `@HiltAndroidApp` sur une classe `TradingApplication` stub
- Room : `schemaDirectory("$projectDir/schemas")`

**Critère de validation :** `./gradlew assembleDebug` passe sans erreur.

---

## Phase 1 — Fondations (4 agents en parallèle)

> Aucune dépendance mutuelle entre B, C, F et G. Tous dépendent uniquement de la Phase 0.

### Agent 1B : Theme + Design System

**Contexte à lire :**
- `docs/design-system.md` (intégralité — couleurs, typo, spacing, shapes, elevation, motion, animations)
- `CLAUDE.md` §5 (Conventions UI)

**Fichiers à créer :**
```
app/src/main/res/font/inter_regular.ttf
app/src/main/res/font/inter_medium.ttf
app/src/main/res/font/inter_semibold.ttf
app/src/main/res/font/inter_bold.ttf
app/src/main/res/font/jetbrainsmono_regular.ttf
app/src/main/res/font/jetbrainsmono_medium.ttf
ui/theme/Color.kt               ← LightColorScheme + DarkColorScheme (valeurs hex exactes du doc)
ui/theme/ExtendedColors.kt      ← data class + lightExtendedColors + darkExtendedColors + LocalExtendedColors
ui/theme/Type.kt                ← interFontFamily, jetBrainsMonoFamily, TradingTypography (TOUS les slots M3)
ui/theme/Shape.kt               ← TradingShapes
ui/theme/Spacing.kt             ← object Spacing
ui/theme/Elevation.kt           ← object Elevation
ui/theme/Motion.kt              ← object Motion (durées + easing)
ui/theme/Theme.kt               ← TradingPlatformTheme (pas de DynamicColor)
```

**Consignes :**
- Télécharger les `.ttf` depuis fonts.google.com (Inter) et jetbrains.com/lp/mono/
- Configurer TOUS les slots M3 dans `TradingTypography` (même displayLarge/displayMedium)
- `pnlColor()` : deux versions (Composable + non-Composable avec paramètres explicites)
- `DynamicColor` désactivé — thème fixe
- Ne pas utiliser `FontFamily.Monospace` — toujours `jetBrainsMonoFamily`
- Ne pas hardcoder de couleurs dans les Composables — toujours via `MaterialTheme.colorScheme` ou `LocalExtendedColors.current`

---

### Agent 1C : Domain Models

**Contexte à lire :**
- `docs/api-contracts.md` (intégralité — types, payloads, conventions)
- `CLAUDE.md` §2 (structure des packages domain/)

**Fichiers à créer :**
```
domain/model/User.kt             ← id: Long, email, firstName, lastName, isAdmin, totpEnabled
domain/model/AuthTokens.kt       ← accessToken, tokenType, expiresIn
domain/model/Portfolio.kt         ← id: Int, name, currency
domain/model/Position.kt          ← id: Int, symbol, quantity: BigDecimal, avgPrice, currentPrice, unrealizedPnl, unrealizedPnlPercent: Double, status, openedAt: Instant
domain/model/PositionStatus.kt    ← enum : OPEN, CLOSED, ALL
domain/model/PnlSummary.kt        ← realizedPnl, unrealizedPnl, totalPnl: BigDecimal, totalPnlPercent: Double, tradesCount, winningTrades, losingTrades: Int
domain/model/PnlPeriod.kt         ← enum : DAY, WEEK, MONTH, YEAR, ALL
domain/model/NavSummary.kt        ← nav, cash, positionsValue: BigDecimal, timestamp: Instant
domain/model/Transaction.kt       ← id: Long, symbol, action, quantity, price, commission, total: BigDecimal, executedAt: Instant
domain/model/Quote.kt             ← symbol, price, bid, ask: BigDecimal, volume: Long, change, changePercent: Double, timestamp: Instant, source
domain/model/Device.kt            ← id: String (UUID), name, status: DeviceStatus, wgIp, lastHeartbeat: Instant
domain/model/DeviceStatus.kt      ← enum : ONLINE, OFFLINE
domain/model/Alert.kt             ← id: Long, title, body, type: AlertType, receivedAt: Instant, read: Boolean
domain/model/AlertType.kt         ← enum selon les types FCM prévus
domain/model/PairingSession.kt    ← sessionId: String, sessionPin: String, deviceWgIp: String
domain/model/DevicePairingInfo.kt  ← deviceId: String, wgPubkey: String, localIp: String, port: Int
domain/model/PairingStatus.kt     ← enum : PENDING, PAIRED, FAILED
```

**Consignes :**
- **Pur Kotlin** — aucune annotation Android, Room, Moshi ou Retrofit
- Prix et montants en `BigDecimal` — jamais `Double` (sauf pourcentages)
- Dates en `java.time.Instant`
- IDs : `Long` pour user/order, `Int` pour portfolio/position/strategy, `String` pour UUIDs (device, session)

---

### Agent 1F : Security Layer

**Contexte à lire :**
- `CLAUDE.md` §4 (Sécurité — certificate pinning, stockage, biométrie, root detection, accès LAN)
- `docs/architecture-decisions.md` §B (biométrie)

**Fichiers à créer :**
```
security/KeystoreManager.kt       ← Android Keystore : generateKey(), getKey(), regenerateKey()
                                     setUserAuthenticationValidityDurationSeconds(300)
                                     catch KeyPermanentlyInvalidatedException
security/BiometricManager.kt      ← abstraction BiometricPrompt, authenticate(), isAvailable()
security/RootDetector.kt           ← wrapper RootBeer : isRooted(): Boolean
security/CertificatePinner.kt     ← buildCertificatePinner() avec pin principal + backup depuis BuildConfig
```

**Consignes :**
- `KeystoreManager` : provider `"AndroidKeyStore"`, schéma AES256_GCM
- `CertificatePinner` : deux pins obligatoires (`BuildConfig.CERT_PIN_SHA256` + `BuildConfig.CERT_PIN_SHA256_BACKUP`)
- `BiometricManager` : gestion `KeyPermanentlyInvalidatedException` → `regenerateKey()` + re-enrollment
- `RootDetector` : utiliser `RootBeer` — simple wrapper, pas de logique complexe
- Fonction utilitaire `isLocalNetwork(ip: String): Boolean` (Patterns.IP_ADDRESS guard + isSiteLocalAddress/isLinkLocalAddress/isLoopbackAddress)

---

### Agent 1G : VPN Layer (partie 1 — modèles)

**Contexte à lire :**
- `CLAUDE.md` §3 (VPN — règles spécifiques)

**Fichiers à créer :**
```
vpn/VpnState.kt                   ← sealed class : Disconnected, Connecting, Connected, Error(message)
vpn/WireGuardConfig.kt             ← data class config (interface + peer)
vpn/VpnNotConnectedException.kt    ← Exception métier (pas IOException) pour catch distinct dans Worker
```

**Consignes :**
- `VpnState` est une sealed class, pas un enum — les états Error et Connected peuvent porter des données
- `VpnNotConnectedException` doit étendre `Exception` (pas `IOException`) pour permettre le catch distinct dans `WidgetUpdateWorker`
- `WireGuardConfig` : modèle de données pur, pas de logique réseau

---

## Phase 2 — Couche données + VPN complet (partiellement parallèle)

> **Dépendances :** D dépend de C. H dépend de C + F. G-complet dépend de F. E dépend de D.
> **Parallélisable :** D, H et G-complet peuvent démarrer ensemble dès que C et F sont terminés. E démarre après D.

### Agent 2D : Repository Interfaces (domain)

**Dépend de :** Phase 1C (domain models)

**Contexte à lire :**
- `CLAUDE.md` §2 (Pattern Result<T>, structure domain/repository/)
- `docs/api-contracts.md` (signatures des endpoints → signatures des méthodes)

**Fichiers à créer :**
```
domain/repository/AuthRepository.kt        ← login, logout, verify2fa, getPortfolios : Result<T>
domain/repository/PortfolioRepository.kt   ← getPositions, getPnl, getNav, getTransactions : Result<T>
domain/repository/MarketDataRepository.kt  ← getQuote : Result<Quote>
domain/repository/DeviceRepository.kt      ← getDevices, getDeviceStatus : Result<T>
domain/repository/AlertRepository.kt       ← getAlerts : Flow<List<Alert>>, markRead, insertAlert, purgeExpired
domain/repository/PairingRepository.kt     ← sendPin : Result<Unit>, pollStatus : Flow<PairingStatus>
```

**Consignes :**
- Toutes les méthodes retournent `Result<T>` (stdlib Kotlin) — jamais de throw
- `AlertRepository.getAlerts()` retourne un `Flow` (observation Room)
- `PairingRepository.pollStatus()` retourne un `Flow` (polling avec delay 2s)
- Interfaces pures dans `domain/` — pas d'import Android/Retrofit/Room

---

### Agent 2H : Data Layer — DTOs, Room, EncryptedDataStore

**Dépend de :** Phase 1C (domain models) + Phase 1F (KeystoreManager)

**Contexte à lire :**
- `docs/api-contracts.md` (payloads JSON exacts — chaque champ, chaque type)
- `CLAUDE.md` §2 (stratégie cache Room, politique rétention, TTL par table)
- `CLAUDE.md` §12 (EncryptedDataStore — clés de référence, corruption handling)
- `docs/gradle-setup.md` §Moshi (codegen, BigDecimalAdapter)

**Fichiers à créer :**
```
# DTOs (Moshi @JsonClass)
data/model/LoginRequestDto.kt
data/model/LoginResponseDto.kt
data/model/TokenResponseDto.kt
data/model/UserDto.kt
data/model/TotpVerifyRequestDto.kt
data/model/TotpVerifyResponseDto.kt
data/model/PortfolioDto.kt
data/model/PortfolioListResponseDto.kt
data/model/PositionDto.kt
data/model/PositionListResponseDto.kt
data/model/PnlResponseDto.kt
data/model/NavResponseDto.kt
data/model/TransactionDto.kt
data/model/TransactionListResponseDto.kt
data/model/QuoteDto.kt
data/model/DeviceDto.kt
data/model/DeviceListResponseDto.kt
data/model/ApiErrorDto.kt
data/model/BigDecimalAdapter.kt           ← Moshi adapter string ↔ BigDecimal
data/model/InstantAdapter.kt             ← Moshi adapter string ↔ Instant (ISO 8601)

# Room Entities
data/local/db/entity/PositionEntity.kt    ← synced_at: Long obligatoire
data/local/db/entity/PnlSnapshotEntity.kt
data/local/db/entity/AlertEntity.kt
data/local/db/entity/DeviceEntity.kt
data/local/db/entity/QuoteEntity.kt

# DAOs
data/local/db/dao/PositionDao.kt          ← @Insert(REPLACE), @Query deleteOlderThan, getAll Flow
data/local/db/dao/PnlDao.kt
data/local/db/dao/AlertDao.kt             ← purge 30j OR 500 max
data/local/db/dao/DeviceDao.kt
data/local/db/dao/QuoteDao.kt

# Database
data/local/db/AppDatabase.kt              ← @Database avec TOUTES les entities, fallbackToDestructiveMigration (dev)
data/local/db/Converters.kt               ← TypeConverters BigDecimal ↔ String, Instant ↔ Long

# DataStore
data/local/datastore/EncryptedDataStore.kt ← wrapper MasterKey + DataStore, readSecurely() avec catch corruption

# Mappers
data/model/Mappers.kt                     ← DTO ↔ Domain, Entity ↔ Domain (fonctions d'extension)
```

**Consignes :**
- Chaque DTO annoté `@JsonClass(generateAdapter = true)`, champs `@Json(name = "snake_case")`
- `BigDecimalAdapter` : désérialise les strings JSON en `BigDecimal` — jamais de mapping vers `Double`
- Room entities : toujours un champ `synced_at: Long` (timestamp epoch millis)
- DAOs : purge par TTL (voir politique rétention CLAUDE.md §2)
- `EncryptedDataStore.readSecurely()` : wrapper `try/catch` pour `IOException` + `GeneralSecurityException` → retourne `null` (corruption)
- Ne pas utiliser `moshi-kotlin` (réflexion) — uniquement codegen

---

### Agent 2G : VPN Layer (partie 2 — complet)

**Dépend de :** Phase 1F (EncryptedDataStore pour clé WG) + Phase 1G (VpnState, WireGuardConfig)

**Contexte à lire :**
- `CLAUDE.md` §3 (VPN — bibliothèque wireguard-android, WireGuardVpnService foreground, WireGuardManager singleton)

**Fichiers à créer :**
```
vpn/WireGuardManager.kt            ← @Singleton, connect(), disconnect(), state: StateFlow<VpnState>
                                      Lit clé privée depuis EncryptedDataStore, génère si absente
vpn/WireGuardVpnService.kt         ← VpnService Android foreground, notification persistante
                                      foregroundServiceType="specialUse"
```

**Consignes :**
- `WireGuardManager` est `@Singleton` injecté par Hilt
- `state` est un `StateFlow<VpnState>` (private `MutableStateFlow`, public `.asStateFlow()`)
- La clé privée est générée une seule fois, stockée dans `EncryptedDataStore`, jamais loggée
- `WireGuardVpnService` utilise la lib `wireguard-android` (BoringTun/GoBackend)
- Le service reste actif pendant le verrou biométrique (foreground indépendant)

---

### Agent 2E : Domain UseCases + Tests

**Dépend de :** Phase 2D (repository interfaces)

**Contexte à lire :**
- `CLAUDE.md` §2 (Pattern Result<T>, UseCases structure)
- `CLAUDE.md` §8 (Pairing UseCases — ParseVpsQr, ScanDeviceQr, SendPin, ConfirmPairing)
- `CLAUDE.md` §2 (Alertes — GetAlertsUseCase Flow, MarkAlertReadUseCase)
- `CLAUDE.md` §12 (Découverte portfolio_id post-login)

**Fichiers à créer :**
```
# Auth
domain/usecase/auth/LoginUseCase.kt           ← login + store tokens + store user info dans EncryptedDataStore
domain/usecase/auth/LogoutUseCase.kt          ← clear EncryptedDataStore + appel API logout
domain/usecase/auth/Verify2faUseCase.kt       ← POST /v1/auth/2fa/verify
domain/usecase/auth/GetPortfoliosUseCase.kt   ← GET /v1/portfolios → stocker portfolios[0].id

# Portfolio
domain/usecase/portfolio/GetPositionsUseCase.kt
domain/usecase/portfolio/GetPnlUseCase.kt
domain/usecase/portfolio/GetPortfolioNavUseCase.kt
domain/usecase/portfolio/GetTransactionsUseCase.kt

# Market
domain/usecase/market/GetQuoteUseCase.kt

# Devices (admin)
domain/usecase/device/GetDevicesUseCase.kt
domain/usecase/device/GetDeviceStatusUseCase.kt

# Alerts
domain/usecase/alerts/GetAlertsUseCase.kt      ← retourne Flow<List<Alert>> (Room)
domain/usecase/alerts/MarkAlertReadUseCase.kt

# Pairing
domain/usecase/pairing/ParseVpsQrUseCase.kt          ← parse JSON → PairingSession, Result.failure si invalide
domain/usecase/pairing/ScanDeviceQrUseCase.kt         ← parse URI pairing://radxa, validation stricte (scheme, IP, port, pubkey 44 chars)
domain/usecase/pairing/SendPinToDeviceUseCase.kt      ← délègue à PairingRepository.sendPin()
domain/usecase/pairing/ConfirmPairingUseCase.kt       ← poll via withTimeout(120_000), delay(2_000)

# Tests unitaires (Mockk, pas de dépendance Android)
test/.../usecase/auth/LoginUseCaseTest.kt
test/.../usecase/auth/Verify2faUseCaseTest.kt
test/.../usecase/portfolio/GetPositionsUseCaseTest.kt
test/.../usecase/portfolio/GetPnlUseCaseTest.kt
test/.../usecase/pairing/ParseVpsQrUseCaseTest.kt
test/.../usecase/pairing/ScanDeviceQrUseCaseTest.kt
test/.../usecase/pairing/ConfirmPairingUseCaseTest.kt
test/.../usecase/alerts/GetAlertsUseCaseTest.kt
test/.../usecase/market/GetQuoteUseCaseTest.kt
```

**Consignes :**
- Chaque UseCase : une seule méthode `suspend operator fun invoke(...)` retournant `Result<T>`
- Exception : `GetAlertsUseCase` retourne `Flow<List<Alert>>` (observation Room)
- `@Inject constructor(private val repo: XxxRepository)` — injection Hilt
- `ParseVpsQrUseCase` / `ScanDeviceQrUseCase` : logique de parsing pure, pas d'appel réseau
- `ConfirmPairingUseCase` : `withTimeout(120_000)` + `delay(2_000)` entre chaque poll
- Tests : Mockk pour les repos, vérifier les cas success/failure/edge cases
- Ne jamais logger `session_pin` — utiliser `[REDACTED]`

---

## Phase 3 — Network Layer (1 agent, séquentiel)

> L'ordre des intercepteurs est critique. Un seul agent évite les erreurs de wiring.

### Agent 3I : Intercepteurs OkHttp + Retrofit APIs

**Dépend de :** Phase 1F (EncryptedDataStore, CertificatePinner) + Phase 2G (WireGuardManager) + Phase 2H (DTOs)

**Contexte à lire :**
- `CLAUDE.md` §3 (chaîne intercepteurs — ordre obligatoire)
- `CLAUDE.md` §12 (CsrfInterceptor complet, EncryptedCookieJar complet, TokenAuthenticator complet)
- `docs/api-contracts.md` (tous les endpoints → interfaces Retrofit)

**Fichiers à créer :**
```
# Intercepteurs (ordre dans la chaîne OkHttp)
data/api/interceptor/CsrfInterceptor.kt           ← bareHttpClient @Named("bare"), Mutex, runBlocking, timeout 5s
data/api/interceptor/VpnRequiredInterceptor.kt     ← vérifie WireGuardManager.state, lève VpnNotConnectedException
data/api/interceptor/AuthInterceptor.kt            ← injecte Bearer token + header X-App-Version
data/api/interceptor/TokenAuthenticator.kt         ← Mutex + Deferred, applicationScope injecté, catch AUTH_1002/1003
data/api/interceptor/EncryptedCookieJar.kt         ← filtre sur cookie.name == "refresh_token", paths exacts

# APIs Retrofit
data/api/AuthApi.kt                ← POST login, POST refresh, POST logout, GET me, POST 2fa/verify
data/api/PortfolioApi.kt           ← GET portfolios, GET positions, GET pnl, GET nav, GET transactions
data/api/MarketDataApi.kt          ← GET quote/{symbol}
data/api/DeviceApi.kt              ← GET devices
data/api/PairingLanApi.kt          ← POST /pin, GET /status (pas de base URL fixe — dynamique par IP Radxa)

# Tests
test/.../interceptor/CsrfInterceptorTest.kt          ← MockWebServer
test/.../interceptor/VpnRequiredInterceptorTest.kt
test/.../interceptor/AuthInterceptorTest.kt
test/.../interceptor/TokenAuthenticatorTest.kt
test/.../interceptor/EncryptedCookieJarTest.kt
```

**Consignes :**
- **Ordre intercepteurs** : CSRF → VPN → Auth → (TokenAuthenticator) → Logging
- `CsrfInterceptor` : lire csrfToken toujours dans le lock (pas de double-check hors lock), `@Volatile` retiré
- `CsrfInterceptor` : `bareHttpClient` avec `connectTimeout(5s)` + `readTimeout(5s)`
- `TokenAuthenticator` : `applicationScope: CoroutineScope` injecté (`SupervisorJob() + Dispatchers.IO`)
- `TokenAuthenticator` : Mutex + Deferred pour refresh concurrent (pas de delay 5s)
- `EncryptedCookieJar` : filtrer `cookies.filter { it.name == "refresh_token" }` — pas tous les cookies
- `AuthInterceptor` : ajouter header `X-App-Version: {versionCode}` sur toutes les requêtes
- `VpnRequiredInterceptor` : lever `VpnNotConnectedException` (pas `IOException`)
- `PairingLanApi` : URL dynamique (pas de `@BASE_URL`), valider `isLocalNetwork()` avant appel
- Tests MockWebServer obligatoires pour chaque intercepteur

---

## Phase 4 — Repository Implementations (1 agent)

### Agent 4J : Repository Implementations

**Dépend de :** Phase 2D (interfaces) + Phase 2H (DTOs, DAOs, Mappers, EncryptedDataStore) + Phase 3I (APIs Retrofit)

**Contexte à lire :**
- `CLAUDE.md` §2 (Pattern Result<T> — toujours `runCatching {}`)
- `CLAUDE.md` §8 (PairingRepository — OkHttpClient LAN séparé, isLocalNetwork())
- `CLAUDE.md` §2 (Stratégie cache Room — sync API puis persist, TTL)

**Fichiers à créer :**
```
data/repository/AuthRepositoryImpl.kt        ← login: API call + store tokens + store user info
data/repository/PortfolioRepositoryImpl.kt   ← API call + persist Room + return domain model
data/repository/MarketDataRepositoryImpl.kt  ← API call + persist quote Room (pour widgets)
data/repository/DeviceRepositoryImpl.kt      ← API call + persist Room (admin only)
data/repository/AlertRepositoryImpl.kt       ← Room local only (insert depuis FCM, query Flow)
data/repository/PairingRepositoryImpl.kt     ← @Named("lan") OkHttpClient, isLocalNetwork() avant chaque appel
```

**Consignes :**
- Toutes les méthodes wrappent dans `runCatching {}` — jamais de throw direct
- `PairingRepositoryImpl` : `@Named("lan")` OkHttpClient sans intercepteurs VPS
- `PairingRepositoryImpl` : valider `isLocalNetwork(ip)` avant chaque appel, `Result.failure` sinon
- PortfolioRepo, MarketDataRepo, DeviceRepo : pattern API → DTO → Mapper → Domain model + persist Entity Room
- `AlertRepositoryImpl` : pas d'appel API — lecture Room uniquement (`Flow<List<Alert>>`)

---

## Phase 5 — Hilt DI Wiring (1 agent)

### Agent 5K : Modules Hilt

**Dépend de :** Phases 1F, 2G, 2H, 3I, 4J (tout le graph de dépendances)

**Contexte à lire :**
- `CLAUDE.md` §2 (Hilt patterns — @HiltWorker, EntryPointAccessors, WidgetEntryPoint)
- `CLAUDE.md` §3 (chaîne intercepteurs — wiring dans NetworkModule)
- `CLAUDE.md` §12 (TokenAuthenticator — applicationScope)
- `docs/gradle-setup.md` §Hilt+KSP, §buildConfig

**Fichiers à créer :**
```
di/AppModule.kt               ← application-scope CoroutineScope (SupervisorJob + Dispatchers.IO), Timber
di/SecurityModule.kt           ← KeystoreManager, BiometricManager, RootDetector, EncryptedDataStore
di/VpnModule.kt                ← WireGuardManager (@Singleton)
di/NetworkModule.kt            ← @Named("bare") OkHttpClient, OkHttpClient principal (chaîne intercepteurs),
                                  @Named("lan") OkHttpClient, CertificatePinner, EncryptedCookieJar,
                                  Moshi (BigDecimalAdapter + InstantAdapter), Retrofit, toutes les APIs
di/DatabaseModule.kt           ← AppDatabase, tous les DAOs
di/RepositoryModule.kt         ← bind interfaces → implémentations (@Binds)
di/WidgetModule.kt             ← @EntryPoint WidgetEntryPoint (UseCases pour widgets Glance)
```

**Consignes :**
- `NetworkModule` est le module le plus complexe — 3 OkHttpClient distincts :
  - `@Named("bare")` : CertificatePinner only, timeouts 5s/5s — pour CsrfInterceptor
  - Principal : CSRF → VPN → Auth intercepteurs + CertificatePinner + EncryptedCookieJar + TokenAuthenticator
  - `@Named("lan")` : aucun intercepteur, pas de CertificatePinner — pour PairingRepository
- Moshi builder : ajouter `BigDecimalAdapter` + `InstantAdapter` au `Moshi.Builder()`
- `WidgetEntryPoint` : `@EntryPoint @InstallIn(SingletonComponent::class)` — exposer les UseCases nécessaires aux widgets
- `RepositoryModule` : utiliser `@Binds` (pas `@Provides`) pour les interfaces → implémentations

**Critère de validation :** l'app compile et démarre (Hilt graph complet).

---

## Phase 6 — Application + Composants UI partagés (2 agents en parallèle)

### Agent 6L : Application + MainActivity

**Dépend de :** Phase 5K (Hilt)

**Contexte à lire :**
- `CLAUDE.md` §4 (verrou biométrique — dispatchTouchEvent, inactivity timer)
- `CLAUDE.md` §4 (root detection au démarrage)
- `docs/gradle-setup.md` §LeakCanary+Timber (init dans Application)

**Fichiers à créer :**
```
TradingApplication.kt          ← @HiltAndroidApp, Timber.plant(), WorkManager scheduling (WidgetUpdateWorker periodic 5 min)
MainActivity.kt                ← @AndroidEntryPoint, setContent { TradingPlatformTheme { AppNavGraph() } },
                                  dispatchTouchEvent → resetInactivityTimer,
                                  biometric lock overlay,
                                  root detection check au démarrage,
                                  FCM deep link intent handling (navigate_to = "alerts")
```

**Consignes :**
- `MainActivity.dispatchTouchEvent` : reset le timer inactivité à chaque touch
- Inactivité 5 min → `showBiometricLock()` (overlay opaque)
- Auth biométrique réussie → reset les deux mécanismes (timer + Keystore)
- Root detection : vérifier au démarrage, afficher warning si rooté (ne pas bloquer — sideload trusted)
- WorkManager : `PeriodicWorkRequestBuilder<WidgetUpdateWorker>(15, TimeUnit.MINUTES)` avec `Constraints.NETWORK_CONNECTED`
- Lire intent extra `navigate_to` pour deep link FCM → AlertListScreen
- `Timber.plant(DebugTree())` en debug, `CrashlyticsTree()` en release

---

### Agent 6M : Composants UI partagés

**Dépend de :** Phase 1B (theme)

**Contexte à lire :**
- `docs/design-system.md` (patterns P&L, badges, valeurs monétaires, animations)
- `CLAUDE.md` §5 (conventions UI)
- `CLAUDE.md` §9 (accessibilité — contentDescription TalkBack)

**Fichiers à créer :**
```
ui/components/LoadingOverlay.kt         ← CircularProgressIndicator centré, fond semi-transparent
ui/components/ErrorBanner.kt            ← banner error avec message + bouton retry
ui/components/StatusBadge.kt            ← badge coloré selon statut (success/warning/error/info containers)
ui/components/PnlText.kt               ← couleur pnlPositive/pnlNegative auto, JetBrains Mono, tnum
ui/components/MoneyText.kt              ← JetBrains Mono + tnum + alignement droite, signe + ou -
ui/components/CacheTimestamp.kt         ← "Données du HH:mm" si cache > 10 min
ui/components/BiometricLockOverlay.kt   ← overlay opaque plein écran, bouton "Déverrouiller"
ui/components/QrScannerView.kt          ← CameraX Preview + MLKit barcode analysis, callback onQrScanned
```

**Consignes :**
- `PnlText` / `MoneyText` : utiliser `jetBrainsMonoFamily` + `fontFeatureSettings = "tnum"` + `TextAlign.End`
- `StatusBadge` : couleurs via `LocalExtendedColors.current` (successContainer, warningContainer, etc.)
- Accessibilité : `contentDescription` sur PnlText ("Gain non réalisé : ..."), StatusBadge ("Statut : Ouvert")
- `QrScannerView` : wraper CameraX + MLKit, exposer un `onQrScanned: (String) -> Unit`
- Animations : `AnimatedContent` pour transitions d'état (pas `AnimatedVisibility` pour crossfade)
- Tous les composants doivent fonctionner en light ET dark
- Paramètre `modifier: Modifier = Modifier` sur tous les Composables

---

## Phase 7 — Screens (6 agents en parallèle)

> Tous les screens sont indépendants entre eux. Chaque agent crée son ViewModel + ses Screens + ses tests ViewModel.

### Agent 7N : Auth (Login + TOTP)

**Dépend de :** Phase 2E (UseCases auth) + Phase 6M (composants)

**Contexte à lire :**
- `CLAUDE.md` §12 (flow post-login, découverte portfolio_id, 2FA flow)
- `docs/api-contracts.md` §Auth (payloads login, 2fa/verify, erreurs AUTH_1001/1004/1008)

**Fichiers à créer :**
```
ui/screens/auth/LoginViewModel.kt    ← UiState: Idle, Loading, TotpRequired(sessionToken), Success, Error
                                       login → store tokens → if totp_enabled navigate TOTP, else GET portfolios → Dashboard
ui/screens/auth/LoginScreen.kt       ← email + password fields, bouton login, gestion 429 (Retry-After)
ui/screens/totp/TotpViewModel.kt     ← UiState: AwaitingInput, Verifying, Success, Error
ui/screens/totp/TotpScreen.kt        ← 6 digit input, timer session, bouton verify
test/.../auth/LoginViewModelTest.kt
test/.../totp/TotpViewModelTest.kt
```

---

### Agent 7O : Dashboard + Portfolio

**Dépend de :** Phase 2E (UseCases portfolio + market) + Phase 6M (composants)

**Contexte à lire :**
- `CLAUDE.md` §2 (polling Dashboard 30s, gestion exceptions VPN/IO/autres)
- `docs/api-contracts.md` §Portfolio, §Market Data

**Fichiers à créer :**
```
ui/screens/dashboard/DashboardViewModel.kt    ← polling 30s while(isActive), QuoteUiState.Stale pour VPN down
ui/screens/dashboard/DashboardScreen.kt       ← P&L summary, quote en direct, AnimatedContent pour updates
ui/screens/portfolio/PositionsViewModel.kt
ui/screens/portfolio/PositionsScreen.kt        ← liste positions, badge statut, P&L coloré
ui/screens/portfolio/PositionDetailScreen.kt   ← détail position avec transactions
test/.../dashboard/DashboardViewModelTest.kt
test/.../portfolio/PositionsViewModelTest.kt
```

**Consigne spécifique :** le polling Dashboard utilise `while(isActive)` + `delay(30_000)` dans `viewModelScope.launch {}` (init block). Côté UI : `collectAsStateWithLifecycle()`.

---

### Agent 7P : Devices (admin)

**Dépend de :** Phase 2E (UseCases devices) + Phase 6M

**Contexte à lire :**
- `CLAUDE.md` §2 (fonctionnalités conditionnelles admin)
- `docs/api-contracts.md` §Devices Edge

**Fichiers à créer :**
```
ui/screens/devices/DevicesViewModel.kt
ui/screens/devices/DeviceListScreen.kt      ← liste devices, badge online/offline, heartbeat timestamp
ui/screens/devices/DeviceDetailScreen.kt    ← détail device, wg_ip, status
test/.../devices/DevicesViewModelTest.kt
```

---

### Agent 7Q : Pairing (admin)

**Dépend de :** Phase 2E (UseCases pairing) + Phase 6M (QrScannerView)

**Contexte à lire :**
- `CLAUDE.md` §8 (intégralité — state machine PairingStep, validation QR, screens, timeout 120s)

**Fichiers à créer :**
```
ui/screens/pairing/PairingViewModel.kt         ← PairingStep sealed class complète, gestion ordre QR libre
ui/screens/pairing/ScanVpsQrScreen.kt          ← caméra → ParseVpsQrUseCase
ui/screens/pairing/ScanDeviceQrScreen.kt       ← caméra → ScanDeviceQrUseCase
ui/screens/pairing/PairingProgressScreen.kt    ← 3 étapes animées (envoi PIN, attente confirmation, terminé)
ui/screens/pairing/PairingDoneScreen.kt        ← succès ou échec avec retry
test/.../pairing/PairingViewModelTest.kt
```

**Consigne spécifique :** le PairingViewModel gère les deux QR dans n'importe quel ordre (Idle → VpsScanned | DeviceScanned → BothScanned → SendingPin → WaitingConfirmation → Success | Error).

---

### Agent 7R : Alerts

**Dépend de :** Phase 2E (UseCases alerts) + Phase 6M

**Contexte à lire :**
- `CLAUDE.md` §2 (Alertes — FCM → Room, pas d'endpoint VPS)

**Fichiers à créer :**
```
ui/screens/alerts/AlertsViewModel.kt     ← observe Flow<List<Alert>> depuis Room
ui/screens/alerts/AlertListScreen.kt     ← liste alertes, swipe to mark read, badge non lu
test/.../alerts/AlertsViewModelTest.kt
```

---

### Agent 7S : Settings

**Dépend de :** Phase 2G (WireGuardManager) + Phase 1F (BiometricManager) + Phase 6M

**Contexte à lire :**
- `CLAUDE.md` §3 (VPN settings)
- `CLAUDE.md` §4 (Security settings, biométrie)

**Fichiers à créer :**
```
ui/screens/settings/VpnSettingsViewModel.kt
ui/screens/settings/VpnSettingsScreen.kt         ← état VPN, bouton connect/disconnect, config WG
ui/screens/settings/SecuritySettingsViewModel.kt
ui/screens/settings/SecuritySettingsScreen.kt    ← biométrie on/off, note thème fixe, root status
```

---

## Phase 8 — Navigation (1 agent, séquentiel)

### Agent 8T : Navigation Graph

**Dépend de :** Phase 7 (tous les screens doivent exister)

**Contexte à lire :**
- `CLAUDE.md` §2 (fonctionnalités conditionnelles admin — navigation conditionnelle)
- `CLAUDE.md` §12 (flow post-login, 2FA → Dashboard)
- `docs/design-system.md` §Animations (transitions de navigation)

**Fichiers à créer :**
```
ui/navigation/Screen.kt           ← sealed class des routes (Login, Totp, Dashboard, Positions, PositionDetail, Devices, DeviceDetail, Pairing, Alerts, VpnSettings, SecuritySettings)
ui/navigation/BottomNavBar.kt     ← bottom nav : Dashboard, Positions, Alerts, [Devices si admin], Settings
ui/navigation/AppNavGraph.kt      ← NavHost, routes, transitions fadeIn/fadeOut, navigation conditionnelle is_admin
```

**Consignes :**
- L'onglet Devices est affiché uniquement si `is_admin == true` (lu depuis EncryptedDataStore)
- Transitions : `fadeIn(tween(Motion.EnterDuration))` / `fadeOut(tween(Motion.ExitDuration))`
- `TotpScreen` reçoit `session_token` via navigation args
- `PositionDetailScreen` reçoit `positionId` via navigation args
- `DeviceDetailScreen` reçoit `deviceId` via navigation args
- Start destination : `Screen.Login` (si pas de token) ou `Screen.Dashboard` (si token valide)

---

## Phase 9 — Widgets + WorkManager + FCM (2 agents en parallèle)

### Agent 9U : Widgets Glance + WorkManager

**Dépend de :** Phase 2E (UseCases) + Phase 2H (Room DAOs) + Phase 5K (WidgetModule)

**Contexte à lire :**
- `CLAUDE.md` §2 (Glance + Hilt EntryPointAccessors, WidgetUpdateWorker pattern obligatoire, cache Room, politique rétention)
- `CLAUDE.md` §2 (WorkManager contraintes, comportement VPN)
- `CLAUDE.md` §11 (manifest — widget receivers, QuoteWidgetConfigureActivity)

**Fichiers à créer :**
```
# Worker
widget/WidgetUpdateWorker.kt             ← @HiltWorker, sync par section avec try/catch indépendant,
                                           purge après sync, VPN check en entrée

# Widgets (chacun = Widget + Receiver + widget_info.xml)
widget/PnlWidget.kt                     ← affiche P&L total, synced_at timestamp
widget/PnlWidgetReceiver.kt
res/xml/pnl_widget_info.xml
widget/PositionsWidget.kt               ← liste positions top 5, synced_at
widget/PositionsWidgetReceiver.kt
res/xml/positions_widget_info.xml
widget/AlertsWidget.kt                  ← dernières alertes, badge non lu
widget/AlertsWidgetReceiver.kt
res/xml/alerts_widget_info.xml
widget/SystemStatusWidget.kt            ← admin only — état VPS/devices, placeholder si !is_admin
widget/SystemStatusWidgetReceiver.kt
res/xml/system_status_widget_info.xml
widget/QuoteWidget.kt                   ← cours ticker configurable, synced_at
widget/QuoteWidgetReceiver.kt
widget/QuoteWidgetConfigureActivity.kt   ← choix ticker, persist via SharedPreferences keyed appWidgetId
res/xml/quote_widget_info.xml

# Tests
test/.../widget/WidgetUpdateWorkerTest.kt
```

**Consignes :**
- `WidgetUpdateWorker` : check VPN en entrée, `Result.success()` si absent (pas retry)
- Sync par section indépendante (portfolio, alertes, quotes) avec `try/catch` par bloc
- Purge après sync (pas avant) — évite table vide si Worker kill
- `synced_at` affiché dans TOUS les widgets sans exception
- Widgets Glance : utiliser `EntryPointAccessors.fromApplication()` pour l'injection
- `SystemStatusWidget` : affiche "Réservé aux administrateurs" si `!is_admin`
- `QuoteWidgetConfigureActivity` : `prefs.putString("ticker_$appWidgetId", symbol)`

---

### Agent 9V : FCM Service

**Dépend de :** Phase 2H (Room AlertDao) + Phase 5K (Hilt)

**Contexte à lire :**
- `CLAUDE.md` §2 (Alertes — FCM → Room)
- `CLAUDE.md` §9 (FCM deep linking → AlertListScreen)

**Fichiers à créer :**
```
fcm/TradingFirebaseMessagingService.kt   ← onMessageReceived: parse FCM → insert Alert dans Room,
                                           build notification avec PendingIntent → MainActivity (navigate_to=alerts)
fcm/FcmTokenManager.kt                  ← gère le token FCM (enregistrement côté VPS si nécessaire)
```

**Consignes :**
- `onMessageReceived` : persister l'alerte dans Room AVANT d'afficher la notification
- `PendingIntent` : `FLAG_IMMUTABLE`, intent extra `navigate_to = "alerts"`
- Ne pas logger le contenu des alertes en production

---

## Phase 10 — Intégration finale (1 agent)

### Agent 10 : Intégration, Lint, Vérification

**Dépend de :** Toutes les phases précédentes

**Contexte à lire :**
- `CLAUDE.md` (intégralité — vérification de toutes les règles impératives §1)

**Actions :**
- `./gradlew assembleDebug` — compilation complète
- `./gradlew lint` — corriger tous les warnings
- `./gradlew test` — tous les tests unitaires passent
- Vérifier que le manifest déclare tous les services, receivers, activités
- Vérifier que ProGuard rules couvrent toutes les libs
- Vérifier que `.gitignore` exclut `local.properties`, `*.jks`, `google-services.json`
- Vérifier que aucun token/clé n'est hardcodé ou loggé
- Review final des règles §1 (NE JAMAIS / TOUJOURS)

---

## Résumé de parallélisation

```
Phase 0  [A]                                          séquentiel
          |
Phase 1  [B] [C] [F] [G-partiel]                     4 agents en parallèle
          |   |   |       |
Phase 2  [H]←-+---+  [G-complet]  [D]←[C]            3 agents en parallèle (H, G, D)
          |       |                 |
          |       |                [E]                 1 agent (attend D)
          |       |                 |
Phase 3  [I]←-----+--------+-------+                  1 agent
          |
Phase 4  [J]                                          1 agent
          |
Phase 5  [K]                                          1 agent
          |
Phase 6  [L] [M]                                      2 agents en parallèle
          |   |
Phase 7  [N] [O] [P] [Q] [R] [S]                     6 agents en parallèle
          |   |   |   |   |   |
Phase 8  [T]←-+---+---+---+---+                       1 agent
          |
Phase 9  [U] [V]                                      2 agents en parallèle
          |   |
Phase 10 [FINAL]←-+                                   1 agent
```

**Total : 10 phases, ~22 agents, max 6 en parallèle (Phase 7)**
