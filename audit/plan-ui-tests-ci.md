# Plan partiel — robustesse ViewModel/UI + tests + CI
Source : agent planificateur, 2026-09-28. $B = app/src/main/java/com/tradingplatform/app/, $T = app/src/test/java/com/tradingplatform/app/.
État : pas de CI (.github absent, seul un snippet dans docs/gradle-setup.md:380) ; build.gradle.kts : forkEvery=1, maxHeapSize=4g, Robolectric + MockWebServer + work-testing présents, Paparazzi opt-in (-PenablePaparazzi=true), pas de plugin jacoco (CLAUDE.md §6 périmé), pas de config lint, un seul androidTest (MigrationTest, actuellement cassé).

## PART 1 — Batches ViewModel/UI

### Batch 1 — DataState<T> pour NAV/PnL du Dashboard (#21) — M
Fichiers : nouveau $B/ui/common/DataState.kt, DashboardViewModel.kt:48-58,72-75,310-314,453-478, DashboardScreen.kt:69-74,125-129 + NavSection/PnlSection, WsRepository.kt:30-36, WsUpdate.kt:21-26.
```kotlin
data class DataState<T>(val value: T? = null, val isRefreshing: Boolean = false, val error: String? = null, val syncedAt: Long = 0L) {
    val isInitialLoading get() = value == null && error == null && isRefreshing
    fun loading() = copy(isRefreshing = true)                          // garde value
    fun success(v: T, now: Long) = DataState(v, false, null, now)
    fun failure(msg: String) = copy(isRefreshing = false, error = msg) // garde value (stale)
}
```
Screen : skeletons seulement si les deux isInitialLoading ; isRefreshing → PullToRefreshBox ; value≠null && error≠null → valeur périmée + CacheTimestamp + snackbar ; value==null && error → carte d'erreur. WS : backend envoie total_value/cash_balance/positions_value (pas de PnL ; l'app lit nav/daily_pnl/total_pnl → mapping mort) → appliquer NAV directement (currentValue = total_value) puis refetch NAV+PnL debounced 750 ms avec value conservée. Ne jamais remettre value=null après un premier succès.
Tests DashboardViewModelTest : WS après Success → value inchangée + isRefreshing ; échec refetch → value gardée + error ; 5 événements WS → 1 refetch.

### Batch 2 — Pagination et hygiène des jobs (#22, Positions, Orders) — M
Fichiers : TransactionHistoryViewModel.kt:44-63, TransactionHistoryScreen.kt:127-136, PositionsViewModel.kt:54-57,99-105, OrdersViewModel.kt:50-97, GetOrderHistoryUseCase.kt, OrdersRepositoryImpl.kt:30-35 (retourner count).
```kotlin
private var loadJob: Job? = null
fun loadMore() { if (loadJob?.isActive == true) return; loadJob = viewModelScope.launch { setLoadingMore(true); loadTransactions() } }
fun refresh() { loadJob?.cancel(); currentOffset = 0; allTransactions.clear(); loadJob = launch { ... } }
```
Screen : bouton `enabled = !isLoadingMore` + indicateur ; `distinctBy { it.id }`. Positions : loadJob annulé ; loadPositions(requestedFilter) et résultat ignoré si le filtre a changé. Orders : init → portfolioId vide = Error sans appel API ; historique paginé via count (Page<Order>(items,total)), loadMoreHistory().
Tests : TransactionHistoryViewModelTest (nouveau), PositionsViewModelTest (+ résultat périmé ignoré), OrdersViewModelTest (nouveau).

### Batch 3 — Résilience des flows + état VPN (Alerts, VpnSettings, WireGuard) — M
Fichiers : AlertsViewModel.kt:50-64, VpnSettingsViewModel.kt:42-53, VpnSettingsScreen.kt:141-184, vpn/VpnState.kt, WireGuardManager.kt:89-170, VpnStatusBanner.kt, AndroidManifest (WireGuardVpnService).
- Alerts : `.catch` à l'intérieur du flatMapLatest + `retryWhen` (≠ CancellationException, 3 tentatives, backoff) ; la chaîne externe reste vivante.
- VpnState : ajouter `SystemVpnActive` ; VpnSettingsViewModel : inApp Connected → inApp ; sysActive → SystemVpnActive ; écran « VPN système actif (tunnel externe) », boutons désactivés ; vérifier l'exhaustivité des when (VpnStatusBanner, VpnRequiredInterceptor le traite comme connecté).
- WireGuardManager : `opMutex` autour de connect/disconnect ; `backend ?: GoBackend(context).also { backend = it }` sous lock ; onStateChange(DOWN) → currentTunnel = null + startService(ACTION_DISCONNECT) pour tuer la notification ; retirer le BIND_VPN_SERVICE/intent-filter inutiles du service de notification.
Tests : AlertsViewModelTest (+ erreur puis reprise), VpnSettingsViewModelTest (+ SystemVpnActive), WireGuardManagerTest (interface TunnelBackend fake).

### Batch 4 — Pairing + Setup — S
Fichiers : PairingViewModel.kt:103-146,148-174, SetupViewModel.kt:61,110, nouveaux domain/repository/SetupRepository.kt + data/repository/SetupRepositoryImpl.kt + domain/usecase/setup/MarkSetupCompletedUseCase.kt, binding DI.
- Pairing : sur QR illisible, garder `_step` et émettre `_scanError: MutableSharedFlow<String>` (snackbar) ; les deux branches BothScanned mettent `_deviceInfo`.
- Setup : SetupRepository { markSetupCompleted(); isSetupCompleted() } sur EncryptedDataStore ; SetupViewModel ne dépend que du use case.
Tests : PairingViewModelTest (+2 cas), SetupViewModelTest (nouveau).

