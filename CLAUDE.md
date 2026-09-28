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

Pour un appel OkHttp brut (hand-built, hors Retrofit) — ex. `MobileProvisioningRepositoryImpl` —
injecter `@IoDispatcher` (`di/DispatcherModule.kt`) et wrapper l'appel bloquant dans
`withContext(io) { ... }`. Les fonctions `suspend` Retrofit dispatchent déjà off-caller
elles-mêmes et n'en ont pas besoin.

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
| Chiffrer les payloads LAN | Via `SealedBoxHelper.seal()` avant envoi HTTP au Radxa |
| ProGuard activé en release | Fichier `proguard-rules.pro` maintenu |

---

## 2. ARCHITECTURE

### Structure des packages

```
com.tradingplatform.app/
├── di/                    # Hilt modules (AppModule, NetworkModule, VpnModule, SecurityModule, WebSocketModule, WidgetModule)
│                          # DispatcherModule fournit @IoDispatcher — pour un appel OkHttp brut hors Retrofit
│                          # (ex. MobileProvisioningRepositoryImpl), jamais pour des fonctions suspend Retrofit
├── data/
│   ├── api/               # Interfaces Retrofit (AuthApi, PortfolioApi, MarketDataApi, DeviceApi, BrokerConnectionApi, PairingApi, NotificationApi, MobileProvisioningApi)
│   │                      # AuthPaths.kt — source unique des chemins PUBLIC / CSRF_EXEMPT / VPN_EXCLUDED / COOKIE_SAVE
│   ├── repository/        # Implémentations des Repository interfaces du domaine
│   ├── local/
│   │   ├── db/            # Room : AppDatabase, DAOs, Entities (dont WatchlistEntity) ; CacheTtl.kt — TTL/rétention centralisés
│   │   └── datastore/     # EncryptedDataStore (tokens, config WireGuard)
│   ├── model/             # Data Transfer Objects (DTOs) JSON ↔ API
│   └── websocket/         # PrivateWsClient, PublicWsClient, WsEvent, WsRepository
├── domain/
│   ├── model/             # Domain models (purs Kotlin, sans annotations Android/Retrofit/Room)
│   │                      # Inclut : PerformanceMetrics, ActivityItem, WsUpdate (OrderUpdate, StrategySignal, CatalystEvent),
│   │                      # BrokerConnection, Cached<T> (valeur + syncedAt), Page<T>, SymbolInfo/SymbolPage
│   ├── repository/        # Interfaces Repository (définies dans domain, implémentées dans data)
│   │                      # Inclut : WatchlistRepository, BrokerConnectionRepository (lecture seule), MobileProvisioningRepository, SetupRepository
│   ├── exception/         # HttpStatusException (isRetryable — pilote les retries FCM/worker), UnrecognizedQrException, etc.
│   ├── util/              # RunCatchingCancellable.kt (domain/util/runCatchingCancellable — relance CancellationException)
│   └── usecase/
│       ├── auth/          # LoginUseCase, LogoutUseCase, GetUserProfileUseCase
│       ├── portfolio/     # GetPortfolioUseCase, GetPositionsUseCase, GetPositionWsUpdatesUseCase, GetPerformanceUseCase
│       ├── market/        # GetQuoteUseCase, GetQuoteStreamUseCase, GetAvailableSymbolsUseCase, GetSymbolHistoryUseCase, GetWatchlistUseCase, AddToWatchlistUseCase, RemoveFromWatchlistUseCase, GetPublicWsConnectionStateUseCase
│       ├── activity/      # GetActivityFeedUseCase
│       ├── device/        # GetDevicesUseCase, GetDeviceStatusUseCase, SendDeviceCommandUseCase, GetBrokerConnectionsUseCase
│       ├── alerts/        # GetAlertsUseCase, GetFilteredAlertsUseCase, MarkAlertReadUseCase
│       ├── notification/  # RegisterFcmTokenUseCase
│       ├── setup/         # MarkSetupCompletedUseCase (SetupViewModel ne touche plus le DataStore directement)
│       └── pairing/       # ParseVpsQrUseCase, ScanDeviceQrUseCase, SendPinToDeviceUseCase, ConfirmPairingUseCase, ParseSetupQrUseCase, ProvisionMobileVpnUseCase
├── ui/
│   ├── theme/             # Color.kt, Theme.kt, Type.kt (Material 3)
│   ├── navigation/        # AppNavGraph.kt — navigation globale
│   ├── common/            # DataState.kt (valeur/isRefreshing/error/syncedAt pour le Dashboard), QuoteFallbackController.kt
│   │                      # (WS + fallback REST piloté par connectionState — partagé Dashboard/MarketData)
│   ├── components/        # Composables partagés (LoadingOverlay, ErrorBanner, MetricsComponents, CacheTimestamp, BiometricLockOverlay, etc.)
│   └── screens/
│       ├── auth/          # LoginScreen + LoginViewModel
│       ├── dashboard/     # DashboardScreen + DashboardViewModel + ActivityFeedCard
│       ├── market/        # MarketDataScreen + MarketDataViewModel + SymbolPickerSheet
│       ├── portfolio/     # PositionsScreen, PositionDetailScreen, TransactionHistoryScreen + ViewModels
│       ├── performance/   # PerformanceScreen + PerformanceViewModel
│       ├── devices/       # DeviceListScreen, EdgeDeviceDashboardScreen (+ broker gateway, scraping) + ViewModels
│       ├── pairing/       # ScanVpsQrScreen, ScanDeviceQrScreen, PairingProgressScreen, PairingDoneScreen + PairingViewModel
│       ├── alerts/        # AlertListScreen + AlertsViewModel + AlertFilterBar
│       ├── totp/          # TotpScreen + TotpViewModel (2FA post-login)
│       ├── setup/         # SetupScreen + SetupViewModel (onboarding QR mobile)
│       └── settings/      # VpnSettingsScreen, SecuritySettingsScreen, ProfileScreen, MyDevicesScreen + ViewModels
├── vpn/
│   ├── WireGuardVpnService.kt   # Service foreground — notification « VPN connecté » (tunnel tenu par GoBackend$VpnService)
│   ├── TunnelBackend.kt         # Seam GoBackend (setState/getState) — fake en test JVM
│   ├── VpnServiceController.kt  # Seam start/stop de WireGuardVpnService (notification foreground) — fake en test JVM
│   ├── WireGuardManager.kt      # API publique : connect(), disconnect(), state: StateFlow<VpnState> — sérialisé par Mutex
│   ├── WireGuardConfig.kt       # Modèle de config (interface, peer)
│   ├── SystemVpnMonitor.kt      # Détecte un VPN tiers actif (ConnectivityManager) → VpnState.SystemVpnActive (D6)
│   └── VpnState.kt              # sealed class : Disconnected | Connecting | Connected | SystemVpnActive | Error
├── security/
│   ├── BiometricManager.kt      # Abstraction BiometricPrompt — consulte KeystoreManager.checkAuthValidity() avant le prompt
│   ├── RootDetector.kt          # Détection root (RootBeer)
│   ├── CertificatePinner.kt     # SHA-256 pins du certificat VPS
│   ├── KeystoreManager.kt       # Android Keystore : génération/récupération clés ; KeyState (Valid|Expired|Invalidated)
│   ├── LanTrustManager.kt       # TrustManager permissif scopé LAN (cert auto-signé Radxa, port 8099 HTTPS)
│   └── SealedBoxHelper.kt       # crypto_box_seal via lazysodium-android (chiffrement payloads LAN)
└── widget/
    ├── PnlWidget.kt / PnlWidgetConfigureActivity.kt   # Glance widget P&L (période configurable jour/semaine/mois)
    ├── PositionsWidget.kt       # Glance widget positions
    ├── AlertsWidget.kt          # Glance widget alertes
    ├── SystemStatusWidget.kt    # Glance widget état VPS/devices (admin)
    ├── QuoteWidget.kt / QuoteWidgetConfigureActivity.kt # Glance widget cours rapide 1x1 (ticker configurable)
    └── WidgetUpdateWorker.kt    # WorkManager periodic task (15 min, plancher OS — toutes sources)
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
| Dashboard (P&L, positions, activity feed) | ✅ | ✅ |
| Marchés (watchlist, cours temps réel WS public) | ✅ | ✅ |
| Positions (cours live via WS privé) | ✅ | ✅ |
| Performance (Sharpe, Sortino, drawdown, etc.) | ✅ | ✅ |
| Alertes (FCM → Room, filtrage par type) | ✅ | ✅ |
| Device list + detail (métriques santé CPU/RAM/temp), écran Devices (flotte) | ❌ masqué | ✅ |
| Pairing depuis « Mes appareils » (Settings → MyDevicesScreen) | ✅ | ✅ |
| Pairing depuis l'écran Devices (flotte admin) | ❌ masqué | ✅ |
| `SystemStatusWidget` | ❌ masqué | ✅ |
| `DevicesWidget` (si créé) | ❌ masqué | ✅ |

**Décision D5 (audit/PLAN.md) :** le pairing d'un device est ouvert à **tout utilisateur
authentifié** via `Settings → Mes appareils` (`MyDevicesScreen`, non gardé par `isAdmin`) — le
backend l'applique par propriétaire du device, pas par rôle. Seuls la flotte admin
(`Screen.Devices`, liste tous les devices) et le pairing lancé depuis cet écran restent
réservés aux admins (`AppNavGraph` redirige vers Dashboard si `!isAdmin`). Les deux points
d'entrée partagent le même graphe de navigation `PairingViewModel`/4 écrans ; seul l'écran de
retour (`pairingReturnRoute`) diffère selon la source.

- L'onglet Devices (flotte) dans la navigation est affiché **uniquement si `user.is_admin == true`**
- Les widgets admin (`SystemStatusWidget`) sont **désactivés** dans le launcher si `is_admin == false` — ils n'apparaissent pas dans le picker de widgets
- Stocker `is_admin` dans `EncryptedDataStore` après login — relire à chaque démarrage
- Désactiver/activer via `ApplyAdminWidgetVisibilityUseCase` (jamais un appel `PackageManager` direct
  depuis un ViewModel — violerait la règle UseCase → Repository) :

```kotlin
// domain/repository/AdminWidgetVisibilityManager — interface domaine, implémentée dans
// widget/AdminWidgetVisibilityManagerImpl.kt (PackageManager.setComponentEnabledSetting)
interface AdminWidgetVisibilityManager {
    suspend fun applyVisibility(isAdmin: Boolean)
}

