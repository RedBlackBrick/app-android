# Audit app-android — Rapport

Date : 2026-09-28 · Branche : `feature/redesign-mobile-onboarding` @ 171902c · Périmètre : tout le dépôt (≈27 k lignes Kotlin, 303 fichiers main, 38 classes de tests JVM, 1 androidTest).
Dépôts consultés en lecture seule pour les contrats : `trading-platform2` (backend FastAPI, Caddy, SQL), `radxa-ramboot` (serveur de pairing LAN).

Méthode : 4 chefs d'audit Sonnet (≈38 revues module × dimension), 1 revue de drift de contrats app ↔ backend ↔ Radxa, 14 vérifications adversariales Opus en contexte frais sur tous les candidats de sévérité ≥ medium, plus vérifications directes par l'orchestrateur (grep/lecture). La délégation Gemini a échoué (pas de shell en mode headless) et a été remplacée par un agent Sonnet. Aucun fichier source n'a été modifié. Gate : `./gradlew testDebugUnitTest` → **PASS** (exécuté plafonné à 1,4 Go de heap, sans daemon).

Contexte important : **aucune release n'a jamais été livrée** (`versionCode = 1`, aucun tag, seul un APK debug existe). Les sévérités ci-dessous sont celles qu'auraient les défauts sur un appareil réel avec un build release ; plusieurs sont invisibles en debug ou en tests JVM.