### Batch 5 — Composants : accessibilité, TTL cache, haptique, caméra, couleurs — S
Fichiers : ui/components/{PnlText,AnimatedPnlText,MoneyText,StatusBadge,VpnStatusBanner,ErrorBanner,ConnectionStatusIndicator}.kt, CacheTimestamp.kt:35-52, HapticFeedback.kt:52-55, QrScannerView.kt:133-213, MarketDataScreen.kt:449-452, ui/theme/ExtendedColors.kt.
- `semantics(mergeDescendants = true) { contentDescription = ... }` (clearAndSetSemantics pour les conteneurs décoratifs).
- `CacheTimestamp(syncedAt, ttlMs = 10 min, warnMs = ttlMs/2)` ; les appelants passent le TTL Room de leur entité.
- `remember(view) { HapticFeedbackHelper(view) }`.
- QrScannerView : garder cameraProvider dans un state ; onDispose → unbindAll() + shutdown + close.
- Couleurs du SourceQualityDot → LocalExtendedColors (ou ajouter dataRealtime/dataPolling/dataStale).
Tests : extraire `cacheTimestampLabel(ageMs, ttlMs)` pur (JVM) ; régénérer les snapshots Paparazzi.

Ordre : 1 → 2 → 3 ; 4 et 5 indépendants (parallélisables). ≈ 4 M + 2 S.

## PART 2 — Tests & CI

### (1) CI .github/workflows/android.yml — M
Prérequis build.gradle.kts : `lint { abortOnError = true; baseline = file("lint-baseline.xml") }` ; Gradle managed devices ; jacoco (ou retirer la ligne de CLAUDE.md §6). Secrets : LOCAL_PROPERTIES_B64, GOOGLE_SERVICES_B64 (google-services.json requis même pour les tests unitaires).
Jobs : `unit` (testDebugUnitTest + lintDebug + verifyPaparazziDebug allow-failure, upload reports) ; `instrumented` (needs unit, KVM, `api30DebugAndroidTest api34DebugAndroidTest`, gpu swiftshader).
```kotlin
android.testOptions.managedDevices.localDevices {
    create("api30") { device = "Pixel 5"; apiLevel = 30; systemImageSource = "aosp-atd" }  // épingle #9 Instant.parse pré-JDK12
    create("api34") { device = "Pixel 6"; apiLevel = 34; systemImageSource = "aosp-atd" }  // FGS/VPN
}
```
androidTest à ajouter : MigrationTest (existant, vert après #5), ui/components/BiometricLockOverlayTest (createAndroidComposeRule<MainActivity> ; lock() → overlay visible, dashboard invisible, pressBack → toujours verrouillé), ui/screens/setup/SetupSmokeTest (Hilt + MobileProvisioningRepositoryImpl réel sur MockWebServer → attrape #3), security/SealedBoxHelperInstrumentedTest (libsodium réel).

### (2) Tests manquants — priorité
P0 (sécurité/session, JVM + MockWebServer) :
1. AuthInterceptorTest (+) : 2fa/verify, verify-2fa, /csrf-token avec token nul → la requête part, pas de notifyForcedLogout ; chemin non public + token nul → 401 synthétique.
2. TokenAuthenticatorTest (nouveau) : 3 requêtes 401 parallèles → exactement 1 POST /refresh ; Bearer ≠ token courant → retry sans refresh ; refresh 401 → handleLogout ; priorResponse 401 → null ; timeout → null sans logout.
3. CsrfInterceptorTest (nouveau) : 403 → 1 refetch puis retry, 2e 403 rendu tel quel ; 5 POST parallèles → 1 fetch ; GET ne fetch jamais ; body rejoué à l'identique.
4. EncryptedDataStoreTest (Robolectric) : round-trip ; clearSession préserve WG_* ; corruption → récupération (rouge jusqu'au fix #17).
5. SealedBoxHelperTest réel (injection LazySodium + lazysodium-java en testImplementation, ou androidTest).
P1 (intégrité) : PortfolioRepositoryImplTest (Room in-memory + MockWebServer : getPnl écrit pnl_snapshots ; TTL positions) ; WidgetUpdateWorkerTest avec repository réel ; MarketDataDtoDecodingTest avec fixtures copiées du backend ; PublicWsClientTest (ref-count).
P2 : ViewModel tests Settings/Profile/MyDevices/Orders/TransactionHistory/Performance (maxDrawdown → « 8,30 % »).

### (3) Garde de drift de contrats — S
Faits : /openapi.json backend seulement en dev (main.py:1275) mais un schéma complet est commité (trading-platform2/audit/front/openapi.json, 490 paths) et tools/codegen/openapi_to_ts.py le génère in-process avec un `--check` (Makefile:254-258).
Design : cible `make openapi-android` qui écrit une copie réduite dans app/src/test/resources/contracts/openapi.json ; test JVM $T/contracts/DtoContractTest.kt : table DTO ↔ nom de schéma, réflexion sur @Json(name) → chaque champ non optionnel du DTO existe dans schema.properties, chaque `required` du schéma correspond à un param non-nullable/défauté, accord nullabilité ; second test : chaque chemin @GET/@POST des *Api.kt existe dans `paths`. Fichier ws_events.json maintenu à la main pour les événements WS. Aurait attrapé #6, #7, #23.

### Ordre global
1. Squelette CI (unit + lint) — M. 2. Tests P0 — M. 3. Batches 1+2. 4. Tests P1 + job instrumenté (vert après #1/#3/#5). 5. Batches 3-5. 6. Garde de contrats + P2. 7. Passe docs (CLAUDE.md §6 jacoco + garde runCatching, api-contracts.md, clés WsUpdate).
