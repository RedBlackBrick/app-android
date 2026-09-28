# Plan partiel — persistance, widgets, arrière-plan (#3, #4, #5, #9, #11, #18, #19, #20, widgets §2)
Source : agent planificateur, 2026-09-28. $B = app/src/main/java/com/tradingplatform/app/. **[new]** = fait découvert pendant l'exploration, non vérifié par l'orchestrateur.

Ordre : A baseline Room (S) → B chemin PnL unique + #11 (M) → C DispatcherModule (S) + parseInstantLenient (S) → D #19 (S), #20 (M), #18 (S), widgets §2 (4×S) → docs.

## A. #5 Room — option recommandée : reset de la baseline en gardant `version = 7` — S
Justification : jamais livré ; écrire 2 migrations de recréation + corriger 5.json pour des utilisateurs inexistants est un coût pur. Garder 7 (pas 1) évite de régénérer le JSON (build) et un downgrade sur les devices de dev ; 7.json correspond déjà aux entités.
Procédure : (1) `git rm app/schemas/.../{1..6}.json` ; (2) AppDatabase.kt : supprimer MIGRATION_2_3…6_7 (:58-117) + imports, remplacer l'historique (:20-56) par « v7 = baseline release 1.0 ; premier changement post-release → MIGRATION_7_8 + 8.json + étape MigrationTest » ; (3) DatabaseModule.kt : retirer les imports et `.addMigrations(...)` (:38), garder le fallback DEBUG ; après B, rebuild une fois pour régénérer 7.json et le commiter ; (4) MigrationTest.kt : garder un seul test `baseline_v7_exportedSchemaMatchesEntities` (createDatabase(7) + runMigrationsAndValidate(7)) ; (5) CI : de préférence déplacer MigrationTest en JVM Robolectric (`sourceSets["test"].assets.srcDirs("$projectDir/schemas")`, pattern WidgetUpdateWorkerTest) — à valider au premier run ; sinon émulateur (android-emulator-runner ou Gradle Managed Device api34).