// domain/usecase/auth/ApplyAdminWidgetVisibilityUseCase — appelé par LoginViewModel/TotpViewModel
class ApplyAdminWidgetVisibilityUseCase @Inject constructor(
    private val visibilityManager: AdminWidgetVisibilityManager,
) {
    suspend operator fun invoke(isAdmin: Boolean) = visibilityManager.applyVisibility(isAdmin)
}
```

### Hilt — deux patterns selon le composant

| Composant | Pattern Hilt | Raison |
|-----------|-------------|--------|
| `GlanceAppWidget` | `EntryPointAccessors` | Glance ne supporte pas l'injection Hilt standard |
| `WidgetUpdateWorker` | `@HiltWorker` + `@AssistedInject` | WorkManager supporte Hilt nativement |

```kotlin
// WidgetUpdateWorker — injection standard via @HiltWorker (constructeur réel, di/WidgetModule.kt
// fournit les mêmes UseCases/DAOs aux widgets Glance via EntryPointAccessors ci-dessous)
@HiltWorker
class WidgetUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val vpnManager: WireGuardManager,
    private val systemVpnMonitor: SystemVpnMonitor,
    private val dataStore: EncryptedDataStore,
    private val getPositionsUseCase: GetPositionsUseCase,
    private val getPnlUseCase: GetPnlUseCase,
    private val getQuoteUseCase: GetQuoteUseCase,
    private val getDefaultQuoteSymbolUseCase: GetDefaultQuoteSymbolUseCase,
    private val alertDao: AlertDao,
    private val quoteDao: QuoteDao,
    private val watchlistDao: WatchlistDao,
) : CoroutineWorker(context, workerParams) { ... }
```

### Glance widgets + Hilt — pattern obligatoire (GlanceAppWidget uniquement)

Les widgets Glance ne supportent pas l'injection Hilt standard. Utiliser `EntryPointAccessors` :

```kotlin
// di/WidgetModule.kt — nom réel des méthodes (pas de getPortfolioUseCase générique)
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    // UseCases — utilisés par WidgetUpdateWorker pour les appels réseau dans doWork()
    fun getPositionsUseCase(): GetPositionsUseCase
    fun getPnlUseCase(): GetPnlUseCase
    fun getPortfolioNavUseCase(): GetPortfolioNavUseCase
    fun getAlertsUseCase(): GetAlertsUseCase
    fun getDevicesUseCase(): GetDevicesUseCase  // vérifier is_admin avant d'appeler
    fun getQuoteUseCase(): GetQuoteUseCase

    // DAOs — pour les GlanceAppWidgets (lecture cache Room uniquement, pas d'appel réseau)
    fun positionDao(): PositionDao
    fun pnlDao(): PnlDao
    fun alertDao(): AlertDao
    fun deviceDao(): DeviceDao
    fun quoteDao(): QuoteDao

    // DataStore — pour lire portfolioId, is_admin depuis les widgets
    fun encryptedDataStore(): EncryptedDataStore
}

