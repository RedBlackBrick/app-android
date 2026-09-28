# Plan de remédiation — app-android

> **État (2026-09-28)** : exécuté intégralement sur la branche `audit/remediation` — voir `audit/REPORT.md` §9 pour le tableau finding → PR → état, et §10 pour les suivis.

Date : 2026-09-28 · Base : `audit/REPORT.md` (audit) et les cinq plans partiels `audit/plan-*.md` (conception détaillée, sketches de code, tests). Cinq agents planificateurs ont lu le code existant, le backend et le serveur de pairing Radxa ; l'orchestrateur a consolidé, arbitré et ordonné.

Contexte : l'app n'a jamais été livrée (versionCode 1). On peut donc casser des formats internes (Room, entités) sans migration, et l'ordre est dicté par la sécurité puis par « la fonctionnalité ne marche jamais ».

Nouveaux défauts découverts pendant la planification (en plus du rapport) :
- **Cookie refresh non persisté après 2FA** — `EncryptedCookieJar.kt:37` ne sauvegarde que login/refresh ; le backend pose le cookie sur `/2fa/verify`. Vérifié. Sans correctif, un compte 2FA est déconnecté à l'expiration du premier access token (une fois #2 corrigé).
- `portfolio_update` WS : l'app lit `nav/daily_pnl/total_pnl`, le backend envoie `total_value/cash_balance/positions_value` → champs toujours nuls (deux planificateurs, non vérifié par l'orchestrateur).
- `PnlPeriod.YEAR` envoie `year`, le backend attend `ytd` → 422 (planificateur, à vérifier).
- Une fois le verrou biométrique réactivé : le thème `Theme.Material` fait planter `BiometricPrompt` sur API 26-27, et `UserNotAuthenticatedException` (clé Keystore expirée après 300 s) n'est pas attrapée → crash. Les deux doivent être traités dans le même lot que #1.
- `ParseSetupQrUseCase.kt:93` parse `expires_at` avec `Instant.parse` (même risque que #9).

---

## Décisions à prendre (par toi) avant de démarrer

| # | Décision | Recommandation |
|---|---|---|
| D1 | Thème : passer à `Theme.AppCompat.DayNight.NoActionBar` (+ appcompat, fragment-ktx) **ou** `minSdk = 28` | `minSdk = 28` si aucun device API 26-27 n'est visé : moins de dépendances, prompt système partout. Sinon AppCompat. |
| D2 | Room : reset baseline v7 (supprimer schémas 1-6 et migrations) **ou** compléter les migrations | Reset baseline v7. |
| D3 | Contrats market-data : adapter l'app **ou** ajouter des alias backend | Adapter l'app (contrat backend riche, paginé, déjà consommé par le web). |
| D4 | Ajouts backend additifs (petits, sans risque web) : `position_id` dans `position_update` ; ne supprimer le temp token 2FA que sur succès TOTP ; `executed_at` typé `datetime` | Oui, mais l'app ne doit dépendre d'aucun des trois. |
| D5 | Pairing ouvert à tous les utilisateurs (état actuel du code et du backend) | Garder ; corriger CLAUDE.md §2 et docs/pairing-flow.md. |
| D6 | Acceptation d'un VPN système tiers (choix du commit 7da1974) | Garder ; documenter dans docs/security-model.md ; état `SystemVpnActive` distinct dans l'UI. |
| D7 | Politique au démarrage à froid : verrouillé dès qu'un token de session existe | Oui (fail-closed). |

---

## Phase 0 — Filet de sécurité (avant tout correctif) — ~1 j

But : que chaque bug corrigé soit épinglé par un test rouge d'abord.

1. **CI minimale** `.github/workflows/android.yml` : job `unit` (`testDebugUnitTest` + `lintDebug`, secrets `LOCAL_PROPERTIES_B64` / `GOOGLE_SERVICES_B64`), `lint { abortOnError = true; baseline }` dans build.gradle.kts. Retirer la ligne `jacocoTestReport` de CLAUDE.md §6 ou ajouter le plugin. (plan-ui-tests-ci §2.1)
2. **Tests P0 rouges** : `AuthInterceptorTest` (2fa/verify sans token → passe), `EncryptedCookieJarTest` (Set-Cookie sur 2fa/verify persisté), `TokenAuthenticatorTest` (8 × 401 → 1 refresh ; priorResponse ; bearer périmé ; refresh 401 → logout), `CsrfInterceptorTest`, `BiometricLockOverlayTest` Robolectric (hôte ComponentActivity → l'overlay reste), `PortfolioRepositoryImplTest` (getPnl écrit Room), `MarketDataDtoDecodingTest` avec fixtures JSON copiées des schémas backend, `InstantParsingTest`.
3. **Garde de drift de contrats** (S, peut glisser en phase 4) : cible `make openapi-android` côté backend qui copie un OpenAPI réduit dans `app/src/test/resources/contracts/`, test JVM `DtoContractTest` par réflexion sur `@Json(name)` et sur les chemins Retrofit. Aurait attrapé #6, #7, #23. (plan-ui-tests-ci §2.3)

## Phase 1 — Sécurité et bloquants — ~4 j

Ordre imposé par les dépendances : stockage récupérable → verrou réel → gestionnaire de verrou.

| PR | Contenu | Plan détaillé | Effort |
|---|---|---|---|
| 1.1 | **EncryptedDataStore récupérable** (#17) : holder réinitialisable au lieu de `by lazy`, `resetCorruptedStore()` (supprime `trading_secure_prefs` + alias `_androidx_security_master_key_`, recrée une fois), `catch SecurityException`, `RecoverFromKeystoreCorruptionUseCase` branché sur le dialog → retour à l'écran Setup. Rien ne survit (clés WG incluses) : le dire dans le dialog. | plan-biometric-storage §C | M |
| 1.2 | **Verrou biométrique réel** (#1) : `MainActivity : FragmentActivity`, D1 (thème ou minSdk), fallback fail-closed (`onError`, jamais `onSuccess`), `LocalInspectionMode` pour les previews, `BiometricManager` gère `UserNotAuthenticatedException` / `KeyPermanentlyInvalidatedException` avant le prompt, `setUserAuthenticationParameters` (API 30+), `BackHandler` dans l'overlay. | plan-biometric-storage §A | M |
| 1.3 | **BiometricLockManager propriétaire unique** (#8 + LOW) : timestamp d'inactivité, poll et `isLocked` dans le singleton (observer ProcessLifecycleOwner) ; MainActivity ne fait que transmettre les touches ; `LAST_INTERACTION_AT` + `BIOMETRIC_LOCKED` en `commit()` ; restore dans la même coroutine que la lecture du token ; verrouillé par défaut au démarrage (D7). | plan-biometric-storage §B | M |
| 1.4 | **AuthPaths + 2FA** (#2 + cookie 2FA) : objet `AuthPaths` unique (PUBLIC, CSRF_EXEMPT, VPN_EXCLUDED, COOKIE_SAVE) utilisé par les 4 intercepteurs/cookie jar ; `TotpViewModel` ne consomme le temp token que sur succès ou 401 serveur ; message + `BackToLogin` sur 401 ; 429 → `AccountLockedException`. | plan-auth-network §A | S |
| 1.5 | **Onboarding** (#3) : `di/DispatcherModule` (`@IoDispatcher`), `MobileProvisioningRepositoryImpl.register` en `withContext(io)` ; test MockWebServer. | plan-data-widgets §C | S |
| 1.6 | **Room baseline v7** (#5, D2) : supprimer schémas 1-6 et `MIGRATION_*`, un seul test `baseline_v7_exportedSchemaMatchesEntities`, `MigrationTest` déplacé en JVM Robolectric si possible. À faire **avant** 2.2 (qui change une entité). | plan-data-widgets §A | S |

## Phase 2 — Fonctionnalités qui ne marchent jamais — ~3 j

| PR | Contenu | Plan détaillé | Effort |
|---|---|---|---|
| 2.1 | **Market data** (#6, #7, volume) : `SymbolListResponseDto` + recherche serveur + pagination dans `SymbolPickerSheet` ; sparklines via `GET /v1/market-data/?symbol&timeframe=1d&limit=30` (réponse DESC → `asReversed()`, vérifier l'absence de 307 sur le slash final) ; `volume` via `toBigDecimalOrNull()`. | plan-market-data §A/B/E | S+S+S |
| 2.2 | **Chemin PnL unique** (#4, #11, YEAR→ytd) : supprimer `getPnl`/`toDomain()` morts ; `getPnlSummary` écrit Room ; `PnlSnapshotEntity` reshapée sur `/pnl`, `period` en clé primaire ; `PnlWidget` lit `totalPnl/totalPnlPercent` ; le worker synchronise chaque période configurée ; `maxDrawdown / 100` dans le mapper ; docs/api-contracts.md + fixtures. Régénérer et commiter `7.json`. | plan-data-widgets §B | M |
| 2.3 | **Payloads WS** (#23 + `portfolio_update`) : `PositionUpdate` étendu (`last_price`, `is_active`, `side`, `quantity`), appariement par symbole limité aux positions OPEN ; `PortfolioUpdate` mappé sur `total_value/cash_balance/positions_value`. Backend additif `position_id` (D4). Test `WsRepositoryTest` avec JSON copié de `consumer.py`. | plan-market-data §C | S |
| 2.4 | **Dates tolérantes** (#9) : `domain/util/InstantParsing.kt` (`parseInstantLenient`, `parseInstantOrNull`), remplacer les 9 sites (Mappers ×5, InstantAdapter, AuthRepositoryImpl, PublicWsClient, ParseSetupQrUseCase), supprimer le helper privé d'`OrdersRepositoryImpl`. | plan-data-widgets §C | S |

## Phase 3 — Robustesse session, réseau, WebSocket — ~4 j

| PR | Contenu | Plan détaillé | Effort |
|---|---|---|---|
| 3.1 | **TokenAuthenticator** (#15, #16) : `priorResponse == 401 → null` ; comparaison bearer périmé → retry sans refresh ; `refreshOnce()` avec Deferred partagé hors du lock ; client `@Named("refresh")` (cookie jar, VPN, timeout, pinning, Dispatcher dédié, sans Authenticator/CSRF) ; `withTimeoutOrNull` n'annule que l'attente. | plan-auth-network §B | M |
| 3.2 | **WS privé** (#12 + latence login) : `SessionManager.sessionStartedEvents` ; `PrivateWsClient` collecte sessionStarted (reset backoff + connect) et forcedLogout (disconnect) ; garde `TokenHolder` vide ; compteur de génération pour fermer un socket rouvert pendant le logout. | plan-auth-network §C | M |
| 3.3 | **runCatchingCancellable** (#10) : helper `domain/util`, remplacement mécanique des 44 sites (sed), gardes explicites dans `ConfirmPairingUseCase`, `AuthRepositoryImpl.kt:91`, `TokenAuthenticator.doRefresh` ; test JVM qui interdit `runCatching {` nu dans `src/main` ; dedup `MarketDataRepositoryImpl.getQuote` détaché sur `applicationScope`. CLAUDE.md §2. **En dernier de la phase** (diff large). | plan-auth-network §D | M |
| 3.4 | **WS public** (#13, #14) : ref-count par symbole ; `connectionState` exposé via repository + `GetPublicWsConnectionStateUseCase` ; `QuoteFallbackController` partagé (polling REST seulement quand Disconnected et foreground, `collectLatest`) remplace les 3 copies et leurs `catch (Exception)`. 3 commits. | plan-market-data §D | L |
| 3.5 | **Logs debug** (LOW) : `Cookie`/`Set-Cookie` expurgés, HEADERS seulement sur `/v1/auth/*` et fcm-token ; **cold start** : `GetAuthContextUseCase` peuple `TokenHolder` ; KDoc `TokenHolder`/`AuthInterceptor`/`TradingApplication`. | plan-auth-network §E/F | S |

## Phase 4 — UI, widgets, arrière-plan — ~4 j (parallélisable avec la phase 3)

| PR | Contenu | Plan détaillé | Effort |
|---|---|---|---|
| 4.1 | **DataState<T> Dashboard** (#21) : valeur conservée pendant le refresh, skeletons seulement au premier chargement, NAV appliqué directement depuis le WS puis refetch debounced 750 ms. | plan-ui-tests-ci Batch 1 | M |
| 4.2 | **Pagination et jobs** (#22, Positions, Orders) : `loadJob` unique + `isLoadingMore` + bouton désactivé + `distinctBy(id)` ; Positions ignore un résultat périmé ; Orders : portfolioId vide → Error sans appel, historique paginé via `count`. | Batch 2 | M |
| 4.3 | **Flows et VPN** : Alerts `catch` + `retryWhen` à l'intérieur du `flatMapLatest` ; `VpnState.SystemVpnActive` (D6) ; `WireGuardManager` Mutex + arrêt de la notification sur DOWN + retrait du `BIND_VPN_SERVICE` inutile. | Batch 3 | M |
| 4.4 | **Widgets et cache** (#18, #20, §2) : `CacheTtl` central, `formatWidgetSyncTime(ttl)` avec date et badge « périmé », `CacheTimestamp` aligné 10 min ; `Cached<T>` + `forceRefresh` pour `getDeviceStatus` et `getPosition` ; `PnlWidgetConfigureActivity` déclarée + `onDeleted` ; `SystemStatusWidget` via `readBooleanSafe` ; Top 5 par valeur absolue ; `syncQuotes` = quoteDao ∪ watchlist ∪ tickers configurés, plafond 50, timeout 60 s. | plan-data-widgets §D | M |
| 4.5 | **FCM** (#19 + LOW) : `HttpStatusException.isRetryable` ; retry sur 5xx/429/408 ; compare-and-remove des clés PENDING ; insert Room en `NonCancellable`/scope applicatif ; id de notification stable. | plan-data-widgets §D | S |
| 4.6 | **Pairing + Setup + composants** : QR illisible n'efface pas le scan réussi, `_deviceInfo` mis à jour ; `MarkSetupCompletedUseCase` (SetupViewModel ne touche plus le DataStore) ; `mergeDescendants` sur les 7 composants ; `rememberHapticFeedback` réel ; `unbindAll()` caméra ; couleurs du `SourceQualityDot` via le thème ; `deviceWgIp` affiché. | Batches 4-5 | S+S |

## Phase 5 — Qualité et docs — ~2 j

- Job CI instrumenté (Gradle Managed Devices api30 + api34) : `MigrationTest` baseline, `BiometricLockOverlayTest` réel, `SetupSmokeTest` (attrape #3), `SealedBoxHelperInstrumentedTest`. API 30 épingle #9.
- Tests P1/P2 : `WidgetUpdateWorkerTest` avec repository réel, `PublicWsClientTest` (ref-count), `PrivateWsClientTest`, ViewModels Settings/Profile/MyDevices/Orders/TransactionHistory/Performance, `SealedBoxHelperTest` réel (LazySodium injecté).
- Docs en une passe : CLAUDE.md §2 (pairing ouvert, 15 min, règle de retry du worker, `runCatchingCancellable`, `pnl_snapshots`, fallback piloté par `connectionState`), §4 (emplacement du timer, verrouillé au démarrage, sémantique du reset, thème), §6 (jacoco), §12 (pré-fetch CSRF déjà implémenté) ; docs/security-model.md (VPN système) ; docs/api-contracts.md (symbols, history, unités performance et pnl) ; docs/pairing-flow.md §« accès admin » ; KDocs périmées (TokenHolder, PrivateWsClient, AppDatabase, WidgetUpdateWorker).

---

## Vue d'ensemble

| Phase | Contenu | Effort | Dépend de |
|---|---|---|---|
| 0 | CI + tests rouges + garde de contrats | ~1 j | — |
| 1 | Stockage, verrou biométrique, 2FA, onboarding, baseline Room | ~4 j | D1, D2, D7 |
| 2 | Market data, PnL, payloads WS, dates | ~3 j | 1.6 avant 2.2 |
| 3 | TokenAuthenticator, WS privé, cancellation, WS public | ~4 j | 1.4 (AuthPaths) |
| 4 | Dashboard, pagination, VPN/flows, widgets, FCM, composants | ~4 j | 2.2 pour 4.4 |
| 5 | CI instrumentée, tests P1/P2, docs | ~2 j | 1.2, 1.5, 1.6 pour le job émulateur |

Total ≈ 18 jours-personne, dont les phases 3 et 4 parallélisables. Chaque PR embarque ses tests et la mise à jour de CLAUDE.md correspondante. Aucun changement d'API publique côté backend n'est requis ; les trois ajouts D4 sont optionnels et additifs.

## Ce que je peux faire ensuite

- Exécuter la phase 0 puis la phase 1 PR par PR sur une branche dédiée, avec les tests rouges d'abord, en respectant la limite de 3 Go (build sans daemon, un worker).
- Ou commencer par les trois correctifs les plus petits et les plus rentables : 1.4 (2FA + cookie), 1.5 (onboarding), 2.1 (market data).