## B. #4 + #11 — un seul chemin PnL : `/pnl` → Room → Dashboard + PnlWidget — M
Faits : PnlWidget.kt:60 lit pnlDao.getLatestByPeriod (période day/week/month configurable) ; DashboardScreen.kt:478-552 affiche totalReturn/totalReturnPct/winRate/sharpe/tradesCount/winningTrades ; getPnlSummary → /pnl (total_pnl_percent en pourcent, mapper /100 déjà) ; getPnl (mort) → /performance (périodes 7d/30d…, fractions sauf max_drawdown). pnl_snapshots a la forme /performance depuis v4 — mauvaise pour les deux consommateurs. **[new]** `PnlPeriod.YEAR.toApiString()="year"` mais le backend accepte `ytd` → 422 ; corriger en "ytd".
Design : supprimer PortfolioRepository.getPnl + Impl (:75-91) + PerformanceResponseDto.toDomain(): PnlSummary (Mappers.kt:87-98) ; /performance reste derrière getPerformance() → PerformanceMetrics. getPnlSummary devient l'unique writer (Dashboard et Worker passent tous deux par GetPnlUseCase) :
```kotlin
val dto = response.body() ?: error("Empty PnL response"); val now = System.currentTimeMillis()
pnlDao.upsertAndPurge(dto.toEntity(period, syncedAt = now), cutoffMillis = now - CacheTtl.PNL_MS); dto.toPnlSummary()
```
PnlSnapshotEntity reshapée sur le payload /pnl, **une ligne par période** (`period` = PK → REPLACE upsert réel ; aujourd'hui l'id autoGenerate accumule) : period, realized_pnl, unrealized_pnl, total_pnl (TEXT), total_pnl_percent (fraction), trades_count, winning_trades, losing_trades, synced_at. PnlDao.getByPeriod + observeByPeriod (optionnel). PnlWidget.kt:43-72 lit totalPnl/totalPnlPercent. WidgetUpdateWorker.syncPnl : synchroniser chaque période configurée par une instance de widget (`PnlWidget.configuredPeriods(ctx)` via prefs `period_*`), pas seulement DAY.
#11 : Mappers.kt:74-85 `maxDrawdown = maxDrawdown?.div(100.0)` (domaine = fractions partout ; PerformanceScreen inchangé). docs/api-contracts.md:304-310 + bloc /pnl (:163+) ; fixtures WidgetUpdateWorkerTest.kt:79, GetPnlUseCaseTest.kt:26, DashboardViewModelTest.kt:77 (maxDrawdown=-0.05 → 0.05).
Tests : PortfolioRepositoryImplTest (mockk PortfolioApi+PnlDao : succès → upsertAndPurge(period=="day", totalPnlPercent==0.0175) ; 500 → pas d'écriture) ; MappersTest (8.3 → 0.083) ; PnlDaoTest Robolectric (2 upserts même période → 1 ligne) ; WidgetUpdateWorkerTest (prefs day+week → 2 appels).

## C. #3 dispatcher + #9 parse des instants — S + S
#3 : pas de DispatcherModule aujourd'hui. Créer di/DispatcherModule.kt (`@IoDispatcher` qualifier, `Dispatchers.IO`) ; MobileProvisioningRepositoryImpl reçoit `@IoDispatcher io` et fait `withContext(io) { runCatching { ... } }`. Ne pas migrer les autres classes maintenant. Test MobileProvisioningRepositoryImplTest (MockWebServer, builder OkHttp injectable). Vérifier `grep -rn "MobileProvisioningRepositoryImpl(" app/src` (construction manuelle dans PairingFlowIntegrationTest ?).
#9 : sites Instant.parse : Mappers.kt:71,131,142,155,193-194 ; InstantAdapter.kt:9 ; AuthRepositoryImpl.kt:218 (expiresAt) ; OrdersRepositoryImpl.kt:57-63 (helper privé à supprimer) ; PublicWsClient.kt:366 ; **[new]** ParseSetupQrUseCase.kt:93 (expires_at du QR, même risque). Helper dans domain/util/InstantParsing.kt (java.time pur, importable par data et domain) :
```kotlin
fun parseInstantLenient(raw: String): Instant = raw.trim().let { s ->
    runCatching { Instant.parse(s) }.recoverCatching { OffsetDateTime.parse(s).toInstant() }
        .recoverCatching { LocalDateTime.parse(s).toInstant(ZoneOffset.UTC) }
        .getOrElse { throw DateTimeParseException("Unparseable instant: $s", s, 0, it) } }
fun String?.parseInstantOrNull(): Instant? = this?.let { runCatching { parseInstantLenient(it) }.getOrNull() }
```
Test InstantParsingTest : Z, +00:00, +02:00, .123456+00:00, naïf, garbage.

## D. #18 / #19 / #20 + widgets §2
#18 WidgetTheme.kt:32-40 : `formatWidgetSyncTime(syncedAt, ttlMs, now)` → SyncLabel(text, isStale) : « maintenant » / « il y a Nmin » / HH:mm si même jour / dd/MM HH:mm sinon ; préfixe « périmé · » au-delà du TTL. Nouvel objet data/local/db/CacheTtl (POSITIONS 5 min, PNL 5 min, QUOTES 10 min, DEVICES 1 min) utilisé par les repositories, les 4 widgets et ui/components/CacheTimestamp.kt (aligner 1/5 min → 10 min documentés). Test WidgetThemeTest. S.
#19 domain/exception/HttpStatusException(code, endpoint) avec `isRetryable = code >= 500 || 429 || 408` ; NotificationRepositoryImpl.kt:23-25 la lève ; FcmTokenRegistrationWorker.kt:58-66 : `is HttpStatusException -> if (isRetryable) retry else failure`. Suivi systémique optionnel : `Response<T>.bodyOrThrow(endpoint)` pour les ≈45 `error("… HTTP …")`. Test Robolectric 503→retry, 400→failure. S.
#20 domain/model/Cached<T>(value, syncedAt) ; DeviceRepositoryImpl.getDeviceStatus(deviceId, forceRefresh) : cache seulement si `now - synced_at < CacheTtl.DEVICES_MS` et !forceRefresh ; renvoie le vrai syncedAt ; GetDeviceStatusUseCase + DevicesViewModel.loadDevice(forceRefresh) / refresh = force. Même motif pour PortfolioRepositoryImpl.getPosition:37-38 → PositionDetailViewModel. Tests DeviceRepositoryImplTest + fixtures VM. M (signatures touchées).
Widgets §2 (4×S) : manifest `<activity .widget.PnlWidgetConfigureActivity>` + `android:configure` dans pnl_widget_info.xml ; **[new]** ajouter clearConfiguredPeriod + PnlWidgetReceiver.onDeleted (QuoteWidget en a un :96) ; SystemStatusWidget.kt:58 → `readBooleanSafe()` (Corrupted → « Session expirée », NotFound/false → « Réservé aux administrateurs ») ; PositionsWidget.kt:56 tri Kotlin par |quantity × (currentPrice ?: avgPrice)| desc ; WidgetUpdateWorker.syncQuotes : union quoteDao + WatchlistDao.getAllSymbols() (nouvelle query) + QuoteWidget.configuredSymbols(ctx), plafond 50, `withTimeout(60_000)` sur le fan-out.

## Points de régression
- B change les colonnes de PnlSnapshotEntity → régénérer + commiter 7.json (A.3).
- #20 change deux signatures de repository → use cases, ViewModels, tests existants.
- CLAUDE.md §2 à mettre à jour dans les mêmes PR (politique de migration = baseline v7 ; pnl_snapshots = forme /pnl, une ligne par période ; badge périmé dans les widgets).