// Dans chaque GlanceAppWidget : les widgets lisent Room directement via les DAOs
// (pas d'appel réseau depuis provideGlance()) — le Worker met à jour Room en amont.
override suspend fun provideGlance(context: Context, id: GlanceId) {
    val ep = EntryPointAccessors
        .fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
    val portfolioId = ep.encryptedDataStore().readString(DataStoreKeys.PORTFOLIO_ID) ?: ""
    val pnl = ep.pnlDao().getByPeriod("day")
    // ...
}
```

### Stratégie de cache Room

Toutes les données affichées offline ou dans les widgets passent par Room. Le `WidgetUpdateWorker`
(WorkManager, périodicité **15 min** — plancher imposé par l'OS pour un `PeriodicWorkRequest`,
il n'y a pas de cadence « 5 min » distincte) met à jour le cache. Si le VPN est inactif, les
écrans affichent le cache daté sans déclencher d'erreur bloquante.

**Source unique des TTL : `data/local/db/CacheTtl.kt`** — ne jamais redéclarer une constante
de TTL privée dans un repository, un widget ou un composant UI.

| Table Room | TTL (fraîcheur) | Utilisée par |
|------------|-----------------|-------------|
| `positions` | `CacheTtl.POSITIONS_MS` (5 min) | PositionsWidget, PositionDetail (`getPosition`) |
| `pnl_snapshots` | `CacheTtl.PNL_MS` (5 min) | PnlWidget — une ligne par période, forme `/pnl` (écrite par `getPnlSummary`) |
| `alerts` | permanent (`CacheTtl.ALERTS_RETENTION_MS`) | AlertListScreen (filtrable par type), AlertsWidget |
| `devices` | `CacheTtl.DEVICES_MS` (1 min) | DeviceListScreen offline, détail device (`getDeviceStatus`), SystemStatusWidget |
| `quotes` | `CacheTtl.QUOTES_MS` (10 min) | QuoteWidget, MarketDataScreen (offline-first cohérent) |
| `watchlist` | permanent | MarketDataScreen, `WidgetUpdateWorker.syncQuotes` |

Le timestamp de dernière sync est stocké avec chaque entité (`synced_at: Long`).

**Lectures unitaires (`getDeviceStatus`, `getPosition`)** : renvoient `Result<Cached<T>>`
(`domain/model/Cached` = valeur + vrai `syncedAt`). Le cache n'est servi que s'il est frais
(`CacheTtl.isFresh`) et que `forceRefresh == false` ; le pull-to-refresh / retry des écrans
passe `forceRefresh = true`. Si le réseau échoue et qu'une ligne périmée existe, elle est
renvoyée avec son `syncedAt` réel (affichée « offline », pas d'erreur bloquante). Les
ViewModels exposent ce `syncedAt`, jamais `System.currentTimeMillis()`.

**UI — `CacheTimestamp(syncedAt, ttlMs = CacheTtl.DEFAULT_UI_MS /* 10 min */, warnMs = ttlMs / 2)`** :
les appelants passent le TTL de l'entité affichée. « À jour » (neutre) < 1 min ; « Données du
HH:mm » (neutre) < `warnMs` ; couleur warning < `ttlMs` ; couleur offline ≥ `ttlMs`. Date
`dd/MM HH:mm` si la donnée n'est pas du jour. Logique pure : `cacheTimestampLabel()` (testée).

**Widgets** : le timestamp `synced_at` est affiché dans tous les widgets sans exception —
pas uniquement dans l'UI principale. Pour un app de trading, afficher un cours de 10 min
sans indication est trompeur. `formatWidgetSyncTime(syncedAt, ttlMs)` → `SyncLabel(text, isStale)` :
« maintenant » / « il y a Nmin » / « HH:mm » (même jour) / « dd/MM HH:mm » ; au-delà du seuil
widget `widgetStaleThreshold(ttl) = maxOf(TTL entité, CacheTtl.WIDGET_STALE_GRACE_MS /* 20 min =
période Worker 15 min + 5 min de grâce */)`, préfixe **« périmé · »** et libellé coloré ambre
(`syncLabelColor`, badge périmé). Le plancher évite un badge quasi permanent entre deux cycles du
Worker ; l'UI in-app (`CacheTimestamp`) garde les TTL d'entité.

### Politique de rétention Room

| Table | Politique |
|-------|-----------|
| `alerts` | 30 jours (`CacheTtl.ALERTS_RETENTION_MS`) **ou** 500 entrées max (whichever first) — purge par le Worker |
| `quotes` | Supprimer les entrées dont `synced_at < now - CacheTtl.QUOTES_MS` |
| `positions` | Supprimer les entrées dont `synced_at < now - CacheTtl.POSITIONS_MS` (remplacées à chaque sync) |
| `pnl_snapshots` | Supprimer les entrées dont `synced_at < now - CacheTtl.PNL_RETENTION_MS` (**24 h**, pas 5 min : une ligne par période — une purge à 5 min après la sync d'une période supprimerait les lignes des autres périodes configurées par d'autres widgets) |
| `devices` | Supprimer les entrées dont `synced_at < now - CacheTtl.DEVICES_MS` |
| `watchlist` | Pas de purge — persistance permanente (symboles gérés par l'utilisateur) |

**`WidgetUpdateWorker.syncQuotes`** rafraîchit tickers `QuoteWidget` configurés ∪ watchlist ∪
symboles déjà en cache `quotes` (majuscules, dédupliqués, plafond 50, tickers widgets en
premier), fan-out borné par `withTimeout(60 s)` — un dépassement compte comme un échec IO.
Les prefs de configuration des widgets (`ticker_*`, `period_*`) sont nettoyées dans
`onDeleted` des receivers.

La purge est exécutée **après** chaque sync réussie — jamais avant. Purger avant les appels
réseau crée un gap : si le Worker est tué pendant la sync, les tables sont vides. Utiliser
des upserts (`OnConflictStrategy.REPLACE`) plutôt que DELETE + INSERT séquentiel.

### Migration Room

Activer `schemaDirectory` (déjà configuré) et appliquer la stratégie suivante :
- **Baseline v7** : l'app n'a jamais été livrée avant la version de schéma 7 (versionCode 1,
  aucun utilisateur en v1-6) — décision D2 du plan de remédiation (finding #5). Les anciens
  schémas `1.json`…`6.json` et les `MIGRATION_*` correspondants ont été supprimés ; `7.json`
  est le seul schéma exporté et fait référence. **Aucune migration n'existe (ni n'a besoin
  d'exister) avant v7.**
- **Développement** : `fallbackToDestructiveMigration()` acceptable — schéma instable.
  Configuré uniquement pour les builds debug dans `DatabaseModule.kt`.
- **Release** : pas de fallback par design — la baseline v7 ne cible que les installations
  fraîches. Le premier changement de schéma post-release (v8+) redevient soumis à la règle
  générale : migration explicite obligatoire via `addMigrations(MIGRATION_X_Y)` dans
  `DatabaseModule.kt` — ne jamais utiliser `fallbackToDestructiveMigration()` en release à
  partir de là (perte de données `alerts`, pas de backup serveur). Voir la checklist « AJOUTER
  UNE MIGRATION » dans `AppDatabase.kt`.
- **Tests** : `MigrationTest` (androidTest, `MigrationTestHelper`) valide que le schéma exporté
  `7.json` correspond aux entités déclarées dans `AppDatabase` — c'est le seul test de migration
  tant qu'il n'y a pas de v8 (exécuté par le job CI instrumenté). Une variante JVM/Robolectric a
  été tentée puis retirée : `MigrationTestHelper` ne voit pas les assets de schéma sous Robolectric.

### WorkManager — contraintes et comportement VPN

Le `WidgetUpdateWorker` doit déclarer `Constraints.NETWORK_CONNECTED` pour éviter les tentatives
inutiles hors-ligne :

```kotlin
val constraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .build()
```

Si le VPN est inactif au moment du Worker (VpnRequiredInterceptor bloque) : retourner
`Result.success()` **sans mettre à jour Room** — le cache daté reste affiché.
Ne jamais retourner `Result.failure()` pour absence de VPN (déclencherait les retries WorkManager
en boucle). `Result.retry()` (`BackoffPolicy.EXPONENTIAL`) n'est renvoyé que si **toutes** les
sections IO (positions, PnL, quotes) ont échoué sur une `IOException` — règle délibérée : si au
moins une section a réussi, un échec isolé et transitoire des autres ne vaut pas la peine de
retenter tout le Worker avant le prochain cycle périodique (15 min).

```kotlin
// Pattern dans WidgetUpdateWorker.doWork()
// Chaque bloc sync est indépendant — un échec portfolio ne bloque pas les alertes
override suspend fun doWork(): Result {
    if (vpnManager.state.value !is VpnState.Connected) {
        return Result.success()  // VPN absent — garder le cache, ne pas retry
    }

    var ioFailCount = 0
    val ioSectionsTotal = 3

    // Sync indépendante par section — try/catch par bloc ; SQLException = non-retryable (log only)
    try {
        syncPositions(portfolioId)
    } catch (e: IOException) { ioFailCount++ } catch (e: SQLException) { /* log, non-retryable */ }

    try {
        syncPnl(portfolioId)
    } catch (e: IOException) { ioFailCount++ } catch (e: SQLException) { /* log, non-retryable */ }

    try {
        syncQuotes()
    } catch (e: IOException) { ioFailCount++ } catch (e: SQLException) { /* log, non-retryable */ }

    purgeExpired()  // alertes + entités périmées, après les tentatives de sync

    return if (ioFailCount == ioSectionsTotal) Result.retry() else Result.success()
}
```

La vérification VPN en entrée de Worker (via `vpnManager.state`) évite de passer par les
intercepteurs OkHttp pour un cas prévisible.

### Données de marché — stratégie

- **Cours (Dashboard + MarketDataScreen)** : WebSocket public `wss://vps/ws/public` (symbole par défaut pour le Dashboard, chaque symbole de la watchlist pour MarketData), throttle `Flow.sample(250ms)`, via le helper partagé `ui/common/QuoteFallbackController` — ne jamais recopier une boucle « collect WS → catch → polling » dans un ViewModel
- **Subscriptions WS ref-comptées** : `PublicWsClient` compte les collecteurs par symbole — frame `subscribe` seulement à 0→1, `unsubscribe` seulement à 1→0, resouscription de toutes les clés sur `onOpen`, fermeture quand plus aucun symbole. Dashboard et MarketData peuvent donc suivre le même symbole sans se couper mutuellement
- **Fallback REST piloté par l'état** : `PublicWsClient.connectionState` (`WsConnectionState` : `Connecting` à l'ouverture / pendant le backoff, `Connected` sur onOpen, `Disconnected` sur onFailure/onClosed/disconnect, `Degraded` à partir de 3 tentatives de reconnexion) est exposé via `PublicWsRepository` + `GetPublicWsConnectionStateUseCase` (qui expose aussi `isAppForeground()`). Le flux WS `quoteUpdates` **ne lève jamais** sur coupure — un `catch` autour de sa collecte est du code mort. `QuoteFallbackController` collecte le flux WS en permanence (jamais annulé → resouscription automatique après reconnexion) et, quand l'état n'est pas `Connected` depuis **2 s** (debounce ; `Connected` est propagé immédiatement), appelle `onStale` puis polle `GET /v1/market-data/quote/{symbol}` toutes les **30 secondes** — uniquement si l'app est au premier plan. Le polling s'arrête dès le retour à `Connected` (`collectLatest`). Dans `openWebSocket`, un `catch (e: Exception)` doit relancer `CancellationException`
- **Portfolio (P&L, positions)** : mises à jour temps réel via `WsRepository` (WebSocket `wss://vps/v1/ws/private`) en complément du polling REST
- **Dashboard NAV / PnL — `DataState<T>`** (`ui/common/DataState.kt` : `value`, `isRefreshing`, `error`, `syncedAt`) : la valeur n'est **jamais** remise à `null` après un premier succès — `loading()` et `failure()` la conservent (valeur périmée), seul `success(v, now)` la remplace. Chaque `portfolio_update` du WS privé applique `total_value` / `cash_balance` / `positions_value` **directement** à la NAV affichée (patch optimiste, `syncedAt = now` ; le backend n'envoie pas de P&L sur ce canal), passe NAV + PnL en `isRefreshing`, puis déclenche un refetch REST NAV + PnL **debouncé 750 ms** (une rafale = une paire de requêtes). Côté écran : skeletons uniquement si NAV **et** PnL sont `isInitialLoading` ; `isRefreshing` n'alimente que le `PullToRefreshBox` ; valeur + erreur → valeur périmée + `CacheTimestamp(syncedAt, CacheTtl.PNL_MS)` + snackbar ; pas de valeur + erreur → carte d'erreur inline. Un changement de période PnL réinitialise la seule section PnL (jamais la PnL d'une autre période sous la nouvelle puce)
- **Positions live** : les `position_update` du WS privé sont mergés dans `PositionsViewModel` pour mettre à jour `currentPrice` et `unrealizedPnl` en temps réel (affichage via `AnimatedPnlText`)
- **Widgets** : rafraîchissement inclus dans le cycle WorkManager **15 min** (plancher OS)
- **Symboles disponibles** : `GET /v1/market-data/symbols` retourne la liste des symboles trackés par le backend
- **Historique OHLCV (sparklines)** : `GET /v1/market-data/{symbol}/history` — 30 derniers points close, affiché en mini-chart dans chaque card de la watchlist

La table Room `quotes` persiste le dernier cours connu (TTL 10 min) pour le `QuoteWidget` et le `MarketDataScreen`.
La table Room `watchlist` persiste les symboles suivis par l'utilisateur (pas de TTL).
L'écran Dashboard ne persiste pas les cours — il affiche uniquement les données live ou rien.

**Gestion des exceptions du fallback REST Dashboard** — trois cas distincts, traités dans le
callback `onFetchError` du `QuoteFallbackController` (le controller relance lui-même une
`CancellationException` encapsulée dans un `Result`).

`repeatOnLifecycle` est une extension de `Lifecycle` (Activity/Fragment) — **non utilisable
dans un ViewModel**. Le polling (`while(isActive)` + `delay(30_000)`) vit dans le controller,
lancé dans `viewModelScope` ; la pause en arrière-plan passe par
`GetPublicWsConnectionStateUseCase.isAppForeground()` (ProcessLifecycleOwner côté data). Côté UI,
`collectAsStateWithLifecycle()` suspend en plus la collection quand l'app est en arrière-plan.

```kotlin
// Dans DashboardViewModel — pattern correct
private val quoteFallback = QuoteFallbackController(
    scope = viewModelScope,
    connectionState = getPublicWsConnectionStateUseCase(),
    isForeground = getPublicWsConnectionStateUseCase.isAppForeground(),
    stream = { getQuoteStreamUseCase(it) },
    fetch = { getQuoteUseCase(it) },
    onQuote = { _, q -> _uiState.update { it.copy(quote = QuoteUiState.Success(q)) } },
    onStale = { markQuoteStale() },                          // Success → Stale(prev.data)
    onFetchError = { _, e ->
        when (e) {
            is VpnNotConnectedException -> markQuoteStale()  // VPN coupé — pas d'erreur bloquante
            is SocketTimeoutException, is IOException -> Unit // transitoire — état inchangé
            else -> _uiState.update { it.copy(quote = QuoteUiState.Error(e.localizedMessage ?: "Erreur")) }
        }
    },
)
// quoteWatchJob = quoteFallback.watch(symbol) — annuler le Job = unsubscribe ref-compté + arrêt du polling

// Dans le Composable — collectAsStateWithLifecycle() gère le lifecycle
val uiState by viewModel.uiState.collectAsStateWithLifecycle()
```

### WebSocket privé — PrivateWsClient

`PrivateWsClient` se connecte à `wss://vps/v1/ws/private` avec un JWT dont le claim `"websocket"` est obtenu via `POST /v1/auth/ws-token`. Il implémente `DefaultLifecycleObserver`, mais `onStop` (arrière-plan) n'annule que les timers de reconnexion/refresh de token pour économiser la batterie — **le socket lui-même n'est pas fermé** ; `onStart` (retour au premier plan) reconnecte seulement si besoin (token expiré pendant le background, socket absent). La connexion est réellement ouverte/fermée par les événements de session (voir ci-dessous), pas par le cycle de vie foreground/background.

`WsRepository` expose des `Flow` pour les événements WS : `portfolioUpdates`, `positionUpdates`, `orderUpdates`, `strategySignals`, `notifications`, `catalystEvents`. `DashboardViewModel` collecte `portfolioUpdates` en complément du polling REST et `GetActivityFeedUseCase` merge les 5 flux (`orderUpdates`, `strategySignals`, `notifications`, `portfolioUpdates`, `catalystEvents`) dans un feed d'activité temps réel affiché sur le Dashboard via `ActivityFeedCard`. `PositionsViewModel` collecte `positionUpdates` pour mettre à jour les prix en temps réel.

Le token WS est distinct de l'access token — obtenir via `POST /v1/auth/ws-token` avant chaque connexion. `WebSocketModule` dans `di/` fournit les bindings Hilt.

**Connexion / déconnexion pilotées par la session (`SessionManager`).** Aucun ViewModel n'appelle `connect()`/`disconnect()`. `AuthRepositoryImpl` émet `notifySessionStarted()` juste après `tokenHolder.setToken` (login et vérification 2FA) : `PrivateWsClient` abandonne toute connexion de la session précédente, remet le backoff à zéro et se connecte immédiatement. Tout `forcedLogoutEvents` (logout utilisateur via `SettingsViewModel`, logout forcé `TokenAuthenticator`/`AuthInterceptor`, escape hatch biométrique) → `disconnect()` (close 1000, sans reconnexion). Garde `TokenHolder` : sans access token en mémoire, `connect()`/`scheduleReconnect()`/`openWebSocket()` ne font rien — la boucle de backoff s'arrête au logout. Un compteur de génération, incrémenté par `disconnect()`, rend périmée toute tentative en vol (`getWsToken()` ou handshake) : elle ne peut ni rouvrir un socket authentifié ni modifier l'état. Au démarrage à froid, `TradingApplication` appelle `connect()` après le préchargement du token (sans écraser un token déjà présent dans `TokenHolder`). Tests : `PrivateWsClientTest` (Robolectric + MockWebServer).

### Alertes — source de données (FCM → Room)

Les alertes proviennent exclusivement de notifications FCM persistées localement :
- À la réception d'un FCM, stocker dans la table Room `alerts`
- `AlertListScreen` et `AlertsWidget` lisent `alerts` en local (fonctionne offline)
- `AlertListScreen` supporte le filtrage par type via `AlertFilterBar` (chips Material 3 multi-sélection)
- Le filtrage est effectué au niveau SQL (query Room `WHERE type IN (...)`) pour la performance
- Pas d'endpoint VPS de listing — historique local uniquement

```
domain/usecase/alerts/
├── GetAlertsUseCase          — Flow<List<Alert>> depuis Room (non filtré)
├── GetFilteredAlertsUseCase  — Flow<List<Alert>> filtré par Set<AlertType> (query Room)
└── MarkAlertReadUseCase      — marque une alerte comme lue (Room update)
```

### Pattern Result<T> — Repository et UseCase (obligatoire)

```kotlin
// Toutes les méthodes des interfaces Repository retournent Result<T> (stdlib Kotlin)
// Jamais de throw directement depuis un Repository — toujours wrapper dans
// runCatchingCancellable {} (domain/util/RunCatchingCancellable.kt), jamais runCatching {} nu.
// runCatching {} catche Throwable, donc avale silencieusement CancellationException et casse la
// concurrence structurée (une coroutine annulée ressort comme un Result.failure « normal »).
// runCatchingCancellable {} relance CancellationException (et sa sous-classe
// TimeoutCancellationException) au lieu de l'encapsuler — un appelant qui veut convertir un
// timeout en échec métier doit le catcher explicitement avant, comme ConfirmPairingUseCase.
// Un test de garde (NoBareRunCatchingTest) échoue le build sur tout nouveau runCatching {} nu
// hors d'une liste blanche de code synchrone (parsing JSON/URI, widgets Glance).
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
- `VpnState.SystemVpnActive` = VPN tiers actif, tunnel intégré coupé (D6 : autorisé par `VpnRequiredInterceptor`, affiché distinctement, jamais émis par `WireGuardManager`) ; `WireGuardManager` sérialise `connect()`/`disconnect()` par un `Mutex` (dernière intention gagne) et, sur un DOWN non initié (révocation OS), arrête `WireGuardVpnService` (qui ne porte que la notification — le tunnel est tenu par `GoBackend$VpnService`).
- La clé privée WireGuard est générée une seule fois, stockée dans `EncryptedDataStore`,
  protégée par Android Keystore. Elle ne sort jamais de l'app.
- La clé publique est partagée avec le VPS lors du pairing uniquement.
- La méthode `configureFromSetupQr(data: SetupQrData)` permet de configurer et connecter le
  tunnel à partir des données du QR d'onboarding. Elle stocke la clé privée et la config dans
  `EncryptedDataStore` avant de connecter.

### Chaîne d'intercepteurs OkHttp (ordre obligatoire)

```
CsrfInterceptor           → récupère et injecte le token CSRF (header X-CSRF-Token)
VpnRequiredInterceptor    → bloque si VpnState ≠ Connected
AuthInterceptor           → injecte Authorization: Bearer <access_token>
TokenAuthenticator        → sur 401 AUTH_1002 : refresh puis retry
HttpLoggingInterceptor    → debug uniquement (NetworkModule.debugLogger) : Authorization,
                            X-CSRF-Token, Cookie, Set-Cookie expurgés ; HEADERS seulement
                            sur AuthPaths.isSensitive() (auth, csrf, fcm-token), BODY ailleurs
```

Le middleware CSRF du VPS **ne fait pas d'exemption** sur les requêtes Bearer — le `CsrfInterceptor` est obligatoire pour tous les `POST/PUT/DELETE/PATCH`.

**Source unique des chemins d'auth : `data/api/AuthPaths.kt`** (`PUBLIC` sans Bearer, `CSRF_EXEMPT`,
`VPN_EXCLUDED`, `COOKIE_SAVE`, `isSensitive()`), miroir de `auth.py PUBLIC_PATHS` / `csrf.py CSRF_EXEMPT_PATHS`
du backend. Ne jamais redéclarer un set de paths privé dans un intercepteur ou le cookie jar — modifier `AuthPaths` (+ `AuthPathsTest`).

**Deux contraintes d'implémentation critiques pour `CsrfInterceptor` :**

1. **Pas d'`AuthApi` en paramètre** — injecter un `OkHttpClient` "bare" (sans interceptors) pour
   le `GET /csrf-token`, évite la dépendance circulaire `OkHttpClient → CsrfInterceptor → AuthApi → OkHttpClient`.
2. **Mutex obligatoire** — utiliser un `kotlinx.coroutines.sync.Mutex` pour que le fetch du token
   CSRF ne soit lancé qu'une seule fois en cas de requêtes parallèles (même pattern que `TokenAuthenticator`).

---

## 4. SÉCURITÉ — RÈGLES SPÉCIFIQUES

### Certificate Pinning (Root CA)

**Strategie : Root CA pinning.** On pinne le SPKI hash de la Root CA Caddy (pas le certificat
serveur leaf). OkHttp verifie le hash contre toute la chaine TLS, donc la Root CA match
automatiquement. Les renouvellements automatiques du cert leaf par Caddy (~tous les 2 mois)
sont transparents. La Root CA est valide ~10 ans.

```kotlin
// Dans CertificatePinner.kt — ne jamais bypasser
// Le hash est celui de la Root CA Caddy (SPKI SHA-256), PAS du cert serveur
OkHttpClient.Builder()
    .certificatePinner(
        CertificatePinner.Builder()
            .add("10.42.0.1", "sha256/<ROOT_CA_SPKI_HASH>")
            .add("10.42.0.1", "sha256/<ROOT_CA_SPKI_HASH_BACKUP>")
            .build()
    )
```

**Generer le hash :** `cd trading-platform2 && ./scripts/extract_caddy_ca.sh`

```properties
# local.properties — Root CA SPKI hashes
CERT_PIN_SHA256=sha256/<hash_root_ca_caddy>
CERT_PIN_SHA256_BACKUP=sha256/<meme_hash_ou_hash_backup_ca>
```

**Backup pin :** identique au principal tant qu'il n'y a pas de migration de CA prevue.
Si le VPS est reconstruit, restaurer le backup PKI (`./scripts/extract_caddy_ca.sh` fait le
backup automatiquement). Tous les clients existants continuent de fonctionner.

### Stockage sécurisé

```kotlin
// CORRECT — EncryptedDataStore
val key = MasterKey.Builder(context)
    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
    .build()

// INTERDIT
getSharedPreferences("prefs", MODE_PRIVATE).edit().putString("token", jwt)
```

**Corruption EncryptedDataStore — handling obligatoire.** Sur certains devices (Samsung, Xiaomi),
le Keystore peut être invalidé au reboot, rendant les données chiffrées illisibles. Toute
lecture depuis `EncryptedDataStore` doit être wrappée :

```kotlin
suspend fun readSecurely(key: String): String? {
    return try {
        dataStore.data.first()[stringPreferencesKey(key)]
    } catch (e: IOException) {
        // Fichier corrompu ou illisible
        null
    } catch (e: GeneralSecurityException) {
        // Keystore invalidé (reboot, suppression biométrie, reset device)
        null
    }
}
```

Attraper aussi `SecurityException` (security-crypto alpha enveloppe les échecs de déchiffrement
Tink en `RuntimeException`). Les chemins critiques utilisent `readStringSafe` / `readBooleanSafe`
(`SecureReadResult.Found | NotFound | Corrupted`) pour distinguer « absent » de « corrompu ».

**Corruption détectée → reset complet.** `SessionManager.notifyKeystoreCorruption()` affiche le
dialog de corruption ; son bouton lance `RecoverFromKeystoreCorruptionUseCase` (WS privé + tunnel
WG coupés, caches token/cookie/CSRF vidés, Room vidé, puis `EncryptedDataStore.resetCorruptedStore()`
qui supprime `trading_secure_prefs` **et** l'alias MasterKey avant de recréer un store vide).
**Rien ne survit** (tokens, cookies, clés/config WireGuard, `setup_completed`, `local_token_*`) :
l'app repart sur `SetupScreen` et l'utilisateur rescanne le QR de configuration. Si le store ne
peut pas être recréé, le dialog réapparaît en variante « stockage indisponible » (réessayer).
Ne jamais laisser l'app dans un état indéterminé avec des clés nulles.

### Verrou biométrique — comportement (Option B1 : deux mécanismes distincts)

La validité de 300 s de la clé Keystore et "inactivité de 5 min" sont deux choses différentes.
L'implémentation combine les deux :

| Mécanisme | Rôle |
|-----------|------|
| Clé Keystore `setUserAuthenticationParameters(300, AUTH_BIOMETRIC_STRONG)` (API 30+ ; `setUserAuthenticationValidityDurationSeconds(300)` sur 28-29) | Invalide la clé crypto 5 min **après la dernière auth biométrique forte** — géré par Android |
| Timer d'inactivité dans `BiometricLockManager` (singleton) | Déclenche l'overlay et redemande la biométrie après 5 min **sans interaction écran** |

**`BiometricLockManager` est le seul propriétaire** du timestamp d'interaction, du poll (5 s) et
de `isLocked` ; il observe `ProcessLifecycleOwner` (`onStart` : verrouille si expiré puis relance
le poll ; `onStop` : coupe le poll et persiste). `MainActivity` ne fait que transmettre les touches
(la recréation d'Activity ne remet donc pas l'horloge à zéro) :
```kotlin
override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
    biometricLockManager.onUserInteraction()   // AtomicLong, ignoré pendant le verrou
    return super.dispatchTouchEvent(ev)
}
```
`unlock()` ré-arme l'horloge ; `BIOMETRIC_LOCKED` + `LAST_INTERACTION_AT` sont persistés en
`commit()` (clés critiques d'`EncryptedDataStore`). Sans session (`TokenHolder` vide :
Setup/Login), le timeout ne verrouille pas.

**Démarrage à froid — verrouillé par défaut (D7).** `isLocked` vaut `true` jusqu'à la décision de
`TradingApplication`, prise dans la coroutine qui lit `ACCESS_TOKEN` : session présente →
`restorePersistedState()` avant `tokenHolder.setToken` (verrouillé sauf état persisté
« déverrouillé » avec interaction < 5 min) ; pas de session → `unlock()` silencieux (Setup/Login
non protégés) ; Keystore corrompu → reste verrouillé (le reset de récupération déverrouille).
Tant que le dialog de corruption Keystore est affiché, l'overlay reste opaque mais ne lance pas
le prompt biométrique (`authEnabled = false`) ; après un reset réussi
(`RecoverFromKeystoreCorruptionUseCase` → `unlock()`), le flux normal reprend.

**Overlay fail-closed.** `BiometricLockOverlay` n'appelle `onAuthSuccess` **que** depuis le
callback de succès du `BiometricPrompt`. Pas de `FragmentActivity` dans la chaîne de `Context`
ou pas de `BiometricManager` → message d'erreur, l'overlay reste. Seule autre sortie : le bouton
« Se reconnecter » (après 60 s) → logout forcé. Escape hatch (bouton ou clé invalidée) :
`LogoutUseCase` + `TokenHolder.clear()` puis `notifyForcedLogout()`, et l'overlay n'est levé
(`unlock()`) qu'une fois Login/Setup devenu la destination RESUMED — jamais d'écran authentifié
déverrouillé. L'overlay bloque les touches et le retour
arrière ; en `LocalInspectionMode` il est rendu statiquement sans lancer de prompt.
`MainActivity` est donc une `FragmentActivity` (exigée par `BiometricPrompt`), et
**`minSdk = 28`** (décision D1) : sur API 26-27 `BiometricPrompt` passe par un dialog AppCompat
qui plante avec notre thème framework — on n'a pas basculé l'app sur un thème AppCompat.

**Révocation biométrie** : avant chaque prompt, `BiometricManager` appelle
`KeystoreManager.checkAuthValidity()` → `Valid` / `Expired` (`UserNotAuthenticatedException`,
cas nominal après 300 s : on affiche le prompt) / `Invalidated`
(`KeyPermanentlyInvalidatedException`, empreintes supprimées : `regenerateKey()` puis
`onKeyInvalidated` → logout forcé, sans prompt). Aucune de ces exceptions ne doit s'échapper.
Prompt : `BIOMETRIC_STRONG` seul (bouton négatif obligatoire), `setConfirmationRequired(false)`.

- En cas de verrou : overlay opaque sur l'écran, données non visibles
- L'authentification biométrique réussie déverrouille pour 5 min supplémentaires (reset les deux mécanismes)
- `WireGuardVpnService` reste actif pendant le verrou (service foreground indépendant)
- **Widgets** : n'ont pas de verrou biométrique — ils lisent des données Room en cache via WorkManager
  (le cache Room est mis à jour en arrière-plan par `WidgetUpdateWorker`, pas par l'écran principal)
- **Décision consciente** : les widgets affichent les montants absolus (P&L en €, positions en valeur).
  Ce choix est intentionnel — les widgets sont sur un écran d'accueil déjà protégé par le verrou OS.
  À réévaluer si l'app est déployée sur des devices partagés.

### Root detection — note sur les limites

`RootBeer` assure une détection côté client, bypassable avec Magisk/Zygisk.
- **Si l'app est distribuée via le Play Store** : ajouter Play Integrity API pour une attestation
  côté serveur (non bypassable côté client). Le VPS peut rejeter les sessions dont l'attestation
  échoue.
- **Si l'app est distribuée en sideload** (usage interne, VPN-only) : Play Integrity API n'est
  pas disponible. RootBeer reste le seul mécanisme — à combiner avec les contrôles VPN/CSRF
  qui réduisent la surface d'attaque même sur un device rooté.

### Accès device LAN

```kotlin
// Toujours valider avant connexion
// Rejeter tout ce qui n'est pas une IP littérale — InetAddress.getByName() sur une IP string
// ne fait pas de DNS lookup ; le Patterns.IP_ADDRESS guard bloque les hostnames (DNS rebinding).
fun isLocalNetwork(ip: String): Boolean {
    if (!Patterns.IP_ADDRESS.matcher(ip).matches()) return false
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
| Valeurs monétaires | `jetBrainsMonoFamily` + `fontFeatureSettings = "tnum"`, aligné à droite |
| P&L positif/négatif | `LocalExtendedColors.current.pnlPositive/pnlNegative` selon signe `BigDecimal` |
| P&L animé | Utiliser `AnimatedPnlText` pour les valeurs mises à jour en temps réel (flash 500ms vert/rouge) |
| Spacing | `Spacing.lg` (16dp) par défaut — via `Spacing.*` (jamais de `.dp` hardcodé) |
| Métriques santé device | Utiliser les composants partagés `MetricsComponents.kt` : `metricColor()`, `CompactHealthBar`, `HealthStatusBadge`, `MetricRow` |
| Seuils métriques | CPU/RAM warn 70% crit 90%, Temp warn 60°C crit 75°C, Disk warn 70% crit 85% — définis dans `MetricsComponents.kt` |
| Dark mode | Tester chaque composant en light ET dark avant merge — `@Preview(uiMode = UI_MODE_NIGHT_YES)` + émulateur via `adb shell "cmd uimode night yes"`. Checklist détaillée : `docs/design-system.md` §"Tester chaque composant en dark mode" |
| DynamicColor | Désactivé — thème fixe pour cohérence avec le web |

Référence complète : `docs/design-system.md`

---

## 6. TESTS

```bash
# Tests unitaires (JVM, rapides) — suite complète
./gradlew testDebugUnitTest

# Tests unitaires — une classe à la fois (recommandé pour debug)
# Exécution isolée : chaque classe dans son propre process JVM (forkEvery=1).
# Permet de voir immédiatement quelle classe échoue sans attendre la suite entière.
./gradlew testDebugUnitTest --tests "com.tradingplatform.app.ui.screens.dashboard.DashboardViewModelTest"

# Tests unitaires — un par un en boucle (diagnostic rapide)
for t in $(find app/src/test -name "*Test.kt" -exec grep -l "^class\|^@.*class" {} \; \
    | sed 's|app/src/test/java/||;s|/|.|g;s|\.kt$||'); do
  short="${t##*.}"
  if ./gradlew testDebugUnitTest --tests "$t" 2>&1 | grep -q "BUILD SUCCESSFUL"; then
    echo "PASS  $short"
  else
    echo "FAIL  $short"
  fi
done

# Tests instrumentation — Gradle Managed Devices (api30, api34 ; images ATD, pas de GMS)
# déclarés dans testOptions.managedDevices (app/build.gradle.kts) — voir aussi le job CI
# `instrumented` (.github/workflows/android.yml)
./gradlew api30DebugAndroidTest api34DebugAndroidTest

# Garde de drift de contrats (JVM, dans testDebugUnitTest) — DtoContractTest compare les DTOs/
# chemins Retrofit/clés WS à un OpenAPI backend réduit + ws_events.json (docs/api-contracts.md
# §« Garde de drift de contrats »)
./gradlew testDebugUnitTest --tests "com.tradingplatform.app.contracts.DtoContractTest"
```

Coverage : plugin jacoco non appliqué — à ajouter si besoin (voir docs/gradle-setup.md).

Structure :
- `test/` — UseCases, ViewModels (Mockk + Turbine pour StateFlow), intercepteurs OkHttp (MockWebServer), Repositories, `DtoContractTest` (garde de drift de contrats), `NoBareRunCatchingTest` (interdit `runCatching {` nu hors liste blanche — voir « Pattern Result<T> » ci-dessous)
- `androidTest/` — UI tests Compose (ComposeTestRule), Room DAOs (`MigrationTest`, baseline v7), `WidgetUpdateWorker` (TestListenableWorkerBuilder), overlay biométrique fail-closed réel (`BiometricLockOverlayTest`), DataStore avec un vrai Keystore, `SealedBoxHelper` avec libsodium réel — exécutés par le job CI `instrumented` (Gradle Managed Devices api30/api34)

Objectif couverture : ≥ 80% sur `domain/usecase/`, `ui/screens/` ViewModels **et** `data/repository/`.

### Pattern standard ViewModel test (à appliquer uniformément)

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()  // remplace Dispatchers.Main

    private val useCase = mockk<GetQuoteUseCase>()
    private lateinit var viewModel: DashboardViewModel

    @Before fun setUp() {
        viewModel = DashboardViewModel(useCase)
    }

    @Test fun `uiState emits Success on valid quote`() = runTest {
        coEvery { useCase(any()) } returns Result.success(fakeQuote)
        viewModel.uiState.test {
            assertIs<QuoteUiState.Success>(awaitItem())
        }
    }
}
```

`MainDispatcherRule` utilise `UnconfinedTestDispatcher` — permet aux coroutines de s'exécuter
immédiatement sans `advanceUntilIdle()` dans la majorité des cas.

### Screenshot tests (Paparazzi / Roborazzi)

Pour les composants où les couleurs et l'alignement sont critiques (P&L, valeurs monétaires,
badges de statut), les screenshot tests détectent les régressions visuelles automatiquement.

```kotlin
// Exemple Paparazzi — ajouter en androidTest ou test selon la lib choisie
@Test fun `PositionCard renders positive PnL correctly`() {
    paparazzi.snapshot {
        TradingPlatformTheme {
            PositionCard(position = fakePositionWithPositivePnl)
        }
    }
}
```

Tester en light **et** dark (deux snapshots par composant critique).

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
VPS_BASE_URL=https://10.42.0.1:443
WG_VPS_ENDPOINT=vps.example.com:51820
WG_VPS_PUBKEY=<clé_publique_wg_du_vps>
CERT_PIN_SHA256=sha256/<empreinte_courante>
CERT_PIN_SHA256_BACKUP=sha256/<empreinte_backup>
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
VPS QR → scan → {session_id, session_pin, device_wg_ip, local_token, nonce}
Radxa QR → scan → {device_id, wg_pubkey, local_ip:8099}
App → POST https://radxa_ip:8099/pin {session_id, session_pin, local_token, nonce}  (LAN direct, chiffré)
App → poll GET https://radxa_ip:8099/status jusqu'à "paired"
```

