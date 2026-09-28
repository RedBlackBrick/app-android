# Audit NOTES — app-android (2026-09-28)

Scope: /home/thomas/Codes/app-android (Kotlin/Compose, ~27k LOC main, 38 unit test files, 1 androidTest)
Context repos (read-only, for contract checks): trading-platform2, radxa-ramboot
Branch: feature/redesign-mobile-onboarding @ 171902c (clean)

## Inventory (files / lines)
- data/api 19f/1067 · data/api/interceptor 8f/734 · data/local 15f/872 · data/model 29f/801
- data/repository 16f/1246 · data/session 2f/136 · data/websocket 5f/1024 · di 8f/678
- domain 99f/2317 · fcm 3f/331 · security 9f/455 · vpn 6f/476 · widget 15f/1879
- ui/components 17f/2470 · ui/navigation 4f/1229 · ui/screens 44f/11391 · ui/theme 9f/709
- root: MainActivity 150, TradingApplication 137, CrashlyticsTree 26
- build: compileSdk 36, minSdk 26, minify release, Room schemaDirectory on, DEV_MODE flag

## Delegation plan
- Gemini flash (antigravity-delegate): API contract drift app ↔ trading-platform2 / radxa-ramboot
- audit-lead A "network-session": data/api, interceptors, data/session, di/NetworkModule+SecurityModule, security/*, data/local/datastore
- audit-lead B "data-domain": data/repository, data/local/db, data/model, data/websocket, domain/*, di/RepositoryModule+DatabaseModule+WebSocketModule
- audit-lead C "app-ui": MainActivity, TradingApplication, ui/navigation, ui/screens (VMs first), ui/components, vpn/*, security/Biometric*
- audit-lead D "background": widget/*, fcm/*, di/WidgetModule, WidgetUpdateWorker
- Self: build/test/lint gates, cross-module synthesis, ranking

## Status log
- [ ] inventory written
- [ ] leads launched
- [ ] gates run
- [ ] verification
- [ ] report

## Findings status (candidate → confirmed/rejected/plausible)
See audit/verified.md (full ledger, 50 rows) and audit/REPORT.md (final ranking). All ≥medium candidates went through an Opus verifier or a direct orchestrator check; lead-only medium/low items are listed in REPORT §2 as unverified.

## REMEDIATION HANDOFF (2026-09-28, usage limit reached mid-run) — READ FIRST TO RESUME
Branch `audit/remediation` (from feature/redesign-mobile-onboarding), 7 commits: 15c782c phase 0 · 7035bae phase 1 wave A · 341b6c6 PnL/WS/TokenAuthenticator · 02526ff biometric lock · ca05060 market data · 9ab9d85 WS lifecycle/pagination/VPN/cache/FCM · 07eb4b1 public WS/components/instrumented CI. Last full gate: 509 unit tests, 0 failures (before the uncommitted items below).

UNCOMMITTED in the main tree (staged): PR 5b tests (7 new test files + lazysodium-java/jna test deps + SealedBoxHelper internal ctor), PR 5c contract guard (scripts/extract_openapi_contracts.py, app/src/test/resources/contracts/*, contracts/DtoContractTest.kt, kotlin-reflect test dep, docs/api-contracts.md section), lint fix (@file:OptIn ExperimentalGetImage in QrScannerView.kt). A gate was started: audit/gate-phase5a.log (check EXIT= and failing suites). EXPECTED: DtoContractTest partially RED until PR 2.5 below; lazysodium-java resolution unverified.

STILL RUNNING when the limit hit (results land in worktrees under .claude/worktrees/agent-*; apply with `git -C <wt> add -A && git -C <wt> diff --cached --binary > p.patch && git apply --3way --index p.patch`):
- PR 4.1 Dashboard DataState (worktree agent-adc5a34548cb09d6c, branch pr-4.1-dashboard from 07eb4b1)
- PR 3.3 runCatchingCancellable rollout + MarketData dedup + NoBareRunCatchingTest (worktree agent-a868a7c7c1598c7c9, branch pr-3.3-cancellable from 07eb4b1)

REMAINING after those: PR 2.5 (fix the 6 drifts found by DtoContractTest — see PR5c note above; files WsRepository.kt, PrivateWsClient notification parse, PublicWsClient market_data source fields, PositionDto nullability decision, DeviceDto phantom fields) · 5a docs pass (CLAUDE.md §2 package tree + ui/common, §12 TotpScreen nav-args sentence, "fermée en arrière-plan" sentence; docs/pairing-flow.md admin section; docs/security-model.md done by 4.3) · lint warnings triage (85 warnings; consider lint.xml for TrustAllX509TrustManager/CustomX509TrustManager which are documented design) · escape-hatch fix (unlock before logout; clear TokenHolder) · VpnService.prepare() consent follow-up · final gate + lint + commit audit/ folder.

Gate command (RAM cap): `./gradlew --no-daemon --max-workers=1 -Dorg.gradle.jvmargs="-Xmx1400m -XX:MaxMetaspaceSize=512m" -Pkotlin.compiler.execution.strategy=in-process -q testDebugUnitTest --continue` ; lint needs -Xmx2048m.

## Final status (2026-09-28)
- [x] inventory · [x] 4 leads + contract reviewer · [x] 14 Opus verifiers + self checks
- [x] gate unit tests PASS (memory-capped run) · [ ] lint NOT run (RAM cap) · [ ] connectedAndroidTest NOT run (no emulator)
- [x] audit/REPORT.md written. Orchestrator re-ranking decisions: "feature never works" drifts (symbols, history, PnlWidget) ranked high although verifiers said medium; runCatching systemic downgraded critical→medium; admin-gating rejected as code defect (doc drift); biometric no-op promoted to the single CRITICAL.

## Constraints (user, 2026-09-28)
- RAM cap 3 GB on the PC. First gradle run (testDebugUnitTest + lint) was aborted; an orphan Gradle daemon (~2.3 GB, JBR 21) remains and the classifier refused to stop it. Gates deferred: run only after leads finish, memory-capped, and only if the daemon is reusable/gone. Leads told not to run gradle.
- User is remote (no PC access): avoid anything needing sudo/password.

## Status log
- [x] inventory written
- [x] leads launched (4 audit-lead + 1 Gemini contract-drift)
- [ ] gates run (deferred, see constraints)
- [ ] verification
- [ ] report

## audit-lead D "background" — units, coverage, open questions (2026-09-28)

Scope covered: widget/*, fcm/*, di/WidgetModule.kt+AppModule.kt, domain/usecase/notification/RegisterFcmTokenUseCase.kt,
domain/usecase/auth/ApplyAdminWidgetVisibilityUseCase.kt, domain/repository/AdminWidgetVisibilityManager.kt,
TradingApplication.kt, AndroidManifest.xml (widget/FCM/boot components).

Review units (7 reviewers, all read-only, no gradle/build commands used — RAM constraint honored):
1. WidgetUpdateWorker — correctness + error handling (full 326-line read + WidgetUpdateWorkerTest.kt)
2. WidgetUpdateWorker — performance + concurrency
3. Glance widgets (Pnl/Positions/Alerts/SystemStatus/Quote + WidgetTheme + WidgetModule + 5 Receivers) — correctness + data integrity
4. Widget config activities (Pnl/QuoteWidgetConfigureActivity) + AdminWidgetVisibilityManagerImpl + ApplyAdminWidgetVisibilityUseCase — security + correctness
5. FCM service (TradingFirebaseMessagingService, FcmTokenManager, RegisterFcmTokenUseCase) — security + correctness
6. FcmTokenRegistrationWorker + RegisterFcmTokenUseCase — error handling + correctness
7. TradingApplication (Hilt worker factory, WorkManager scheduling, boot) — correctness

Key confirmed facts (verified directly by lead, not just reviewer claims):
- `PnlWidgetConfigureActivity.kt` (233 lines) exists but is registered NEITHER in AndroidManifest.xml NOR via
  `android:configure` in `pnl_widget_info.xml` (confirmed: only `quote_widget_info.xml` has that attribute) — dead code.
- `WidgetEntryPoint` (di/WidgetModule.kt) correctly implements the mandated EntryPointAccessors pattern; all
  Glance widgets use it (spot-checked PnlWidget.kt) — base CLAUDE.md §2 Glance+Hilt rule is respected.
- `WidgetUpdateWorker.kt`'s own doc-comment (line 40: "IOException → Result.retry() si au moins un bloc a échoué")
  contradicts its own implementation (line 180: `ioFailCount.get() == ioSectionsTotal` — retries only if ALL 3
  sections fail) — self-contradiction inside one file, high confidence.
- `RECEIVE_BOOT_COMPLETED` permission declared (manifest:21) with no app-level receiver anywhere under
  app/src/main/java — vestigial but not a functional bug (WorkManager's own merged components use it).
- TradingApplication correctly implements `Configuration.Provider` + `HiltWorkerFactory`, enqueues
  `WidgetUpdateWorker` with `ExistingPeriodicWorkPolicy.KEEP` + `NetworkType.CONNECTED`, 15 min (Android's floor;
  CLAUDE.md's "5 min" is aspirational/inaccurate vs platform constraint).

Open questions / could-not-determine (flagged by reviewers, not resolved):
- Whether `AddToWatchlistUseCase` pre-seeds `quoteDao` for watchlist symbols the user never opened in
  MarketDataScreen (would mitigate widgetupdateworker-correctness-1) — needs Repository/DAO-layer check, out of
  this unit's scope.
- Real-world likelihood of the FCM onNewToken/FcmTokenRegistrationWorker race (fcmworker-correctness-1) and of the
  Room-insert-vs-process-kill race in TradingFirebaseMessagingService (fcm-correctness-1) — both device/timing
  dependent, not verifiable from source alone.
- Whether any in-app trigger besides login (e.g. silent `/auth/me` refresh) re-applies admin widget visibility on
  demotion — grep found none, but coverage isn't exhaustive of every possible future call site.
- Gemini contract-drift delegation FAILED (3 attempts: shell tool denied in headless, then empty output after 10 min). Replaced by an audit-reviewer (Sonnet) "contract" run. No files modified.
- [x] lead D (background) reported: 19 candidates → audit/candidates-D-background.md

## audit-lead B "data-domain" — units, coverage, open questions (2026-09-28)

Scope covered: data/repository/*.kt (16), data/local/db/** (AppDatabase+6 migrations, 6 DAOs, 6 entities) + app/schemas/*.json (7),
data/model/** (Mappers.kt + BigDecimalAdapter + InstantAdapter + 26 DTOs), data/websocket/** (PrivateWsClient, PublicWsClient,
WsBackoff, WsEvent, PublicWsEvent), domain/** (models, repository interfaces, usecases incl. pairing/auth), di/RepositoryModule.kt,
di/DatabaseModule.kt, di/WebSocketModule.kt.

RAM constraint honored: no gradle/build/test commands run by lead or any of the 9 reviewers — Read/Grep/Glob/Bash(cat,grep,find,wc) only.

9 audit-reviewer units (all read-only, parallel):
1. WS-A — PrivateWsClient + WsRepository + WebSocketModule (concurrency, security, error handling)
2. WS-B — PublicWsClient + PublicWsRepositoryImpl + WsBackoff + WsEvent/PublicWsEvent (concurrency, error handling)
3. REPO-AUTH — AuthRepositoryImpl + auth usecases (correctness, security, concurrency)
4. REPO-PAIRING — PairingRepositoryImpl + MobileProvisioningRepositoryImpl + pairing usecases (security, correctness, concurrency)
5. REPO-PORTFOLIO-MARKET — Portfolio/MarketData/Watchlist repos + usecases (correctness, data integrity)
6. REPO-MISC — Device/MyDevices/BrokerConnection/Notification/Orders/Risk/Strategies/Alert repos + usecases (correctness, error handling)
7. MAPPERS-DTO — Mappers.kt + BigDecimalAdapter + InstantAdapter + all DTOs (correctness, data integrity)
8. ROOM-SCHEMA — AppDatabase migrations vs 7 exported schema JSONs (data integrity, correctness)
9. ROOM-DAO-ENTITY — 6 DAOs + 6 entities, retention/upsert policy (data integrity, correctness)

Headline results: 3 critical Room migration gaps (MIGRATION_1_2 entirely missing; MIGRATION_3_4 doesn't touch pnl_snapshots
column rewrite; MIGRATION_4_5 doesn't relax NOT NULL constraints on positions/devices/quotes) — any release-build user upgrading
from schema v1, v3, or v4 crash-loops on launch. 1 critical main-thread network call in MobileProvisioningRepositoryImpl.register()
(NetworkOnMainThreadException on every onboarding attempt). 1 critical/high data-correctness bug found independently by two
reviewers: PerformanceResponseDto → PerformanceMetrics mapping skips the /100.0 normalization PnlResponseDto applies, inflating
totalReturnPct/maxDrawdown/volatility/cagr 100x on PerformanceScreen. Systemic pattern across nearly every repository
(Auth/Portfolio/MarketData/Watchlist/Device/MyDevices/BrokerConnection/Notification/Orders/Risk/Strategies/Alert/Pairing):
`runCatching {}` around suspend network calls does not exclude CancellationException, breaking structured concurrency
repo-wide (stdlib `runCatching` catches `Throwable`).

Open questions / could not verify from this unit alone (would need cross-check with network-session or app-ui leads):
- Whether `EncryptedCookieJar`/`TokenAuthenticator`/`CsrfInterceptor` (network-session scope) have the same CancellationException-
  swallowing pattern — not reviewed here, but given the repo-wide pattern found, worth checking.
- Whether ViewModels (app-ui scope) actually observe the swallowed-cancellation failures as spurious "Error" states, or whether
  something upstream re-checks `isActive`/coroutineContext before mutating StateFlow.
- No Room `MigrationTestHelper` (androidTest) exists to have caught the 3 critical migration gaps at build/CI time — none of the
  found migration bugs are covered by any existing test.
- No repository-level unit tests exist at all (grep of app/src/test found zero `*RepositoryImplTest.kt`), no DAO tests, no
  PrivateWsClient/PublicWsClient/WsRepository tests (only WsBackoffTest.kt, which tests the pure function, not call-site wiring).
- `PortfolioRepositoryImpl.getPnl()` shares the same PerformanceResponseDto scaling bug but grep found no live callers — flagged
  as same-root-cause, not filed separately.
- [x] lead B (data-domain) reported: 17 candidates → audit/candidates-B-data-domain.md

## audit-lead A: network-session (completed)

Scope: data/api/*.kt + interceptor/*.kt, data/session/*, data/local/datastore/*, di/NetworkModule+SecurityModule, security/* (CertificatePinner, LanTrustManager, KeystoreManager, NetworkUtils, SealedBoxHelper, SealedLanRequest, RootDetector), network_security_config.xml, build.gradle.kts BuildConfig, proguard-rules.pro, existing tests.

### Review units (6 parallel audit-reviewer runs, static analysis only per RAM constraint)
1. interceptor-concurrency — CsrfInterceptor/TokenAuthenticator/AuthInterceptor runBlocking+Mutex+Deferred patterns
2. interceptor-chain-correctness-security — chain wiring, bypass-path lists, logging redaction, Timeout/UpgradeRequired
3. vpn-cert-pinning — VpnRequiredInterceptor, SystemVpnMonitor, CertificatePinner, LanTrustManager, network_security_config.xml
4. secure-storage — EncryptedDataStore, SessionManager, TokenHolder, EncryptedCookieJar, KeystoreManager
5. retrofit-sealedbox — all Retrofit API interfaces, SealedBoxHelper/SealedLanRequest, RootDetector
6. test-coverage — gap analysis of the 5 existing test files vs. the above classes

### Headline findings (see full report handed to orchestrator for contract-format detail)
- CRITICAL: `/v1/auth/2fa/verify` is missing from `AuthInterceptor.PUBLIC_PATHS` and `CsrfInterceptor.csrfExemptPaths` (present only in `VpnRequiredInterceptor.VPN_EXCLUDED_PATHS`) → every TOTP-enabled login is broken (synthetic 401 + forced logout mid-login). `AuthInterceptor.kt:42-47,74-86`.
- CRITICAL: `DataStoreKeys.BIOMETRIC_LOCKED` is excluded from `EncryptedDataStore.criticalKeys` → persisted via async `apply()` instead of `commit()` → biometric lock state can be lost on process kill, exposing trading data unlocked on relaunch. `EncryptedDataStore.kt:37-39,73-82,251-255`.
- HIGH: `TokenAuthenticator`'s "reuse in-flight refresh" branch is unreachable dead code (mutex held across the whole `deferred.await()`), so concurrent 401s trigger N redundant serialized `/v1/auth/refresh` calls instead of 1. `TokenAuthenticator.kt:79-98`.
- HIGH (cross-module, self-verified): `TokenHolder`'s doc claims AuthInterceptor falls back to `EncryptedDataStore` on a null in-memory token — no such fallback exists in code. Confirmed the real risk: `TradingApplication.onCreate()` preloads `TokenHolder` via a fire-and-forget `appScope.launch` with no gating in `MainActivity.onCreate()/setContent()` (`TradingApplication.kt:67,73-107`, `MainActivity.kt:51-73`) → a cold-start request racing the preload can force-logout a user with a perfectly valid persisted session.
- HIGH: `EncryptedDataStore.sharedPreferences` is a `by lazy` that permanently caches `null` after the first Keystore `GeneralSecurityException`; no code path deletes/regenerates that master key → install is bricked (forever "corrupted session" state) after one Keystore invalidation, contradicting `SessionManager`'s own doc claim of a recovery UI. `EncryptedDataStore.kt:51-70`.
- HIGH: `SystemVpnMonitor.active` is satisfied by ANY system-level VPN (`NetworkCapabilities.TRANSPORT_VPN`), not specifically the app's WireGuard tunnel — `VpnRequiredInterceptor` accepts it as equivalent to `VpnState.Connected`, weakening the "we're on the right network" guarantee for both the VPS and LAN-pairing clients. `SystemVpnMonitor.kt:46-70,95-103`.
- HIGH: Debug-build `HttpLoggingInterceptor` (`Level.BODY`) logs plaintext login password + refresh/access tokens in request/response bodies; the `Cookie: csrf_token=...` header CsrfInterceptor sets is not in the `redactHeader(...)` list despite `X-CSRF-Token` being redacted. `NetworkModule.kt:119-128`.
- Test coverage: CsrfInterceptor, TokenAuthenticator, EncryptedDataStore, KeystoreManager, CertificatePinner, LanTrustManager, TimeoutInterceptor, UpgradeRequiredInterceptor all have **zero** dedicated unit tests. `SealedBoxHelperTest.kt` never actually instantiates `SealedBoxHelper` (asserts an inlined `require()`, not the real class) — the pairing crypto path has no real coverage.

### Open questions / not fully resolved
- Whether the VPS backend's real CSRF endpoint is `/v1/auth/csrf-token` or root `/csrf-token` (3 bypass lists say the former, the actual `bareHttpClient` fetch hits the latter) — can't confirm from this repo alone; currently harmless only because the bare client has no interceptors to consult those lists.
- Real-world deployed value of `VPS_BASE_URL` (only the gradle default `10.42.0.1` was inspected) — the VPN-exclusion of `/v1/auth/login` + `/v1/auth/2fa/verify` is only "safe" because that default is a private RFC-1918 IP.
- Whether `androidTest/` (Robolectric or instrumented) covers any of KeystoreManager/CertificatePinner's Keystore-dependent paths — only `app/src/test` was checked per the module's JVM-test scope.

## audit-lead C "app-ui-vpn" — units, coverage, open questions (2026-09-28)

Scope covered: MainActivity.kt, TradingApplication.kt, CrashlyticsTree.kt, ui/navigation/**,
ui/screens/** (all ViewModels + hosting Screens), ui/components/** (except BiometricLockOverlay
deep-dive, covered under auth unit), vpn/**, security/BiometricLockManager.kt, security/BiometricManager.kt,
security/KeystoreManager.kt, security/SealedBoxHelper.kt, security/LanTrustManager.kt, security/RootDetector.kt,
security/CertificatePinner.kt, security/SealedLanRequest.kt, security/NetworkUtils.kt, AndroidManifest.xml.
RAM constraint (3GB cap) honored — no gradle/build/test execution by lead or any of the 11 reviewers,
static/grep/read analysis only.

Review units (11 audit-reviewer agents, all read-only, run in parallel batches due to a shared
20-subagent concurrency cap with sibling audit-leads):
1. VPN core (WireGuardManager/Service/SystemVpnMonitor/VpnSettingsViewModel) — correctness, concurrency, security, error handling
2. Auth/session/biometric (MainActivity, TradingApplication, Login/Totp/Setup VMs, BiometricLockManager/Manager, BiometricLockOverlay) — security, correctness
3. Navigation + admin gating (AppNavGraph 815L, BottomNavBar, NavTransitions, Screen.kt, AndroidManifest) — correctness, security
4. Dashboard + MarketData (VMs + Screens + ActivityFeedCard + SymbolPickerSheet) — concurrency, correctness, performance
5. Positions/PositionDetail/TransactionHistory/Performance VMs+Screens — concurrency, correctness, error handling
6. Orders + Alerts VMs+Screens — correctness, error handling, performance
7. Devices (admin) VM + 3 Screens (incl. EdgeDeviceDashboardScreen 964L) — correctness, security (admin gating)
8. Pairing (VM + 4 Screens) — correctness, security (CLAUDE.md §8 compliance)
9. Settings/Profile/SecuritySettings/MyDevices VMs+Screens — correctness, security
10. Shared Compose components (QrScannerView, VpnStatusBanner, AnimatedPnlText, PnlText, MoneyText, StatusBadge, MetricsComponents, CacheTimestamp, ShimmerEffect, HapticFeedback, SparklineChart, etc.) — performance, correctness, accessibility
11. Security misc helpers (KeystoreManager, SealedBoxHelper, LanTrustManager, RootDetector, CertificatePinner, SealedLanRequest, NetworkUtils) — security, correctness

Lead-level verification performed (not delegated):
- Rejected reviewer unit 1's critical claim that WireGuard tunnel bring-up is entirely broken
  (GoBackend never wired to a declared VpnService component): extracted the actual
  tunnel-1.0.20250531.aar from the Gradle cache and confirmed its own AndroidManifest.xml declares
  `<service android:name="com.wireguard.android.backend.GoBackend$VpnService" .../>` with the
  `android.net.VpnService` intent-filter — this merges into the app manifest at build time via the
  standard AGP manifest merger, so the app's own `WireGuardVpnService` (foreground-notification-only,
  by design) does not need to duplicate that declaration. Evidence path:
  /home/thomas/.gradle/caches/modules-2/files-2.1/com.wireguard.android/tunnel/1.0.20250531/.../tunnel-1.0.20250531.aar
  (AndroidManifest.xml inside the AAR). Dropped from final findings.
- Verified unit 2's critical claim (MainActivity.isBiometricLocked desync) by reading MainActivity.kt
  and grepping onBiometricUnlocked/onAuthSuccess call sites: confirmed `MainActivity.onBiometricUnlocked()`
  (line 122) is dead code, never called; the real unlock path is `AppNavViewModel.onBiometricUnlocked()`
  (AppNavGraph.kt:248, wired at :807) which never touches MainActivity's local field. Confirmed CRITICAL.
- Cross-referenced unit 3 (nav-security-1/2), unit 7 (devices), unit 8 (pairing), unit 9 (settings) —
  all four independently converge on the same root cause (pairing/device-detail routes and the
  MyDevicesScreen entry point have zero is_admin enforcement anywhere in the client: not in the nav
  graph, not in PairingViewModel, not in DevicesViewModel/EdgeDeviceDashboardScreen, not in
  MyDevicesViewModel). Consolidated into one finding family below.

Coverage gaps (ViewModels with NO unit test, confirmed via `find app/src/test -name "*Test.kt"`):
MarketDataViewModel, OrdersViewModel, TransactionHistoryViewModel, PerformanceViewModel,
SetupViewModel, SettingsViewModel, ProfileViewModel, MyDevicesViewModel.

Open questions for other leads / follow-up:
- LAN pairing transport: reviewer for unit 11 found `PairingRepositoryImpl` actually calls
  `https://$deviceIp:$devicePort/pin` (HTTPS, via a scoped trust-all `LanTrustManager`), not the
  plain HTTP documented in CLAUDE.md §8/§11 ("Cleartext permis... nécessaire pour POST http://radxa_ip:8099/pin").
  Not a vulnerability per se (properly scoped OkHttpClient, payload already crypto_box_seal'd) but the
  docs are stale — worth a CLAUDE.md correction, flagged for whichever lead/orchestrator owns docs.
- Whether the VPS actually rejects pairing/device-detail requests from non-admin accounts is outside
  this service's scope (client-only audit) — the client-side gating gap is real regardless, but real-world
  severity depends on unverified server-side enforcement.
- Verifiers launched (Opus): room-migrations(B-room-1/2/3), perf-100x(B-dto-1), main-thread+ws-connect(B-pairing-1,B-ws-a-1/2), publicws+getPosition(B-ws-b-1/2,B-pm-3), widget-worker(D-wuw-corr-2,D-wuw-err-1,D-widgets-di-1), fcm(D-fcmworker-*, D-fcm-corr-1)
- SELF-VERIFIED B-auth-conc-1/B-misc-1 (systemic runCatching): grep shows ~45 runCatching in data/repository + 6 in domain/usecase; the ONLY CancellationException rethrow guard is PrivateWsClient.kt:235. CONFIRMED as systemic, severity re-ranked MEDIUM (misreported cancellation, no crash; coroutine already cancelled so next suspend point throws). CLAUDE.md §2 template itself lacks the guard → doc follow-up.
- [x] lead A (network-session) reported: 23 candidates → audit/candidates-A-network.md
- [x] lead C (app-ui-vpn) reported: ~40 candidates → audit/candidates-C-ui-vpn.md
- [x] contract reviewer reported: 4 drifts → audit/candidates-E-contracts.md
- PR2.2 open point: pnl_snapshots TTL purge (5 min) can delete other periods' rows → handle in PR 4.4 (CacheTtl: PNL purge cutoff ≥ 24h or drop purge)
- PR3.1 open point: TradingApplication preload sets token unconditionally → guard with  (owner: PR 3.2 agent)
- PR3.1 open point: TradingApplication preload sets token unconditionally -> guard with if (tokenHolder.accessToken == null) (owner: PR 3.2 agent)
- PR2.3 worktree was based on d6f0dd7 (main) not 7035bae; patch applied with --3way onto audit/remediation
- PR1.2/1.3 open points to fold into phase 4: escape hatch calls unlock() before forced logout (brief content frame) -> logout+navigate first then unlock; corrupted-keystore cold start shows biometric prompt + corruption dialog simultaneously; fragment 1.8.9 version to confirm at gate
- PR4.3 open points: no VpnService.prepare() call in app (consent) -> follow-up; WidgetUpdateWorker checks only WireGuardManager.state (skips sync when only a system VPN is up) -> follow-up; START_STICKY null-intent restart without startForeground (pre-existing)
- PR3.2 open points: escape hatch emits forced logout without clearing TokenHolder (fold into escape-hatch fix); CLAUDE.md sentence 'fermee en arriere-plan' inaccurate
- PR5c found 6 new drifts (to fix in PR 2.5 after 3.3): order_update fill_price→price; notification type→notification_type (+dead message fallback); catalyst_event event_type→catalyst_type, title/description nested in data; public market_data source_name/source_type/quality not sent (only source); PositionDto quantity/avgPrice non-null vs backend Optional (DDL NOT NULL → keep non-null but accept in test? decide: nullable with default ZERO); DeviceDto hostname/scrapersCircuit/availableMemoryMb never sent
- PR1.7 open points: isLoggedIn never returns true after in-app login (VPN banner + FCM deep-link effects read it) -> follow-up: handle NavHost startDestination change; server logout 30 s timeout keeps the overlay without progress