Détail des preuves et verdicts : `audit/verified.md` (registre), `audit/candidates-*.md` (candidats bruts par chef d'audit), `audit/NOTES.md` (journal).

---

## 1. Findings vérifiés, classés

Légende : **V** = vérifié par un vérificateur Opus indépendant ; **S** = vérifié directement par l'orchestrateur (lecture/grep) ; les deux quand applicable.

### CRITIQUE

**#1 — Le verrou biométrique est inopérant : l'overlay se déverrouille seul sans authentification.** (V+S)
- `app/src/main/java/com/tradingplatform/app/MainActivity.kt:28` — `class MainActivity : ComponentActivity()`
- `ui/components/BiometricLockOverlay.kt:187-191` — `val activity = context as? FragmentActivity ?: run { onSuccess(); return }`
- Aucune classe de l'app n'étend `FragmentActivity` ni `AppCompatActivity`. Le cast échoue donc toujours et le fallback « preview/test » appelle `onSuccess()` depuis le `LaunchedEffect` dès que l'overlay apparaît. `BiometricManager.authenticate()` (qui exige une `FragmentActivity` pour `BiometricPrompt`) n'est jamais atteint.
- Scénario : inactivité 5 min → overlay affiché → déverrouillage immédiat → positions et P&L visibles par quiconque tient l'appareil. Toute la section « Verrou biométrique » de CLAUDE.md §4 est sans effet.
- Correctif : `MainActivity : FragmentActivity()` ; faire échouer fermé le fallback (`onError`, pas `onSuccess`) ; ajouter un test instrumenté qui vérifie que l'overlay reste affiché sans authentification.

### HIGH

**#2 — Connexion 2FA (TOTP) impossible : `AuthInterceptor` bloque `/v1/auth/2fa/verify`.** (V)
- `data/api/interceptor/AuthInterceptor.kt:42-47` — `PUBLIC_PATHS` = login, register, refresh, csrf-token ; correspondance exacte ligne 54.
- `data/repository/AuthRepositoryImpl.kt:65-68` lève `TotpRequiredException` avant `tokenHolder.setToken`, donc aucun access token n'existe au moment du `verify2fa`. L'intercepteur fabrique un 401 synthétique et appelle `sessionManager.notifyForcedLogout()` (:64-86) ; la requête ne quitte jamais l'appareil. `AuthRepositoryImpl.verify2fa` traduit le 401 en `InvalidTotpCodeException` (« Code incorrect ») pendant que l'utilisateur est renvoyé au login ; le `session_token` a déjà été consommé (`TotpViewModel.kt:59`), pas de retry possible.
- Côté backend le chemin est public et l'alias existe (`auth/router.py:477`, `core/middleware/auth.py:67-68`) : le défaut est purement côté app. Aucun test ne couvre l'intercepteur sur ce chemin.
- Correctif : ajouter `/v1/auth/2fa/verify` (et `/v1/auth/verify-2fa`, `/csrf-token`) à `PUBLIC_PATHS` ; test `AuthInterceptorTest` « token nul + chemin 2FA → proceed sans forced logout ».

**#3 — Onboarding mobile bloqué : appel réseau bloquant sur le thread principal.** (V)
- `data/repository/MobileProvisioningRepositoryImpl.kt:68` — `client.newCall(request).execute()` dans une `suspend fun` sans `withContext(Dispatchers.IO)`.
- Chaîne : `SetupViewModel.kt:79,93` (`viewModelScope.launch`, Main) → `ProvisionMobileVpnUseCase.kt:41-62` (aucun changement de dispatcher) → `register()`. Android lève `NetworkOnMainThreadException`, avalée par `runCatching` → « Échec du provisioning » à chaque tentative. Invisible en tests JVM. `PairingRepositoryImpl` est sain (Retrofit `suspend`).
- Correctif : `withContext(Dispatchers.IO) { ... }` ou injection d'un dispatcher IO ; test instrumenté du flux de setup.

**#4 — Le `PnlWidget` ne reçoit jamais de données : le seul writer de `pnl_snapshots` n'a aucun appelant.** (S, remonté par un vérificateur)
- `domain/usecase/portfolio/GetPnlUseCase.kt:15` appelle `repository.getPnlSummary()` (`PortfolioRepositoryImpl.kt:93-100`), qui n'écrit pas Room.
- `PortfolioRepositoryImpl.getPnl()` (:75-91), seul appel à `pnlDao.upsertAndPurge`, n'a **aucun** call site dans `app/src/main`.
- `widget/PnlWidget.kt:60` lit `pnlDao.getLatestByPeriod` → toujours vide. La KDoc de `WidgetUpdateWorker.kt:228` (« l'upsert est fait dans le Repository ») est fausse, et `WidgetUpdateWorkerTest` mocke le use case, donc ne le détecte pas.
- Correctif : faire écrire Room par le chemin appelé par le worker (ou appeler `getPnl`), et ajouter un test qui vérifie l'écriture `pnlDao`.

**#5 — Migrations Room 3→4 et 4→5 incomplètes : crash-loop garanti sur toute mise à jour en release.** (V)
- `data/local/db/AppDatabase.kt:74-83` — `MIGRATION_3_4` ne contient que 6 `CREATE INDEX` alors que `pnl_snapshots` perd 8 colonnes et en gagne 10 entre `schemas/.../3.json` et `4.json`.
- `AppDatabase.kt:108-117` — `MIGRATION_4_5` ne crée que `watchlist` alors que 8 colonnes passent de `NOT NULL` à nullable (positions ×4, devices ×2, quotes ×2), ce que SQLite ne permet pas sans recréer la table. De plus `5.json` a été modifié en place (commit c5decdc) sans bump de version : l'`identityHash` a changé.
- `di/DatabaseModule.kt:40-44` : `fallbackToDestructiveMigration` uniquement en DEBUG, donc jamais visible en dev. `androidTest/.../MigrationTest.kt:69,96` doit échouer sur ces deux étapes → ces tests n'ont jamais été exécutés. `MIGRATION_5_6` et `6_7` sont complètes.
- Impact réel aujourd'hui : latent (rien n'a été livré). Impact dès la première release : toute mise à jour depuis v3/v4 lève `IllegalStateException` au démarrage, perte de l'historique d'alertes (CLAUDE.md : « ne jamais »).
- Correctif : soit recréer les tables (caches TTL, aucune copie nécessaire) dans 3→4 et 4→5, soit, puisque rien n'a été livré, repartir d'une baseline v7 (supprimer les schémas 1-6 et les migrations). Exécuter `MigrationTest` en CI sur émulateur. Supprimer `1.json` et le commentaire « voir MIGRATION_1_2 » (AppDatabase.kt:24) : la v1 n'a jamais existé.

**#6 — Le sélecteur de symboles ne fonctionne jamais : `GET /v1/market-data/symbols` renvoie un objet, l'app attend un tableau.** (V)
- `data/api/MarketDataApi.kt:14-15` — `Response<List<String>>`.
- Backend `app/market_data/router.py:232-235` — `response_model=SymbolListResponse` = `{symbols:[{ticker,...}], total, limit, offset, has_more}` ; aucun alias de compatibilité (contrairement à `/csrf-token`). Moshi : « Expected BEGIN_ARRAY but was BEGIN_OBJECT » → `Result.failure` systématique. `docs/api-contracts.md:263-270` documente l'ancienne forme.
- Correctif : DTO `SymbolListResponseDto`, mapper `symbols.map { it.ticker }`, pagination.

**#7 — Les sparklines ne se rendent jamais : `GET /v1/market-data/{symbol}/history` répond 422.** (V)
- `MarketDataApi.kt:17-22` envoie `interval`/`limit` ; backend `router.py:195-205` exige `start` et `end` (`Query(...)`), nomme le pas `timeframe`, et renvoie `MarketDataResponse` (objet `{symbol, data, count, ...}`), pas un tableau.
- Correctif : envoyer `start`/`end` ISO + `timeframe=1d`, décoder `MarketDataResponseDto`, prendre `.data.takeLast(30)`.

**#8 — Le minuteur d'inactivité ne se réarme jamais après le premier verrouillage.** (V)
- `MainActivity.kt:122-127` — `onBiometricUnlocked()` est le seul code qui remet `isBiometricLocked = false` et n'a aucun appelant. Le vrai chemin (`AppNavGraph.kt:807` → `AppNavViewModel.onBiometricUnlocked()` :248) n'appelle que `biometricLockManager.unlock()`.
- Conséquence : `dispatchTouchEvent` (:94-98) cesse de mettre à jour `lastInteractionAt` et la boucle (:105-111) fait `continue` indéfiniment jusqu'à recréation de l'Activity. Sans effet visible tant que #1 rend le verrou inopérant ; devient réel dès que #1 est corrigé.
- Correctif : supprimer le flag local et piloter le minuteur par `biometricLockManager.isLocked` (collecteur dans `lifecycleScope`).

**#9 — Historique des transactions : `Instant.parse` sur un offset `+00:00` échoue probablement sur Android ≤ 13.** (V, confiance moyenne : non reproduit sur appareil)
- `data/model/Mappers.kt:131` — `executedAt = Instant.parse(executedAt)`. Backend `portfolio/schemas.py:1976` type `executed_at: str` construit via `.isoformat()` → `2026-05-04T10:00:00.123456+00:00`. Les autres timestamps sont des `datetime` sérialisés en `...Z` et passent.
- `Instant.parse` n'accepte les offsets autres que `Z` qu'à partir de JDK 12 (JDK-8166138) ; le `java.time` d'Android ≤ 13 est antérieur et le desugaring ne remplace pas la plateforme (minSdk 26). Une seule ligne fait échouer toute la liste.
- À trancher : `Instant.parse("2026-05-04T10:00:00+00:00")` sur un émulateur API 26-33. Correctif dans tous les cas : `OffsetDateTime.parse(s).toInstant()` via un helper tolérant (comme `OrdersRepositoryImpl.kt:57-63` le fait déjà), ou `executed_at: datetime` côté backend.

### MEDIUM

**#10 — `runCatching` avale `CancellationException` dans toute la couche data (systémique).** (S)
- ≈45 occurrences dans `data/repository/*`, 6 dans `domain/usecase/*` (`ConfirmPairingUseCase.kt:38` via `catch (e: Exception)`). Seul `PrivateWsClient.kt:235` relance correctement. Une coroutine annulée est rapportée comme `Result.failure` (états d'erreur parasites, jobs de fallback lancés pour des symboles retirés, cf. #14). Le modèle de CLAUDE.md §2 ne montre pas non plus la garde : corriger le doc en même temps.
- Correctif : helper `suspend inline fun <T> runCatchingCancellable(block)` qui relance `CancellationException`, utilisé partout.

**#11 — `max_drawdown` affiché ×100 (« 830,00 % »).** (V)
- `Mappers.kt:74-85` passe les champs de `/performance` tels quels ; le backend renvoie `total_return_pct`, `volatility`, `cagr`, `win_rate` en fractions (corrects après `×100` dans l'UI) mais `max_drawdown` en pourcentage (`calculators/performance.py:137` fait déjà `*100`). `PerformanceScreen.kt:150` remultiplie. `docs/api-contracts.md:304-310` documente des unités fausses ; les fixtures de tests utilisent un drawdown négatif fractionnaire, ce qui a masqué le bug.
- Correctif : diviser `maxDrawdown` par 100 dans le mapper (domaine tout en fractions) ; corriger doc et fixtures.

**#12 — Le WebSocket privé n'est jamais déconnecté au logout.** (V)
- `PrivateWsClient.disconnect()` (:195) n'a aucun appelant ; `AuthRepositoryImpl.logout` (:87-107) et `LogoutUseCase` (:16-24) ne le touchent pas. Le socket reste authentifié avec l'ancien JWT WS, un nouveau login ne peut pas le remplacer (garde `isConnected` :183), et la boucle de reconnexion continue sans token (pas d'arrêt sur 401).
- Correctif : `disconnect()` dans le logout et le forced-logout ; ne pas replanifier si `TokenHolder` est vide.

**#13 — Abonnements WS publics sans comptage de référence : retirer un symbole de la watchlist gèle la cotation du Dashboard.** (V)
- `PublicWsClient.kt:79,148-156` — `activeSymbols` est un set partagé ; `PublicWsRepositoryImpl.kt:44-45` appelle `unsubscribe` en `onCompletion`. Le Dashboard s'abonne au symbole par défaut (= premier de la watchlist), les deux ViewModels vivent en même temps (onglets avec `saveState`). La cotation reste affichée en `Success` figée, sans signal.
- Correctif : `ConcurrentHashMap<String, AtomicInteger>` par symbole, `unsubscribe` réseau uniquement à 0.

**#14 — Le fallback REST des cotations est du code mort : `Flow<Quote>` ne lève jamais.** (V)
- `PublicWsClient.kt:280-287` absorbe toute erreur en `Disconnected` ; `PublicWsRepositoryImpl.kt:39-45` ne garde que `MarketData`. Les `catch (VpnNotConnectedException|IOException)` de `MarketDataViewModel.kt:188-208` et `DashboardViewModel.kt:340-365` ne s'exécutent jamais pour une vraie panne : écran Marchés sans prix si le WS tombe et REST marche. Bonus : le `catch (e: Exception)` attrape l'annulation et lance un polling pour le symbole qu'on vient de retirer.
- Correctif : exposer un `connectionState: StateFlow` via repository/use case et démarrer/arrêter le polling dessus ; relancer `CancellationException`.

**#15 — `TokenAuthenticator` : le dédoublonnage du refresh est inatteignable.** (V)
- `TokenAuthenticator.kt:79-97` — le `Mutex` est tenu pendant `await()` et `refreshDeferred` remis à `null` dans `finally` avant relâchement : chaque thread en attente voit `null` et relance son propre `POST /refresh`. Pas de comparaison entre le token de la requête échouée et `tokenHolder.accessToken` ; pas de garde `priorResponse` (jusqu'à 20 refresh par requête restant en 401). Combiné à #16 : ≈8 s de blocage et une salve de 401 à la reprise de l'app.
- Correctif : dans le lock, comparer d'abord le Bearer de la requête au token courant (retry sans refresh s'il diffère) ; `if (response.priorResponse?.code == 401) return null`.

**#16 — Le refresh passe par le client principal (même host, `maxRequestsPerHost = 5`, même `Authenticator`).** (V, low-medium)
- `NetworkModule.kt:142,217-223,246-247`. Avec ≥ 5 appels parallèles en 401 (Dashboard en lance 4 + quote + WS token), le refresh attend un slot jusqu'au timeout 8 s. Se répare seul mais dégrade la reprise.
- Correctif : `AuthApi` dédié au refresh sur un client sans `Authenticator` avec son propre `Dispatcher`.

**#17 — `EncryptedDataStore` : une corruption Keystore brique l'installation sans voie de récupération.** (V)
- `EncryptedDataStore.kt:51-70` — `by lazy` met `null` en cache pour toute la vie du singleton ; `clearAll`/`clearSession` (:263-274) deviennent des no-op ; rien ne supprime `trading_secure_prefs` ni l'alias `_androidx_security_master_key_` (`KeystoreManager.regenerateKey` vise un autre alias). Le dialog de corruption (`AppNavGraph.kt:319-330`) ne fait que basculer un `StateFlow` ; le login « réussit » mais rien n'est persisté, et l'état revient à chaque démarrage. Note : security-crypto alpha lève `SecurityException` (RuntimeException) à la déchiffrement, non attrapée par `readString`.
- Correctif : sur échec, supprimer le fichier de prefs et l'alias, recréer une fois ; rendre le holder réinitialisable ; brancher le bouton du dialog dessus.

**#18 — Horodatage des widgets sans date au-delà de 60 min.** (V)
- `widget/WidgetTheme.kt:32-39` — `else -> "HH:mm"`. Utilisé par Pnl/Positions/Quote/SystemStatus ; les DAO n'ont pas de filtre TTL et la purge n'a lieu qu'après une sync réussie : des données de 3 jours s'affichent « Sync 14:32 ». `AlertsWidget` a son propre formateur correct.
- Correctif : `dd/MM HH:mm` au-delà du jour courant + badge « périmé » au-delà du TTL.

**#19 — Enregistrement du token FCM : un 5xx/429 est traité comme échec permanent.** (V)
- `NotificationRepositoryImpl.kt:16-24` (`error()` → `IllegalStateException`) + `FcmTokenRegistrationWorker.kt:58-66` (`Result.retry()` seulement sur IO/VPN). Rattrapé au prochain démarrage à froid seulement.
- Correctif : exception typée avec le code HTTP ; retry si `>= 500 || 429 || 408`.

**#20 — Détail device : le cache Room est servi sans TTL, le pull-to-refresh est un no-op affiché comme frais.** (V)
- `DeviceRepositoryImpl.kt:39-42` (`DEVICE_TTL_MS` déclaré :20 mais inutilisé ici) ; `DevicesViewModel.kt:147-165` horodate `now()`. Écran admin de santé (CPU/RAM/temp) potentiellement périmé.
- Correctif : n'utiliser le cache que si `now - synced_at < TTL`, paramètre `forceRefresh`, remonter le vrai `synced_at`.

**#21 — Dashboard : chaque `portfolio_update` WS remplace tout l'écran par des squelettes.** (V)
- `DashboardViewModel.kt:310-314,453-467` met `Loading` inconditionnellement sur NAV et PnL ; `DashboardScreen.kt:69-74,125-129` bascule en `isInitialLoading`. Un échec REST transitoire remplace de bonnes données par `Error`.
- Correctif : `Loading` seulement si l'état n'est pas `Success` ; flag `isRefreshing` séparé pour les refetch WS.

**#22 — Historique des transactions : double-tap « Charger plus » → page dupliquée → crash `LazyColumn` (clé dupliquée).** (V)
- `TransactionHistoryViewModel.kt:52-63` sans garde ni état de chargement pour les pages > 1 ; `TransactionHistoryScreen.kt:127,130-133` bouton toujours actif et `key = { it.id }`.
- Correctif : `loadJob` unique, `isLoadingMore` exposé, bouton désactivé.

**#23 — `position_update` : l'app lit `current_price`, le backend envoie `last_price`.** (S, remonté par un vérificateur)
- `data/repository/WsRepository.kt:47` vs `trading-platform2/app/portfolio/consumer.py:2184`. Le prix live des positions n'est jamais mis à jour par WS (seul `unrealized_pnl` l'est). Le champ `position_id` lu en :44 n'existe pas non plus dans le payload : l'appariement se fait toujours par symbole, ce qui écrase aussi les lignes fermées du même symbole sous les filtres CLOSED/ALL (`PositionsViewModel.kt:93-97`).
- Correctif : mapper `last_price` (fallback `current_price`) ; n'apparier par symbole que les positions `OPEN`.

### LOW (vérifiés)

- `BiometricLockManager` : `BIOMETRIC_LOCKED` écrit avec `apply()` (fenêtre de perte de quelques ms) ; par ailleurs rien n'est persisté si le process meurt avant que le minuteur ne tire → démarrage à froid déverrouillé. `EncryptedDataStore.kt:73-82,251-254`, `BiometricLockManager.kt:39-62`.
- Recréation d'Activity (rotation) : `MainActivity.kt:79,103` remet le compteur d'inactivité à zéro (pas de `configChanges`). Ne fait que reporter le verrou.
- `restorePersistedState()` non attendu (`TradingApplication.kt:67-72`) : bref flash possible du contenu avant l'overlay ; `FLAG_SECURE` posé.
- Overlay biométrique sans `BackHandler` : Back navigue derrière l'overlay opaque.
- Race `connect()`/`disconnect()` dans `WireGuardManager.kt:89-136` : un disconnect pendant `setState(UP)` est perdu silencieusement (tunnel UP, état Connected, notification disparue) ; bouton « Déconnecter » actif pendant Connecting ; `backend == null` non atomique.
- Révocation VPN par l'OS : le tunnel est tenu par `GoBackend$VpnService` (AAR) qui remonte bien `DOWN` ; mais rien n'arrête la notification « VPN connecté » de `WireGuardVpnService` sur `DOWN` (`WireGuardManager.kt:163`). Le `BIND_VPN_SERVICE` + intent-filter sur ce service de notification sont superflus.
- `SystemVpnMonitor` accepte tout `TRANSPORT_VPN` : choix délibéré (commit 7da1974), atténué par base URL RFC-1918 + pinning + payload LAN scellé ; `docs/security-model.md:44-45,128` est périmé. Pourrait être resserré sur les réseaux VPN ayant une route vers `10.42.0.0/24`.
- Logging debug `Level.BODY` : mot de passe, tokens et cookies (`Cookie`/`Set-Cookie` non expurgés) en logcat, debug uniquement (`NetworkModule.kt:119-128`). Contraire à CLAUDE.md §1 « même en debug ».
- `PortfolioRepositoryImpl.getPosition()` (:37-53) ne recherche que `status=open` à cache manquant : une position fermée ouverte après purge (5 min) affiche « not found ».
- `CircuitBreakerState.fromWire` (:30-33) : inconnu → `CLOSED` (vert). Le backend n'émet que `open`/`closed` aujourd'hui ; robustesse.
- `BigDecimalAdapter` : `BigDecimal(value)` non gardé ; le backend envoie bien des chaînes ; fail-fast défendable.
- FCM : suppression inconditionnelle de `PENDING_FCM_*` après une inscription en vol (rotation de token concurrente perdue) ; insertion Room lancée dans `serviceScope` annulé par `onDestroy` (perte rare d'alerte) ; id de notification = `currentTimeMillis().toInt()` (collision).
- `WidgetUpdateWorker` : la règle « retry seulement si les 3 sections échouent » est délibérée (commit 44a5927, tests) mais contredit la KDoc :40 et CLAUDE.md §2 ; `ioFailures` redondant.
- `AlertsViewModel.kt:50-64` : après une exception amont, la chaîne se termine et ne se réabonne jamais (déclencheur rare : erreur SQLite).
- `PositionsViewModel.selectFilter/refresh` : chargements non annulés, écrasement possible dans le désordre.
- Volume WS public : `"123456.0".toLongOrNull()` → 0, label « Vol: » masqué (`PublicWsClient.kt:357`).
- `TokenHolder.kt:10-12` documente un fallback DataStore qui n'existe pas dans `AuthInterceptor` ; `GetAuthContextUseCase` lit le token sans peupler `TokenHolder`. Fenêtre de course au démarrage à froid très étroite (UI gatée), effet limité à un renvoi au login sans destruction de session.

---

## 2. Findings issus des revues, non vérifiés individuellement

Ces points viennent d'une revue module × dimension (Sonnet) sans passage en vérification adversariale. Ils sont plausibles et cités avec fichier:ligne, à confirmer avant correction.

Medium :
- `PnlWidgetConfigureActivity` n'est déclarée ni dans le manifest ni dans `pnl_widget_info.xml` (`android:configure`) : 233 lignes mortes, période jamais configurable.
- `SystemStatusWidget.kt:58` : `readBoolean(IS_ADMIN) ?: false` confond datastore corrompu et non-admin (les autres widgets affichent « Session expirée »).
- `PositionsWidget.kt:56` : « Top 5 » = 5 premiers par ordre alphabétique (`ORDER BY symbol ASC`).
- `WidgetUpdateWorker.kt:261-268` : `syncQuotes` ne rafraîchit que les symboles déjà dans `quoteDao`, pas la watchlist ni le ticker configuré d'un `QuoteWidget` ; pas de plafond ni de timeout global sur le fan-out.
- `ProfileViewModel.kt:57` : une rétrogradation admin lue via `/auth/me` ne réapplique pas `ApplyAdminWidgetVisibilityUseCase`.
- `EncryptedDataStore.kt:273-287` : le fallback de `clearSession` sur échec d'énumération fait `clear()` complet (efface les clés WireGuard).
- `EncryptedDataStore.kt:73-82` : `IS_ADMIN`/`PORTFOLIO_ID` en `apply()` alors que `ACCESS_TOKEN` est en `commit()` → état de login partiellement persisté possible.
- `VpnSettingsViewModel.kt:42-53` : un VPN tiers affiche « Tunnel WireGuard actif ».
- `DashboardViewModel.kt:329-365`, `MarketDataViewModel.kt:183-209` : après un échec WS, polling REST définitif, jamais de réabonnement.
- `DashboardViewModel.kt:220-230` : NAV/PnL sans refetch périodique (init, manuel, WS seulement).
- `OrdersViewModel.kt:50-58,86-97` : `init` appelle l'API avec un `portfolioId` possiblement vide ; historique plafonné à 50 sans pagination.
- `PairingViewModel.kt:103-146,162-174` : une erreur de lecture QR après un premier scan jette le scan réussi ; la branche `BothScanned` ne met pas à jour `_deviceInfo`.
- `CacheTimestamp.kt:35-52` : seuils 1/5 min contre les 10 min documentés.
- `QrScannerView.kt:133-213` : `onDispose` sans `cameraProvider.unbindAll()`.
- `HapticFeedback.kt:52-55` : `rememberHapticFeedback` n'utilise pas `remember`.
- `SetupViewModel.kt:61,110` : écrit `EncryptedDataStore` directement (couche Domain contournée), sans test.
- `SendDeviceCommandUseCase.kt:12-17` : `commandType: String` libre, pas d'allow-list.
- Accessibilité : `semantics { contentDescription }` sans `mergeDescendants = true` dans PnlText/AnimatedPnlText/MoneyText/StatusBadge/VpnStatusBanner/ErrorBanner/ConnectionStatusIndicator → le texte brut reste focusable par TalkBack.
- `CertificatePinner.kt:19-25` : `DEV_MODE` → pinning désactivé (debug seulement, gardé par Gradle en release) ; CLAUDE.md §1 demande un certificat de test à la place.

Low : `deviceWgIp` jamais affiché (CLAUDE.md §8) ; `clearDeepLink()` seulement pour « alerts » ; `CrashlyticsTree` sans filet d'expurgation ; snackbar keyée sur le message ; `MarketDataScreen.kt:449-452` couleurs `Color(0xFF...)` en dur (§5) ; `NumberFormat` alloué à chaque recomposition dans PnlText/MoneyText ; `NetworkUtils.isLocalNetwork` regex maison au lieu de `Patterns.IP_ADDRESS` ; `RECEIVE_BOOT_COMPLETED` sans receiver propre (WorkManager l'utilise) ; `purgeExpiredAlerts` sérialisée après le réseau ; une transaction Room par symbole dans `syncQuotes` ; titre/corps FCM stockés sans troncature ; `SecuritySettingsViewModel` doc périmée ; `LanTrustManager` trust-all (documenté, payload scellé, `/status` non authentifié).

---

## 3. Dérives de documentation (à corriger avec le code)

| Document | Écart |
|---|---|
| CLAUDE.md §2 (table admin) et `docs/pairing-flow.md:495-499` | Le pairing depuis « Mes appareils » est ouvert à tous les utilisateurs par choix (commit 0e55eed) et le backend l'applique par propriétaire ; les docs disent « admin uniquement ». |
| CLAUDE.md §2 (widgets 5 min, retry « ≥ 1 bloc ») | Code : 15 min (plancher OS) et retry « 3 blocs sur 3 » (délibéré). |
| CLAUDE.md §2 (modèle `runCatching`) | Le modèle n'a pas la garde `CancellationException`, d'où #10. |
| CLAUDE.md §8/§11, `network_security_config.xml` | Le pairing LAN est en HTTPS + `LanTrustManager`, pas en HTTP ; le fichier XML est plus strict que décrit. |
| `docs/security-model.md:44-45,128` | Ne mentionne pas l'acceptation d'un VPN système quelconque. |
| `docs/api-contracts.md:263-270, 304-310` | Forme de `/symbols` et unités de `/performance` fausses. |
| KDocs périmées | `TokenHolder.kt:10-12`, `PrivateWsClient.kt:47-48`, `AppDatabase.kt:24`, `WidgetUpdateWorker.kt:40,228`, `MyDevicesScreen`/CLAUDE.md incohérents. |

---

## 4. Couverture de tests — lacunes

Gate : 38 classes de tests JVM, **toutes vertes**. Mais :
- **Aucun test** de `TokenAuthenticator`, `CsrfInterceptor`, `EncryptedDataStore` (instance réelle), `KeystoreManager`, `CertificatePinner`, `LanTrustManager`, `TimeoutInterceptor`, `UpgradeRequiredInterceptor`, ni de l'ordre de la chaîne d'intercepteurs.
- `SealedBoxHelperTest` n'instancie jamais `SealedBoxHelper` (teste un `require` recopié).
- Aucun `*RepositoryImplTest`, aucun test des clients WebSocket au-delà de `WsBackoffTest`.
- ViewModels sans test : Settings, Profile, MyDevices, Orders, TransactionHistory, Performance.
- `androidTest/MigrationTest.kt` existe mais doit échouer (voir #5) : jamais exécuté. Pas de test instrumenté du setup ni du verrou biométrique (#1 et #3 seraient détectés par un test sur appareil).
- Les fixtures de tests encodent des unités fausses (drawdown négatif fractionnaire), ce qui a masqué #11.

Recommandation : ajouter un job CI émulateur (API 26 ou 30 + API 34) exécutant `connectedAndroidTest` ; un `AuthInterceptorTest` sur les chemins publics ; un test de `WidgetUpdateWorker` avec un vrai `PortfolioRepositoryImpl` + Room en mémoire.

---

## 5. Ce qui est sain (vérifié)

- Certificate pinning : deux pins obligatoires hors `DEV_MODE` ; `DEV_MODE` figé à `false` en release avec garde Gradle qui refuse `assembleRelease` sinon.
- Backend joignable uniquement via le tunnel (Caddy publié sur `10.42.0.1:443` ; le listener public n'expose que l'enregistrement pairing/provisioning). Le chemin `/csrf-token` racine est un alias backend prévu pour Android.
- `EncryptedCookieJar` : correspondance exacte chemin + nom de cookie ; `clearSession` préserve bien les clés WG sur le chemin nominal.
- `KeyPermanentlyInvalidatedException` gérée avec régénération ; `SealedBoxHelper` utilise bien `crypto_box_seal` ; `session_pin`/`local_token`/`nonce` expurgés des logs ; le client LAN n'a pas de logging et embarque `VpnRequiredInterceptor` + garde RFC-1918 + HTTPS only.
- Contrats API majoritairement alignés : auth (login/refresh/logout/me/2FA/ws-token), portfolios, quote, orders, risk, strategies, edge devices/commands/vpn-peers, broker-connections, fcm-token, noms d'événements WS privés et publics, protocole de pairing LAN (HTTPS 8099, payload scellé, vocabulaire de statut), mobile-provisioning.
- `WidgetUpdateWorker` : vérification VPN en entrée, `Result.success()` sans VPN, purge après sync, `KEEP` + `NETWORK_CONNECTED`, sections indépendantes (les use cases renvoient `Result`, rien ne traverse `awaitAll`).
- Pas de secret dans `SharedPreferences` (les activités de configuration de widgets n'y mettent que période/ticker) ; pas de token FCM loggé ; `PendingIntent` immuable ; `PrivateWsClient` observe le cycle de vie process et relance correctement `CancellationException`.
- Backoff WS conforme au test ; `WsBackoff` correct ; migrations 5→6 et 6→7 complètes.

---

## 6. Couverture de l'audit

| Module | Revu par | Dimensions | Vérifications Opus | Non revu / limites |
|---|---|---|---|---|
| data/api + interceptors, data/session, security/* (hors Biometric*), datastore | Lead A (5 revues) | concurrence, correction, sécurité, intégrité, erreurs, couverture tests | 2FA/CSRF, TokenAuthenticator/cold-start, DataStore/VPN/logging | — |
| data/repository, data/model, data/local/db + schémas, data/websocket, domain/* | Lead B (9 revues) | correction, intégrité, concurrence, erreurs, sécurité | migrations, perf ×100, main-thread + WS privé, WS public + getPosition, DTO/CB | pas d'exécution de `MigrationTest` (émulateur) |
| MainActivity, TradingApplication, ui/navigation, ui/screens (18 VM), ui/components, vpn/*, security/Biometric* | Lead C (11 revues) | correction, concurrence, sécurité, erreurs, perf, accessibilité | biométrie, admin/pairing/devices, concurrence VM, WireGuard | style/thème hors règles §5 volontairement exclu ; pairing UseCases lus par grep seulement (couverts par Lead B) |
| widget/*, fcm/*, di/WidgetModule | Lead D (7 revues) | correction, erreurs, perf, sécurité, intégrité | worker, FCM | — |
| Contrats app ↔ backend ↔ Radxa | Reviewer contrats (Sonnet, remplace Gemini) | correction | 4 drifts vérifiés | modules backend non appelés par l'app non explorés |
| ui/theme, build.gradle.kts, proguard, manifest | Leads A/C (lecture directe) | correction | — | thème : pas de revue dédiée (hors périmètre défauts) |
| Tests instrumentés (`connectedAndroidTest`), lint | — | — | — | **non exécutés** (pas d'émulateur ; lint non lancé pour rester sous 3 Go) |

---

## 7. Plan de correction suggéré (ordre)

1. **Sécurité immédiate** : #1 (FragmentActivity + fallback fermé), #8 (minuteur), puis les low biométriques (persistance, rotation, BackHandler).
2. **Bloquants fonctionnels** : #2 (2FA), #3 (onboarding), #6/#7 (symboles, sparklines), #4 (PnlWidget), #23 (`last_price`).
3. **Avant la première release** : #5 (baseline Room v7 ou migrations complètes + `MigrationTest` en CI), #17 (récupération DataStore), #9 (parse tolérant des dates).
4. **Robustesse** : #10 (helper `runCatchingCancellable` + CLAUDE.md), #12-#16, #18-#22.
5. **Docs** : §3 en une passe.

## 8. Follow-ups hors périmètre

- Backend : `TransactionItem.executed_at` typé `str` au lieu de `datetime` (source de #9) ; `PositionResponse.quantity/average_price` `Optional` alors que la colonne est `NOT NULL` ; `PortfolioCircuitBreakerStatus.state` en `str` libre plutôt qu'enum.
- Backend : pas d'alias de compatibilité pour `/v1/market-data/symbols` et `/history` comme il en existe pour `/csrf-token` ; à décider côté API ou côté app.

---

## 9. État de la remédiation (2026-09-28, branche `audit/remediation`)

Toutes les phases du plan ont été exécutées par sous-agents (Opus/Sonnet) avec un gate de tests unitaires plafonné en mémoire après chaque vague, et un commit par vague. Aucun commit n'a été poussé.

| Finding | PR | État |
|---|---|---|
| #1 verrou biométrique inopérant | 1.2 | corrigé (`FragmentActivity`, overlay fail-closed, `minSdk 28`) |
| #2 2FA bloqué | 1.4 | corrigé (`AuthPaths`) + cookie refresh après 2FA |
| #3 onboarding thread principal | 1.5 | corrigé (`@IoDispatcher`) |
| #4 PnlWidget sans données | 2.2 | corrigé (chemin `/pnl` unique, une ligne par période) |
| #5 migrations Room | 1.6 | baseline v7, migrations supprimées, test baseline |
| #6 / #7 symboles, sparklines | 2.1 | corrigés (DTO paginés, `/history` avec `start`/`end`) |
| #8 minuteur d'inactivité | 1.3 | corrigé (`BiometricLockManager` propriétaire unique) |
| #9 dates `+00:00` | 2.4 | corrigé (`parseInstantLenient` / `parseInstantOrNull`, dead cleartext config retiré) |
| #10 `runCatching` systémique | 3.3 | corrigé (44 sites) + garde `NoBareRunCatchingTest` |
| #11 drawdown ×100 | 2.2 | corrigé |
| #12 WS privé jamais fermé | 3.2 | corrigé (événements de session, garde token, génération) |
| #13 / #14 WS public | 3.4 | corrigés (ref-count, `connectionState`, `QuoteFallbackController`) |
| #15 / #16 TokenAuthenticator | 3.1 | corrigés (client de refresh dédié) |
| #17 DataStore corrompu | 1.1 | corrigé (`resetCorruptedStore`, retour au Setup) |
| #18 / #20 / widgets | 4.4 | corrigés (`CacheTtl`, `Cached<T>`, badge périmé) |
| #19 FCM | 4.5 | corrigé (`HttpStatusException.isRetryable`) |
| #21 Dashboard squelettes | 4.1 | corrigé (`DataState<T>`) |
| #22 pagination | 4.2 | corrigé |
| #23 `last_price` + 6 drifts trouvés par la garde | 2.3, 2.5 | corrigés |
| Alerts flow, `SystemVpnActive`, mutex WireGuard | 4.3 | corrigés |
| Pairing/Setup/composants/accessibilité | 4.6 | corrigés |
| Escape hatch, dialog de corruption | 1.7 | corrigés |
| CI unit + lint, CI instrumentée (GMD api30/api34), tests P0/P1/P2, garde de drift de contrats | 0, 5b, 5c, 5d | en place |
| Documentation | 5a | alignée |

Gates : 597 tests unitaires verts (6 ignorés : variante JVM du sealed box, couverte en instrumenté) ; `lintDebug` 0 erreur / 88 avertissements. Les tests instrumentés n'ont pas été exécutés localement (pas d'émulateur) : le job CI `instrumented` les lancera.

## 10. Suivis restants après la remédiation

- `isLoggedIn` ne repasse jamais à `true` après un login dans l'app (bannière VPN et deep-link FCM le lisent) ; à traiter en gérant le changement de `startDestination` du `NavHost`.
- Aucun appel à `VpnService.prepare()` (consentement VPN) dans l'app.
- `WidgetUpdateWorker` ne synchronise pas quand seul un VPN système est actif.
- Backend (optionnel, additif) : `position_id` dans `position_update`, suppression du temp token 2FA seulement sur succès, `executed_at` typé `datetime`, cible `make openapi-android`.
- Lint : 88 avertissements à trier (versions de dépendances, icônes launcher, `ModifierParameter`) ; `TrustAllX509TrustManager` est documenté (LAN scellé).
- `SealedBoxHelperRealTest` réactivable quand la CI passera en JDK 21 (lazysodium-android est du bytecode Java 21).
- Vérification sur appareil recommandée : prompt biométrique API 28/29, attente `RESUMED` de l'escape hatch, dark mode des composants retouchés.