**Règles critiques :**
- Valider que `radxa_ip` est RFC-1918 avant d'envoyer le PIN (`isLocalNetwork()`)
- Le `session_pin`, `local_token` et `nonce` ne doivent jamais être loggés (`[REDACTED]` si debug nécessaire)
- La connexion vers `radxa_ip:8099` doit être faite uniquement si `VpnState.Connected`
  (le VPN garantit qu'on est sur le bon réseau avant de contacter le LAN) — appliqué au niveau
  du client `@Named("lan")` par le `VpnRequiredInterceptor`, pas seulement documenté
- Timeout 120s sur l'opération complète (durée de vie de la session VPS)

**Validation des QR scannés :**
- QR non reconnu (ni VPS ni Radxa) → `PairingStep.Error("QR non reconnu, réessayez", retryable=true)` + vibration
- `ParseVpsQrUseCase` et `ScanDeviceQrUseCase` retournent `Result.failure(UnrecognizedQrException)` sur format invalide
- Structure QR Radxa à valider strictement (pas seulement le scheme) :
  ```kotlin
  // Valider que le scheme est pairing://radxa, les params id/pub/ip/port présents,
  // ip correspond à Patterns.IP_ADDRESS, port == 8099
  fun parseRadxaQr(raw: String): Result<DevicePairingInfo> {
      val uri = Uri.parse(raw)
      if (uri.scheme != "pairing" || uri.host != "radxa") return Result.failure(UnrecognizedQrException)
      val ip = uri.getQueryParameter("ip") ?: return Result.failure(MalformedQrException("ip"))
      if (!Patterns.IP_ADDRESS.matcher(ip).matches()) return Result.failure(MalformedQrException("ip format"))
      // ... valider id, pub (44 chars base64), port
  }
  ```

### QR codes

**QR VPS** : affiché sur `(app)/admin/edge-devices/` via `GET /v1/pairing/{session_id}/qr`
```json
{ "session_id": "uuid", "session_pin": "472938", "device_wg_ip": "10.42.0.5", "local_token": "hex-256-bit", "nonce": "64-char-hex" }
```

**QR Radxa** : affiché sur l'écran e-ink du device
```
pairing://radxa?id={device_id}&pub={wg_pubkey}&ip={local_ip}&port=8099
```

L'app scanne les deux QR dans n'importe quel ordre, puis connecte les infos.

> **Notes d'implémentation :**
> - `device_wg_ip` (du QR VPS) est affiché à l'utilisateur pour confirmation uniquement — il n'est envoyé ni à la Radxa ni au VPS par l'app.
> - `wg_pubkey` dans le QR Radxa est la clé publique **complète** (44 chars base64). La mention "tronquée" ne s'applique qu'à l'affichage sur l'e-ink, pas aux données QR.
> - La connexion vers `radxa_ip:8099` est **HTTPS** (le pairing-server Radxa refuse de démarrer
>   sans TLS) avec un certificat auto-signé accepté par `security/LanTrustManager.kt` — l'app
>   ne fait pas de certificate pinning sur ce chemin (défense en profondeur : garde RFC-1918 +
>   VPN actif + `crypto_box_seal`, pas la chaîne TLS). Le payload (session_pin, local_token) est
>   en plus chiffré avec `crypto_box_seal(radxa_wg_pubkey)` avant envoi — illisible sans la clé
>   privée du Radxa, même si le TLS venait à être compromis.

### Repository LAN (obligatoire — violation d'archi sinon)

Les UseCases ne peuvent pas faire d'appels réseau directement. Les appels vers `radxa_ip:8099`
passent par `PairingRepository` avec un OkHttpClient dédié (distinct du client VPS) :

```
domain/repository/
└── PairingRepository    — interface : sendPin(...): Result<Unit>, pollStatus(...): Flow<PairingStatus>

data/repository/
└── PairingRepositoryImpl — client @Named("lan") (di/NetworkModule.kt) : HTTPS + LanTrustManager,
                            garde anti-fuite qui rejette toute URL non-HTTPS/non-RFC-1918
                            (lanOnlyHttpsGuard), VpnRequiredInterceptor inclus ; pas de
                            CsrfInterceptor ni d'AuthInterceptor (LAN, pas VPS)
                            Valide isLocalNetwork() avant chaque appel
```

Le `PairingRepository` utilise un `OkHttpClient` **séparé** sans les interceptors VPS
(pas de CsrfInterceptor, pas d'AuthInterceptor). Le VPN vérifie déjà l'accès réseau au niveau OS.

### UseCases à créer

```
domain/usecase/pairing/
├── ParseVpsQrUseCase            — parse QR VPS → PairingSession(session_id, session_pin, device_wg_ip, local_token, nonce)
├── ScanDeviceQrUseCase          — parse QR Radxa → DevicePairingInfo(device_id, wg_pubkey, local_ip)
├── SendPinToDeviceUseCase       — délègue à PairingRepository.sendPin() — payload chiffré (session_pin + nonce) via SealedBoxHelper
├── ConfirmPairingUseCase        — poll toutes les 2s via withTimeout(120_000) — retourne dès "paired" ou "failed"
└── ParseSetupQrUseCase          — parse QR onboarding mobile → SetupQrData(wg_private_key, endpoint, tunnel_ip, dns, server_pubkey)
```

**Intervalle de polling : 2 secondes.** Ne pas laisser au développeur le choix — une boucle
sans délai consomme batterie et surcharge la Radxa. `delay(2_000)` entre chaque GET status.

**Invalidation PIN côté VPS :** le VPS invalide le `session_pin` après un usage réussi (usage
unique, TTL 120s). L'app ne doit pas retenter `SendPinToDeviceUseCase` après un succès de
`ConfirmPairingUseCase`.

### PairingViewModel — state machine (sealed class obligatoire)

Les 4 screens partagent un seul `PairingViewModel`. Les deux QR sont scannables dans n'importe
quel ordre — le state machine doit le gérer explicitement :

```kotlin
sealed interface PairingStep {
    data object Idle : PairingStep
    // QR VPS scanné, en attente du QR Radxa
    data class VpsScanned(val session: PairingSession) : PairingStep
    // QR Radxa scanné, en attente du QR VPS
    data class DeviceScanned(val device: DevicePairingInfo) : PairingStep
    // Les deux QR sont scannés — prêt à envoyer le PIN
    data class BothScanned(val session: PairingSession, val device: DevicePairingInfo) : PairingStep
    data object SendingPin : PairingStep
    data object WaitingConfirmation : PairingStep
    data object Success : PairingStep
    data class Error(val message: String, val retryable: Boolean) : PairingStep
}
```

Si l'utilisateur quitte le flux (back button), le ViewModel est effacé et la session est
abandonnée côté VPS à l'expiration du TTL 120s. Pas de reprise de session en cours.

### Screens à créer

```
ui/screens/pairing/
├── ScanVpsQrScreen       — caméra → parse QR VPS
├── ScanDeviceQrScreen    — caméra → parse QR Radxa
├── PairingProgressScreen — progression temps réel (3 étapes animées)
└── PairingDoneScreen     — succès ✓ / échec avec retry
```

---

## 9. QUALITÉ TRANSVERSALE

### Accessibilité

Toutes les valeurs P&L, montants et badges doivent avoir un `contentDescription` lisible par
TalkBack — la valeur numérique seule est ambiguë pour un lecteur d'écran :

```kotlin
Text(
    text = formatAmount(position.unrealizedPnl),  // "+1 250,00 €"
    modifier = Modifier.semantics {
        contentDescription = "Gain non réalisé : ${formatAmountVerbose(position.unrealizedPnl)}"
    }
)
// Badges : "Statut : Ouvert" plutôt que l'icône seule
```

### Crash reporting

Firebase est déjà dans les dépendances (FCM). Ajouter Crashlytics :
```toml
# libs.versions.toml
firebase-crashlytics = { group = "com.google.firebase", name = "firebase-crashlytics-ktx" }  # version via BOM
```
```kotlin
// app/build.gradle.kts plugins
alias(libs.plugins.firebase.crashlytics)
```
Les crashes derrière VPN sont impossibles à diagnostiquer sans telemetry.

### Notifications FCM — deep linking

Cliquer sur une notification FCM doit ouvrir `AlertListScreen` directement. Implémenter dans
`FirebaseMessagingService.onMessageReceived()` :

```kotlin
val intent = Intent(context, MainActivity::class.java).apply {
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    putExtra("navigate_to", "alerts")  // ou deep link URI
}
val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
// Attacher au NotificationCompat.Builder
```

`MainActivity` lit l'extra au démarrage et navigue vers `AlertListScreen` via `NavController`.

### Compatibilité API — stratégie de version check

Si le VPS déploie un breaking change, les anciennes apps cassent silencieusement. Prévoir :
- Header `X-App-Version: {versionCode}` sur toutes les requêtes (via `AuthInterceptor`)
- Le VPS peut retourner `426 Upgrade Required` si la version est trop ancienne
- L'app affiche un dialog "Mise à jour requise" non dismissable sur `426`

## 10. RÉFÉRENCES

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

## 11. BUILD — MANIFEST ET DÉPENDANCES

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
<!-- Pas de BIND_VPN_SERVICE ni d'intent-filter VpnService : ce service ne porte que la
     notification ; GoBackend$VpnService (déclaré par l'AAR wireguard) tient le tunnel. -->
<service
    android:name=".vpn.WireGuardVpnService"
    android:foregroundServiceType="specialUse"
    android:exported="false">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="VPN tunnel WireGuard — toutes les requêtes API passent par ce tunnel" />
</service>
<!-- Widgets Glance : déclarer chaque AppWidgetProvider ici (1 <receiver> par widget) -->
<!-- QuoteWidget nécessite une activité de configuration (ticker configurable par widget) -->
<activity
    android:name=".widget.QuoteWidgetConfigureActivity"
    android:exported="true">
    <intent-filter>
        <action android:name="android.appwidget.action.APPWIDGET_CONFIGURE" />
    </intent-filter>
</activity>
<!-- QuoteWidgetConfigureActivity : persister le ticker choisi par instance via
     SharedPreferences keyed sur appWidgetId (pas GlanceStateDefinition — plus simple
     pour une valeur scalaire configurée une seule fois) :
     prefs.edit().putString("ticker_$appWidgetId", symbol).apply() -->
<!-- PnlWidgetConfigureActivity : même pattern (période day/week/month, clé "period_$appWidgetId"),
     référencée par android:configure dans pnl_widget_info.xml -->
<!-- WorkManager : ne pas déclarer manuellement, géré par la lib -->
```

### network_security_config.xml (`app/src/main/res/xml/`)

Le certificate pinning du trafic VPS est géré **uniquement via OkHttp** (`CertificatePinner` +
`BuildConfig`), pas via ce fichier (les valeurs `local.properties` ne peuvent pas être injectées
dans des XML Android au build time). Le fichier bloque le cleartext par défaut et référence la
Root CA interne de Caddy comme ancre de confiance additionnelle pour le trafic VPS :

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <!-- Défaut : cleartext bloqué (HTTPS uniquement pour le trafic VPS). -->
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
            <!-- Root CA interne Caddy (VPS trading-platform2, `tls internal`).
                 Le hash SPKI SHA-256 est en plus pinné par OkHttp CertificatePinner. -->
            <certificates src="@raw/caddy_root_ca" />
        </trust-anchors>
    </base-config>

    <!-- Domain-config LAN (préfixes IP RFC-1918) : hérité de la décision architecture C
         (cleartext explicitement permis pour joindre le pairing-server Radxa). Le pairing
         LAN réel passe désormais en HTTPS + LanTrustManager (§8) — ce bloc reste au cas où
         un appel cleartext LAN existerait, mais n'est plus le mécanisme de protection
         principal du port 8099. `<domain>` ne supporte pas les plages CIDR ; seuls quelques
         préfixes courants sont listés à titre indicatif, l'enforcement réel est côté code
         (`isLocalNetwork()` + `VpnRequiredInterceptor`). -->
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">10.0.0.0</domain>
        <domain includeSubdomains="true">172.16.0.0</domain>
        <domain includeSubdomains="true">192.168.0.0</domain>
    </domain-config>
</network-security-config>
```

### ProGuard — règles minimales obligatoires (`proguard-rules.pro`)

```proguard
# Retrofit — garder toutes les interfaces et @SerializedName / @Json
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit

# Moshi — garder les data classes annotées @JsonClass
-keep @com.squareup.moshi.JsonClass class * { *; }
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}

# Room — garder les entités et DAOs
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# Glance widgets — garder les GlanceAppWidget et GlanceAppWidgetReceiver
-keep class * extends androidx.glance.appwidget.GlanceAppWidget
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver

# WireGuard — garder les classes JNI et tunnel
-keep class com.wireguard.** { *; }

# RootBeer — garder les détections natives
-keep class com.scottyab.rootbeer.** { *; }

# Kotlin — garder les métadonnées pour la réflexion
-keepattributes *Annotation*
-keep class kotlin.Metadata { *; }

# Timber — supprimer les logs debug en release
-assumenosideeffects class timber.log.Timber {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
```

### Dépendances — voir `docs/gradle-setup.md`

> ⚠ Libs en alpha — **ne pas upgrader sans tester** :
> - `androidx.security:security-crypto-ktx:1.1.0-alpha06` (EncryptedDataStore)
> - `androidx.biometric:biometric-ktx:1.2.0-alpha05` (BiometricPrompt)

> **lazysodium-android** : `com.goterl:lazysodium-android` — wrapper libsodium utilisé par
> `SealedBoxHelper` pour le chiffrement `crypto_box_seal` des payloads LAN (pairing + maintenance).
> Voir `docs/gradle-setup.md` pour la version exacte et la configuration ABI splits.

---

## 12. SESSION ET PERSISTANCE

### Principe général

L'utilisateur **ne doit jamais avoir à se reconnecter** tant que son refresh token est valide.
Le token refresh est renouvelé silencieusement en arrière-plan par OkHttp — l'app et les widgets
restent fonctionnels sans interaction utilisateur.

### EncryptedCookieJar — refresh token httpOnly

Le `refresh_token` est un cookie httpOnly (non accessible en JS/Kotlin directement).
Il est persisté via un `CookieJar` OkHttp qui écrit dans `EncryptedDataStore` :

```kotlin
class EncryptedCookieJar(private val dataStore: EncryptedDataStore) : CookieJar {

    // Paths exacts (AuthPaths) — ne pas utiliser .contains("auth") qui matcherait n'importe quel
    // endpoint futur contenant "auth" dans son path. COOKIE_SAVE inclut /2fa/verify (+ alias
    // /verify-2fa) : pour un compte 2FA, c'est la vérification TOTP qui pose le refresh_token.
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (url.encodedPath in AuthPaths.COOKIE_SAVE) {
            // Filtrer sur le nom exact — ne pas persister les cookies analytics/tracking futurs
            cookies.filter { it.name == "refresh_token" }
                   .forEach { dataStore.save("cookie_${it.name}", it.toString()) }
        }
    }
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return if (url.encodedPath == AuthPaths.REFRESH) dataStore.loadCookies() else emptyList()
    }
}
```

### CsrfInterceptor — token double-submit

Le VPS requiert un token CSRF pour tous les `POST/PUT/DELETE/PATCH` (pas d'exemption Bearer).

```kotlin
// NE PAS injecter AuthApi — dépendance circulaire (AuthApi est construit avec cet OkHttpClient).
// Utiliser un OkHttpClient "bare" dédié sans interceptors pour le fetch du token CSRF.
// Le bareHttpClient doit avoir des timeouts courts — sans ça, un VPS lent bloque un thread OkHttp.
class CsrfInterceptor(
    private val bareHttpClient: OkHttpClient,  // @Named("bare") — connectTimeout 5s, readTimeout 5s
    private val baseUrl: String
) : Interceptor {
    private val mutex = Mutex()
    // Toujours lire csrfToken à l'intérieur du lock — évite le double-check fragile hors lock
    private var csrfToken: String? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method in listOf("POST", "PUT", "DELETE", "PATCH")) {
            val token = runBlocking { mutex.withLock { csrfToken ?: fetchCsrfToken() } }
            val response = chain.proceed(request.newBuilder().header("X-CSRF-Token", token).build())
            if (response.code == 403) {
                // Token CSRF invalide — invalider le cache et retry une fois
                response.close()
                val newToken = runBlocking { mutex.withLock { csrfToken = null; fetchCsrfToken() } }
                return chain.proceed(request.newBuilder().header("X-CSRF-Token", newToken).build())
            }
            return response
        }
        return chain.proceed(request)
    }

    private fun fetchCsrfToken(): String {
        val req = Request.Builder().url("$baseUrl/csrf-token").get().build()
        return bareHttpClient.newCall(req).execute().use { it.body?.string() ?: "" }
            .also { csrfToken = it }
    }
}

// Configuration du bareHttpClient dans NetworkModule :
// OkHttpClient.Builder()
//     .connectTimeout(5, TimeUnit.SECONDS)
//     .readTimeout(5, TimeUnit.SECONDS)
//     .build()
```

- Le token CSRF est mis en cache en mémoire (durée de vie = session)
- Sur réponse `403` CSRF invalide : invalider le cache, refetch et retry une fois
- Le `Mutex` garantit qu'un seul fetch est en vol simultanément (même pattern que `TokenAuthenticator`)

**Risque `runBlocking` sous charge :** sur le thread pool OkHttp, `runBlocking` pendant le
fetch CSRF peut saturer les threads si plusieurs requêtes parallèles attendent simultanément.
**Mitigation implémentée :** `CsrfInterceptor.preFetch()` pré-charge le token CSRF juste après
un login / une vérification 2FA réussis (`AuthRepositoryImpl`) et au démarrage si une session
existe (`TradingApplication`), si bien qu'il est en cache avant la première vraie requête —
contention nulle dans le cas nominal. Ne pas le déplacer dans `TokenAuthenticator` : le refresh
est exempt de CSRF et passe par un client dédié (voir ci-dessous).

### Token refresh transparent — TokenAuthenticator

```
[Requête API] → 401 AUTH_1002
    → TokenAuthenticator.authenticate()
        → POST /v1/auth/refresh (cookie envoyé automatiquement par EncryptedCookieJar)
        → Succès : nouveau access_token → retry requête originale
        → Échec 401 AUTH_1003 : logout forcé → LoginScreen
```

**Mécanisme de refresh concurrent (Deferred partagé, Mutex sur le champ uniquement) :**
Si plusieurs requêtes reçoivent un `401 AUTH_1002` simultanément, une seule doit déclencher
le refresh — les autres attendent le même `Deferred` et réutilisent le nouveau token.

**Client refresh dédié — `@Named("refresh")` (NetworkModule) :** `TokenAuthenticator` reçoit un
`@Named("refresh") AuthApi` construit sur un `OkHttpClient` propre : `Dispatcher` dédié
(maxRequests = maxRequestsPerHost = 2), `TimeoutInterceptor`, `VpnRequiredInterceptor`,
`EncryptedCookieJar`, timeouts 5 s, certificate pinning, logger debug HEADERS seulement.
**Jamais** de `CsrfInterceptor` (le refresh est exempt côté backend), d'`AuthInterceptor` ni
d'`Authenticator` (pas de refresh récursif). Aucune de ses dépendances ne dépend de
`TokenAuthenticator` → pas de cycle Hilt, pas de `dagger.Lazy`.

`TokenAuthenticator` est un `Authenticator` OkHttp sans scope intrinsèque : il reçoit le
`CoroutineScope` applicatif (`AppModule.provideApplicationScope()`, `SupervisorJob + IO`).

```kotlin
class TokenAuthenticator @Inject constructor(
    private val applicationScope: CoroutineScope,          // @Singleton — survit aux requêtes
    private val tokenHolder: TokenHolder,
    @Named("refresh") private val authApi: AuthApi,         // client refresh dédié
    /* dataStore, sessionManager, appDatabase, cookieJar */
) : Authenticator {
    private val mutex = Mutex()
    private var refreshDeferred: Deferred<String?>? = null

    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.priorResponse?.code == 401) return null           // 1 retry max par requête
        val path = response.request.url.encodedPath
        if (path == AuthPaths.REFRESH) { handleLogout(); return null }  // refresh token invalide
        if (path in AuthPaths.PUBLIC) return null                       // 401 login/2FA = échec métier
        val failed = response.request.header("Authorization")?.removePrefix("Bearer ")
        tokenHolder.accessToken?.let { if (it != failed) return retryWith(response, it) } // bearer périmé
        val token = runBlocking {
            withTimeoutOrNull(AUTHENTICATE_TIMEOUT_MS) { refreshOnce(failed).await() }  // n'annule que l'attente
        } ?: return null
        return retryWith(response, token)
    }

    // Le lock ne garde que le champ : jamais tenu pendant l'appel réseau.
    private suspend fun refreshOnce(failed: String?): Deferred<String?> = mutex.withLock {
        refreshDeferred?.takeIf { !it.isCompleted }
            ?: tokenHolder.accessToken?.takeIf { it != failed }?.let { CompletableDeferred(it) }
            ?: applicationScope.async { doRefresh() }.also { refreshDeferred = it }
    }
    // doRefresh() : tokenHolder.setToken() AVANT dataStore.writeString() ; non-2xx → handleLogout() ;
    // `catch (e: CancellationException) { throw e }` avant le catch générique.
}
```
Pas de `delay(5000)` — les threads en attente bloquent sur le `Deferred`, pas sur un timer.
Le timeout (8 s) n'annule que l'attente : le refresh finit dans `applicationScope` et alimente
`TokenHolder`, donc la requête suivante prend le chemin « bearer périmé » sans nouveau refresh.

### Découverte du portfolio_id — flow post-login

```
POST /v1/auth/login
    ├─ Succès → stocker access_token, user.id, user.is_admin dans EncryptedDataStore
    │
    ├─ Si user.totp_enabled == true
    │       → LoginViewModel appelle SessionManager.storePendingTotpToken(session_token)
    │         puis navigue vers TotpScreen (pas d'argument de nav)
    │       → POST /v1/auth/2fa/verify
    │       └─ Succès → continuer ci-dessous
    │
    └─ GET /v1/portfolios → stocker portfolios[0].id dans EncryptedDataStore (clé auth_portfolio_id)
            └─ Naviguer vers Dashboard
```

**Invariant :** chaque utilisateur a exactement un portfolio. `portfolios[0]` est toujours correct.
- `portfolios.isEmpty()` → logout forcé (état incohérent côté serveur)
- `portfolios.size > 1` → logger `[PORTFOLIO_MULTI] count=N`, prendre `portfolios[0]` sans UI de sélection

Le `portfolioId` est réutilisé pour tous les appels portfolio sans re-fetch.

### 2FA — flow TotpScreen

```
LoginScreen → [auth OK + totp_enabled] → TotpScreen → [POST /v1/auth/2fa/verify OK]
    → GET /v1/portfolios → Dashboard
```

`TotpScreen` ne reçoit **pas** le `session_token` via navigation args : `TotpViewModel` le lit
depuis `SessionManager.pendingTotpToken` (posé par `LoginViewModel.storePendingTotpToken()` juste
après le login), en mémoire process uniquement — jamais dans le `NavController` ni persisté.
`TotpViewModel` expose un `UiState` : `AwaitingInput | Verifying | Success | Error | BackToLogin`.
Le temp token est lu sans être consommé ; il n'est consommé que
sur succès ou sur 401 serveur (→ `BackToLogin`, le backend l'a brûlé). Timeout/IOException/429 →
`Error` récupérable, token conservé.

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
| `wg_endpoint` | Endpoint WireGuard du VPS |
| `wg_server_pubkey` | Clé publique WireGuard du VPS |
| `wg_tunnel_ip` | IP tunnel attribuée |
| `wg_dns` | DNS du tunnel |
| `setup_completed` | `true` après onboarding QR + premier login |
| `local_token_{device_id}` | local_token par device Radxa (persisté après pairing) |
| `cookie_*` | Cookies auth (refresh token httpOnly) |
