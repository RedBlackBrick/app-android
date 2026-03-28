# Strategie offline et cache

Ce document decrit le comportement de l'application lorsque le VPN est inactif ou que l'API
VPS est injoignable, ainsi que les mecanismes de cache Room et OkHttp en place.

---

## 1. Comportement par ecran hors-ligne

| Ecran | VPN actif, API OK | VPN inactif ou API injoignable |
|-------|-------------------|-------------------------------|
| **DashboardScreen** | NAV/PnL via REST + WS prive ; cours via WS public (fallback REST 30s) | Cours : `QuoteUiState.Stale` (derniere valeur connue + indicateur visuel + `CacheTimestamp`). NAV/PnL : `NavUiState.Error` / `PnlUiState.Error` si aucune donnee en cache. WS prive degrade : indicateur discret `wsPrivateDegraded`, polling REST reste actif en fallback. |
| **PositionsScreen** | Positions via REST, mises a jour temps reel via WS prive (`position_update`) | `PositionsUiState.Error` si le fetch echoue. Le cache Room `positions` reste lisible via `PositionDao.getAll()` (utilise par `getPosition()` dans `PortfolioRepositoryImpl` comme fast-path). `CacheTimestamp` affiche l'horodatage `syncedAt`. |
| **MarketDataScreen** | WS public par symbole de la watchlist ; fallback REST 30s si WS echoue | `VpnNotConnectedException` / `IOException` / `SocketTimeoutException` : etat precedent conserve (pas d'erreur affichee). Les quotes en cache Room (`QuoteEntity`) restent accessibles via `QuoteDao.getBySymbol()`. Watchlist permanente en Room. |
| **AlertListScreen** | Lecture Room locale uniquement (pas d'API VPS) | **Toujours fonctionnel.** Les alertes proviennent de FCM et sont persistees dans Room (`AlertEntity`). `AlertRepositoryImpl.getAlerts()` retourne un `Flow<List<Alert>>` depuis `AlertDao.getAllFlow()`. Aucun appel reseau. Filtrage par type via query SQL `WHERE type IN (...)`. |
| **DeviceListScreen** | Devices via REST, cache Room | `DevicesUiState.Error` si le fetch echoue. Le cache Room `devices` (TTL 1 min) est consulte en fast-path par `DeviceRepositoryImpl.getDeviceStatus()`. `CacheTimestamp` affiche l'horodatage `syncedAt`. |
| **Widgets Glance** | `WidgetUpdateWorker` sync Room puis `updateAll()` | VPN absent : `Result.success()` sans mise a jour Room. Le cache date est conserve et affiche tel quel. Le timestamp `synced_at` est visible sur chaque widget. |

### Detail : QuoteUiState.Stale sur le Dashboard

Quand le VPN est coupe (`VpnNotConnectedException`) ou que le WS public echoue, le
`DashboardViewModel` transite le cours de `QuoteUiState.Success` vers `QuoteUiState.Stale`.
L'ecran affiche le dernier cours connu avec un avertissement textuel et un `CacheTimestamp`
base sur `quote.timestamp`. Le polling REST continue en arriere-plan (30s) et restaure
`QuoteUiState.Success` des que le VPN revient.

```kotlin
// Extrait de DashboardViewModel.fetchQuote()
is VpnNotConnectedException -> {
    _uiState.update { state ->
        val newQuote = when (val prev = state.quote) {
            is QuoteUiState.Success -> QuoteUiState.Stale(prev.data)
            is QuoteUiState.Stale -> prev
            else -> state.quote
        }
        state.copy(quote = newQuote)
    }
}
```

### Detail : MarketDataScreen fallback WS vers REST

`MarketDataViewModel` demarre un abonnement WS public par symbole de la watchlist
(`startWsSubscription`). Si la connexion WS echoue (`VpnNotConnectedException`,
`SocketTimeoutException`, `IOException`, ou toute exception), le ViewModel bascule sur un
polling REST a 30s (`startPollingFallback`). Les erreurs transitoires pendant le polling
conservent l'etat precedent sans afficher d'erreur.

---

## 2. Cache Room

### Tables et TTL

| Table Room | Cle primaire | TTL indicatif | Purge | Utilisee par |
|------------|-------------|---------------|-------|-------------|
| `positions` | `id: Int` | 5 min | `DELETE WHERE synced_at < now - 5 min` | PositionsScreen, PositionsWidget, PortfolioRepositoryImpl (fast-path) |
| `pnl_snapshots` | `id: Long` (auto) | 5 min | `DELETE WHERE synced_at < now - 5 min` | DashboardScreen, PnlWidget |
| `quotes` | `symbol: String` | 10 min | `DELETE WHERE synced_at < now - 10 min` | QuoteWidget, MarketDataScreen, MarketDataRepositoryImpl |
| `alerts` | `id: Long` (auto) | 30 jours OU 500 max | `DELETE WHERE received_at < now - 30j` puis `DELETE sauf les 500 plus recentes` | AlertListScreen, AlertsWidget |
| `devices` | `id: String` | 1 min | `DELETE WHERE synced_at < now - 1 min` | DeviceListScreen, DeviceDetailScreen, SystemStatusWidget |
| `watchlist` | `symbol: String` | Permanent | Aucune purge automatique | MarketDataScreen (symboles suivis par l'utilisateur) |

### Champ synced_at

Chaque entite Room porte un champ `synced_at: Long` (epoch millis) enregistre au moment de
l'upsert. Ce timestamp sert a :

- Determiner les entrees expirees lors de la purge
- Afficher l'age du cache a l'utilisateur via `CacheTimestamp`
- Calculer la fraicheur dans les widgets

Des index existent sur `synced_at` pour toutes les tables (migration v3 vers v4) afin
d'optimiser les requetes `DELETE ... WHERE synced_at < :cutoff`.

### Pattern upsert + purge atomique

La purge est **toujours executee apres la sync reussie**, jamais avant. Chaque DAO expose
une methode `@Transaction` qui combine l'upsert et la purge en un seul commit SQLite :

```kotlin
// PositionDao
@Transaction
suspend fun upsertAllAndPurge(positions: List<PositionEntity>, cutoffMillis: Long) {
    upsertAll(positions)          // OnConflictStrategy.REPLACE
    deleteOlderThan(cutoffMillis)
}
```

Ce pattern garantit :
- **Pas de gap** : si le Worker est tue pendant la sync, les anciennes donnees restent en place.
- **Atomicite** : aucune lecture concurrente ne voit un etat intermediaire (donnees inserees
  mais anciennes pas encore purgees).
- **WAL mode** (defaut Room) : les lectures UI via `getAllFlow()` ne sont pas bloquees par la
  transaction.

### Purge des alertes

Les alertes ont une politique de retention specifique (`AlertDao.purgeExpired()`) :

1. Suppression des alertes dont `received_at < now - 30 jours`
2. Conservation des 500 alertes les plus recentes uniquement

Les deux DELETE sont executes dans une seule `@Transaction`.

---

## 3. WidgetUpdateWorker — comportement hors-ligne

`WidgetUpdateWorker` (`@HiltWorker`, `CoroutineWorker`) est planifie periodiquement par
WorkManager (minimum 15 min, contrainte `NetworkType.CONNECTED`).

### Verification VPN

```kotlin
if (vpnManager.state.value !is VpnState.Connected) {
    return Result.success()  // VPN absent — garder le cache, ne pas retry
}
```

Si le VPN est inactif, le Worker retourne `Result.success()` immediatement **sans modifier
Room**. Le cache date reste affiche par les widgets avec son timestamp. `Result.failure()` et
`Result.retry()` ne sont jamais utilises pour absence de VPN (evite les retries WorkManager
en boucle).

### Blocs de sync independants

Chaque section (positions, PnL, quotes) est wrappee dans un `try/catch` independant. Un echec
sur les positions ne bloque pas la sync des quotes.

| Exception | Comportement |
|-----------|-------------|
| `IOException` | `anyRetryNeeded = true` — le Worker retourne `Result.retry()` avec backoff exponentiel |
| `VpnNotConnectedException` | Ignore — VPN coupe en cours de sync, cache conserve |
| `SQLException` | Log erreur, pas de retry (non-transitoire) |

### Purge des alertes dans le Worker

Les alertes ne sont pas synchronisees par le Worker (elles viennent de FCM uniquement). Le
Worker execute `AlertDao.purgeExpired()` apres les syncs reseau pour maintenir la table sous
les limites de retention.

### Rafraichissement des widgets

Apres la sync, le Worker appelle `updateAll()` sur chaque widget Glance :

- `PnlWidget`
- `PositionsWidget`
- `AlertsWidget`
- `SystemStatusWidget`
- `QuoteWidget`

Chaque widget relit ses donnees depuis Room dans son `provideGlance()`.

---

## 4. Composant CacheTimestamp

**Fichier :** `app/src/main/java/com/tradingplatform/app/ui/components/CacheTimestamp.kt`

Composable qui affiche l'horodatage de la derniere synchronisation :

| Condition | Affichage |
|-----------|-----------|
| `syncedAt == 0L` | Rien (composable ne rend rien) |
| Age < 1 minute | "A jour" |
| Age >= 1 minute | "Donnees du HH:mm" |

Style : `MaterialTheme.typography.labelSmall`, couleur `onSurfaceVariant`.

Utilise sur les ecrans suivants (verifie dans le code source) :

- `DashboardScreen` — sur le cours quand `QuoteUiState.Stale`
- `PositionsScreen` — en en-tete de la liste des positions
- `PositionDetailScreen`
- `DeviceListScreen`
- `DeviceDetailScreen`
- `EdgeDeviceDashboardScreen`
- `MyDevicesScreen`

---

## 5. VpnRequiredInterceptor

**Fichier :** `app/src/main/java/com/tradingplatform/app/data/api/interceptor/VpnRequiredInterceptor.kt`

Intercepteur OkHttp qui bloque toutes les requetes si `vpnManager.state.value` n'est pas
`VpnState.Connected`. Leve `VpnNotConnectedException` (extends `Exception`, pas `IOException`)
pour permettre un catch distinct dans les ViewModels et le WidgetUpdateWorker sans declencher
les retries automatiques.

### Chemins exclus

Les endpoints d'authentification ne necessitent pas le VPN :

- `/v1/auth/login`
- `/v1/auth/refresh`
- `/v1/auth/2fa/verify`
- `/csrf-token`

### Mode DEV_MODE

En `BuildConfig.DEV_MODE` (distinct de `DEBUG`), l'intercepteur est bypass pour permettre les
tests sur un backend local sans WireGuard. `DEV_MODE` est toujours `false` en release.

---

## 6. Deduplication des requetes quote

**Fichier :** `app/src/main/java/com/tradingplatform/app/data/repository/MarketDataRepositoryImpl.kt`

`MarketDataRepositoryImpl` utilise un `ConcurrentHashMap<String, CompletableDeferred<Result<Quote>>>`
pour dedupliquer les requetes quote en vol. Si plusieurs sources (Dashboard, PositionDetail,
Widget) demandent le meme symbole simultanement, une seule requete reseau est effectuee.

### Fonctionnement

1. Requete entrante pour le symbole `X` (normalise en uppercase)
2. Verification dans `inFlightQuotes[X]` :
   - Si present : `await()` sur le `CompletableDeferred` existant
   - Si absent : creation d'un nouveau `CompletableDeferred` via `putIfAbsent`
3. Le premier demandeur execute la requete dans un `supervisorScope` (la cancellation d'un
   appelant ne cancel pas le Deferred pour les autres)
4. Le resultat est `complete()` sur le Deferred, puis retire de la map immediatement

```kotlin
private val inFlightQuotes = ConcurrentHashMap<String, CompletableDeferred<Result<Quote>>>()

override suspend fun getQuote(symbol: String): Result<Quote> {
    val upperSymbol = symbol.uppercase()
    val existing = inFlightQuotes[upperSymbol]
    if (existing != null) return existing.await()

    val deferred = CompletableDeferred<Result<Quote>>()
    val winner = inFlightQuotes.putIfAbsent(upperSymbol, deferred)
    if (winner != null) return winner.await()

    val result = supervisorScope { runCatching { /* fetch API + upsert Room */ } }
    deferred.complete(result)
    inFlightQuotes.remove(upperSymbol)
    return result
}
```

Chaque quote fetchee est persistee dans Room via `QuoteDao.upsertAndPurge()` (TTL 10 min).

---

## 7. Cache HTTP OkHttp

**Configuration dans :** `app/src/main/java/com/tradingplatform/app/di/NetworkModule.kt`

Le `OkHttpClient` principal est configure avec un cache disque :

```kotlin
val cache = Cache(
    directory = File(context.cacheDir, "http_cache"),
    maxSize = 10L * 1024L * 1024L,  // 10 MB
)
```

Ce cache respecte les headers `Cache-Control` renvoyes par le backend VPS. L'application
ne definit pas de headers `Cache-Control` cote client — le comportement de cache HTTP depend
entierement des reponses du serveur.

Le cache OkHttp est complementaire au cache Room :
- **OkHttp** : cache HTTP transparent au niveau reseau, utile pour les reponses identiques a
  courte duree (deduplication reseau, reponses conditionnelles 304).
- **Room** : cache applicatif persistant avec TTL explicite, source de donnees pour les widgets,
  l'affichage offline et le composant `CacheTimestamp`.

---

## 8. Chaine d'intercepteurs et impact offline

L'ordre des intercepteurs dans le `OkHttpClient` principal est :

```
TimeoutInterceptor         → timeouts par endpoint (FAST/MEDIUM/SLOW/STANDARD)
UpgradeRequiredInterceptor → detecte 426 Upgrade Required
CsrfInterceptor            → injecte X-CSRF-Token sur POST/PUT/DELETE/PATCH
VpnRequiredInterceptor     → bloque si VPN inactif → VpnNotConnectedException
AuthInterceptor            → injecte Authorization: Bearer
TokenAuthenticator         → refresh transparent sur 401
HttpLoggingInterceptor     → logs debug (tokens redactes)
```

Le `VpnRequiredInterceptor` est le point de coupure principal : quand le VPN est inactif,
toutes les requetes (sauf authentification) levent `VpnNotConnectedException` avant d'atteindre
le reseau. Les ViewModels traitent cette exception pour basculer sur le cache ou afficher un
etat degrade.

---

## 9. Resume du flux de donnees hors-ligne

```
VPN actif :
  Screen → ViewModel → UseCase → Repository → API (Retrofit/OkHttp) → Room (upsert+purge)
                                                                    ↘ OkHttp cache (10 MB)
  Widget → WidgetUpdateWorker → UseCase → Repository → API → Room → provideGlance()

VPN inactif :
  Screen → ViewModel → UseCase → Repository → VpnRequiredInterceptor → VpnNotConnectedException
                                      ↘ Room (fast-path si cache present, ex: getPosition())
  ViewModel → gere l'exception :
    - Dashboard : QuoteUiState.Stale + polling continue
    - MarketData : etat precedent conserve
    - Positions/Devices : Error si pas de cache fast-path

  Widget → WidgetUpdateWorker → VPN check → Result.success() (cache Room inchange)
```
