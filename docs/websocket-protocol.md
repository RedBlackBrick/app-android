# Protocole WebSocket

Documentation du protocole WebSocket tel qu'implemente dans l'app Android.
Deux canaux distincts : **prive** (`/v1/ws/private`) et **public** (`/ws/public`).

---

## 1. Canal prive — `wss://{vps}/v1/ws/private`

### 1.1 Authentification

Le canal prive requiert un JWT specifique au WebSocket, distinct de l'access token REST.

1. `PrivateWsClient.openWebSocket()` appelle `AuthRepository.getWsToken()` qui effectue `POST /v1/auth/ws-token`
2. La reponse contient un `WsTokenInfo(token: String, expiresAt: Instant)` — le JWT porte le claim `type=websocket`
3. Apres l'ouverture du socket OkHttp (`onOpen`), le client envoie immediatement le premier message :
   ```json
   {"token": "<jwt_websocket>"}
   ```
4. Le serveur confirme l'authentification ou ferme la connexion

Le handshake HTTP Upgrade passe par le client OkHttp principal (avec `CertificatePinner` et `VpnRequiredInterceptor`), mais l'authentification elle-meme se fait via le message JSON, pas via un header HTTP.

### 1.2 Refresh proactif du token

Le token WS a un TTL limite. Pour eviter une deconnexion a l'expiration, `PrivateWsClient` planifie un refresh proactif a 80% du TTL (meme strategie que le frontend SvelteKit) :

| Constante | Valeur |
|-----------|--------|
| `TOKEN_REFRESH_RATIO` | 0.80 |
| `TOKEN_REFRESH_MIN_MS` | 10 000 ms |

Mecanisme :
1. `scheduleTokenRefresh(expiresAt)` calcule `delayMs = max(ttlMs * 0.80, 10_000)` et lance un job dans `appScope`
2. A l'echeance, `refreshWsToken()` obtient un nouveau JWT via `AuthRepository.getWsToken()`
3. Le nouveau token est envoye sur la connexion existante (sans reconnexion) :
   ```json
   {"action": "refresh_token", "token": "<new_jwt>"}
   ```
4. Le serveur repond `{"type": "token_refreshed"}` — log debug uniquement, pas d'emission dans le flow
5. En cas d'echec du refresh : pas de retry. Le serveur fermera la connexion a l'expiration du token (code 4001) et le mecanisme de reconnexion standard prendra le relais

**Race condition foreground/background (R2 fix)** : quand l'app revient en foreground (`onStart`), si la connexion est encore ouverte mais que `currentTokenExpiresAt` est passe, un refresh proactif est declenche immediatement au lieu d'attendre la fermeture serveur (evite le backoff exponentiel de 5-300s sans donnees).

### 1.3 Types d'evenements entrants

Tous les messages serveur ont la forme `{"type": "...", "data": {...}, "timestamp": "..."}`.

| `type` serveur | Classe `WsEvent` | Description |
|----------------|------------------|-------------|
| `portfolio_update` | `WsEvent.PortfolioUpdate(data: JSONObject)` | Trade execute + totaux portfolio resultants (voir §3.1 pour les cles exactes) |
| `position_update` | `WsEvent.PositionUpdate(data: JSONObject)` | Position individuelle (prix, P&L non realise/realise, is_active) |
| `order_update` | `WsEvent.OrderUpdate(data: JSONObject)` | Statut d'ordre, fill |
| `notification` | `WsEvent.Notification(notifType, title, body, data)` | Alerte utilisateur |
| `strategy_signal` | `WsEvent.StrategySignal(data: JSONObject)` | Signal de strategie (informatif) |
| `catalyst_event` | `WsEvent.CatalystEvent(data: JSONObject)` | Evenement catalyst (earnings, spinoff) |
| `ping` | *(pas emis dans le flow)* | Heartbeat applicatif — reponse `{"type": "pong"}` |
| `token_refreshed` | *(pas emis dans le flow)* | Acquittement refresh token — log debug |
| `error` | *(pas emis dans le flow)* | Erreur serveur — log warning avec `code` et `message` |
| *(inconnu)* | *(ignore silencieusement)* | Log verbose en debug uniquement |

Evenements de connexion emis dans le flow :
- `WsEvent.Connected` — emis dans `onOpen` apres envoi du token d'authentification
- `WsEvent.Disconnected(reason: String?)` — emis dans `onClosed` et `onFailure`

### 1.4 Ping/Pong applicatif

A la reception d'un message `{"type": "ping"}`, le client repond immediatement :
```json
{"type": "pong"}
```
Ce mecanisme est distinct du ping/pong au niveau protocole WebSocket (gere par OkHttp).

### 1.5 Reconnexion avec backoff exponentiel

| Constante | Valeur |
|-----------|--------|
| `BACKOFF_INITIAL_MS` | 5 000 ms |
| `BACKOFF_MAX_MS` | 300 000 ms (5 min) |
| `BACKOFF_MULTIPLIER` | 2.0 |

Progression : 5s, 10s, 20s, 40s, 80s, 160s, 300s, 300s, ...

Conditions de reconnexion :
- La reconnexion ne se declenche que si l'app est en **foreground** (`isAppForeground == true`)
- Declenchee sur `onClosed` (sauf code 1000 = fermeture normale via `disconnect()`) et sur `onFailure`
- Le compteur `reconnectAttempts` est remis a zero a chaque connexion reussie (`onOpen`) ou apres un refresh token reussi

Gestion du lifecycle (via `DefaultLifecycleObserver` + `ProcessLifecycleOwner`) :
- `onStart` (foreground) : reconnecte si deconnecte, ou refresh le token si expire pendant le background
- `onStop` (background) : annule les timers de reconnexion et de refresh token. La connexion existante reste ouverte — fermee par le serveur (idle timeout)

### 1.6 Thread-safety

- `_events` est un `MutableSharedFlow(replay=0, extraBufferCapacity=64)` — emission via `tryEmit()` (non-suspending), appelable depuis n'importe quel thread OkHttp
- `isConnected`, `isConnecting` sont des `AtomicBoolean`
- `reconnectAttempts` est un `AtomicInteger`
- `webSocket`, `reconnectJob`, `tokenRefreshJob`, `isAppForeground`, `currentTokenExpiresAt` sont `@Volatile`

---

## 2. Canal public — `wss://{vps}/ws/public`

### 2.1 Protocole de souscription

Le canal public est **non authentifie**. Apres l'ouverture de la connexion, le client envoie un message subscribe :

```json
{"action": "subscribe", "symbols": ["AAPL", "TSLA"]}
```

Desinscription :
```json
{"action": "unsubscribe", "symbols": ["AAPL"]}
```

Comportements :
- `subscribe(symbol)` ajoute le symbole au set `activeSymbols` (`CopyOnWriteArraySet<String>`) et envoie immediatement si connecte, sinon declenche la connexion
- `unsubscribe(symbol)` retire le symbole du set et envoie le message. Si `activeSymbols` est vide apres le retrait, la connexion est fermee
- Apres une reconnexion (`onOpen`), toutes les souscriptions de `activeSymbols` sont renvoyees automatiquement
- `disconnect()` n'efface pas `activeSymbols` — une reconnexion future retablira les souscriptions
- Les symboles sont normalises en uppercase

### 2.2 Types d'evenements entrants

| `type` serveur | Classe `PublicWsEvent` | Description |
|----------------|------------------------|-------------|
| `market_data` | `PublicWsEvent.MarketData(symbol, price, open, high, low, close, volume, bid?, ask?, timestamp)` | Cours temps reel OHLCV + bid/ask |
| `subscription_ack` | `PublicWsEvent.SubscriptionAck(action, symbols)` | Acquittement subscribe/unsubscribe — log debug uniquement |
| `ping` | *(pas emis dans le flow)* | Heartbeat — reponse `{"type": "pong"}` |
| `error` | *(pas emis dans le flow)* | Erreur serveur — log warning |
| *(inconnu)* | *(ignore)* | Log verbose en debug |

Evenements de connexion :
- `PublicWsEvent.Connected` — emis dans `onOpen`
- `PublicWsEvent.Disconnected(reason: String?)` — emis dans `onClosed` et `onFailure`

### 2.3 Parsing `market_data`

Champs parses depuis le JSONObject `data` (source : `MarketDataBridge` / Redis Stream `clean-market-data`) :

| Champ | Type | Nullable | Remarque |
|-------|------|----------|----------|
| `symbol` | String | non | Uppercase. Si absent, le message est ignore |
| `price` | BigDecimal | non | Defaut `"0"` |
| `open` | BigDecimal | non | Defaut `"0"` |
| `high` | BigDecimal | non | Defaut `"0"` |
| `low` | BigDecimal | non | Defaut `"0"` |
| `close` | BigDecimal | non | Defaut `"0"` |
| `volume` | Long | non | Defaut `0` |
| `bid` | BigDecimal | oui | Nullable cote serveur (champ optionnel du Redis Stream) |
| `ask` | BigDecimal | oui | Nullable cote serveur |
| `source` | String (-> `sourceName`) | oui | **PR-2.5 fix (PR-5c finding)** : seule cle source envoyee par `MarketDataBridge._forward` — texte libre (ex: `"yahoo"`), mappee dans `Quote.sourceName`. Il n'y a pas de `source_type`/`quality` sur ce canal (existent uniquement sur le canal admin separe `admin_events.py`) — `PublicWsEvent.MarketData.sourceType`/`quality` restent donc toujours `null` |
| `data_mode` | String (-> `dataMode`) | oui | Utilise par `SourceQualityDot` (MarketDataScreen) pour la couleur du point (`"realtime"`/`"polling"`/`"eod"`) |
| `timestamp` | Instant | non | Lu depuis le champ racine de l'enveloppe (`json.optString("timestamp")`), pas depuis `data`. Fallback `Instant.now()` si absent |

Si le parsing echoue (ex: valeur non numerique), le message est ignore avec un log warning.

### 2.4 Throttling (`Flow.sample`)

`PublicWsRepositoryImpl.quoteUpdates(symbol)` applique `Flow.sample(250ms)` sur le flux filtre par symbole. Cela garantit qu'au maximum 4 updates par seconde par symbole atteignent le ViewModel, meme si le serveur emet plus frequemment.

### 2.5 Gestion du cycle de vie des souscriptions

Le cycle de vie est gere par les operateurs Flow dans `PublicWsRepositoryImpl` :
- `onStart { wsClient.subscribe(symbol) }` — souscription WS declenchee quand le Flow est collecte
- `onCompletion { wsClient.unsubscribe(symbol) }` — desinscription quand le Flow est annule (scope parent annule)

Le `GetQuoteStreamUseCase` propage ce flow tel quel. Le ViewModel collecte dans `viewModelScope` — quand le ViewModel est detruit, la souscription est automatiquement annulee.

### 2.6 Reconnexion

Meme backoff exponentiel que le canal prive (5s, 10s, 20s, ..., 300s max). Deux conditions supplementaires :
- La reconnexion ne se fait que si `activeSymbols` est non vide (pas de connexion sans souscription)
- L'app doit etre en foreground

Le canal public n'expose pas de `WsConnectionState` vers l'UI — l'indicateur de connexion concerne uniquement le canal prive.

---

## 3. Chaine de mapping — du message brut a l'UI

### 3.1 Canal prive : WsEvent -> WsUpdate -> ActivityItem / UiState

```
                      PrivateWsClient                    WsRepository (data)
Message JSON -----> WsEvent (sealed class) ------> WsUpdate (domain sealed class)
  {"type":            - PortfolioUpdate(JSONObject)      - PortfolioUpdate(portfolioId?, symbol?, side?, quantity?,
   "...",             - PositionUpdate(JSONObject)          price?, totalValue?, cashBalance?, positionsValue?,
   "data":            - OrderUpdate(JSONObject)             nav?, dailyPnl?, totalPnl?)
   {...}}             - Notification(notifType,           - PositionUpdate(positionId?, symbol?, side?, quantity?,
                        title, body, JSONObject)             averagePrice?, lastPrice?, unrealizedPnl?, realizedPnl?,
                      - StrategySignal(JSONObject)            isActive)
                      - CatalystEvent(JSONObject)         - OrderUpdate(orderId?, symbol?, side?, status?, quantity?, fillPrice?)
                      - Connected                         - Notification(notifType, title, body)
                      - Disconnected(reason?)             - StrategySignal(signalId?, strategyId?, symbol?, action?,
                                                             confidence?, strategyType?)
                                                          - CatalystEvent(symbol?, catalystType?, strategyId?, description?)
```

La couche `WsRepository` (data) extrait les champs du `JSONObject` brut avec des extensions null-safe (`optDoubleOrNull`, `optIntOrNull`). Les champs sont **tous nullable** (sauf `Notification.notifType/title/body` et `PositionUpdate.isActive`, qui defaut a `true` quand la cle est absente) pour tolerer les payloads partiels du serveur.

**Champs reels envoyes par le backend** (verifies contre `trading-platform2`, cles exactes ; voir le guard `DtoContractTest` §4 et `app/src/test/resources/contracts/ws_events.json`) :

| Evenement | Payload backend (cles exactes) | Notes |
|-----------|--------------------------------|-------|
| `position_update` (`app/portfolio/consumer.py` `ws_position_payload`, ~L2164-2199) | `portfolio_id`, `symbol`, `side`, `quantity`, `average_price`, `last_price` (nullable), `unrealized_pnl` (nullable), `realized_pnl` (nullable), `is_active` | **Pas de `position_id`** — le mapping garde `WsUpdate.PositionUpdate.positionId` nullable pour un futur ajout backend. `last_price` est mappe avec fallback sur `current_price` (ancien nom potentiel). `is_active == false` signifie que ce fill a cloture completement la position |
| `portfolio_update` (`app/portfolio/consumer.py` `ws_payload`, ~L2151-2160) | `portfolio_id`, `symbol`, `side`, `quantity`, `price`, `total_value`, `cash_balance`, `positions_value` | **Pas de `nav`, `daily_pnl` ni `total_pnl`** — le payload decrit le trade qui vient de s'executer, pas un snapshot NAV/P&L. `WsUpdate.PortfolioUpdate.nav` est renseigne depuis `total_value` pour compatibilite arriere des consommateurs existants ; `dailyPnl`/`totalPnl` restent `null` |
| `order_update` (`app/execution/consumer.py` `_send_order_ws_update`, `app/execution/exit_consumer.py`) | `order_id`, `symbol`, `side`, `status`, `broker_order_id`, `quantity`, `price`, `order_type`, `portfolio_id`, `strategy_id`, `strategy_type`, `reason`, `exit_order`, `rule_type`, `trigger_price`, `position_id` | **PR-2.5 fix (PR-5c finding)** : `WsUpdate.OrderUpdate.fillPrice` est lu depuis `price` — aucun call site n'envoie jamais `fill_price` (l'ancien nom lu, toujours `null`) |
| `notification` (`app/notification/service.py` `send_and_route`/`broadcast`, `app/notification/consumer.py`) | `title`, `body`, `notification_type`, `priority`, `data`, `id` | **PR-2.5 fix (PR-5c finding)** : `WsEvent.Notification.notifType` est lu depuis `notification_type` — la cle `type` n'existe pas sur ce canal (ancien fallback, toujours `"info"`). Le fallback `message` pour `body` a ete supprime (mort — jamais envoye) |
| `catalyst_event` (`app/events/catalyst/consumer.py` `_forward_to_websocket`) | `catalyst_type`, `symbol`, `strategy_id`, `data` (objet imbrique, forme `EarningsEventData`/`SpinoffEventData` selon `catalyst_type` — voir `app/events/catalyst/schemas.py`) | **PR-2.5 fix (PR-5c finding)** : `WsUpdate.CatalystEvent.catalystType` est lu depuis `catalyst_type` (jamais `event_type`). Il n'y a pas de `title`/`description` au niveau racine — `description` est compose a partir des champs presents dans `data` (`fiscal_quarter`/`eps_direction` pour `earnings`, `child_symbol`/`phase` pour `spinoff`), sinon `null` |

### 3.2 WsUpdate -> ActivityItem (activity feed)

`GetActivityFeedUseCase` merge 5 flux via `kotlinx.coroutines.flow.merge` :

| Flux source | ActivityItem resultant |
|-------------|----------------------|
| `wsRepository.orderUpdates` | `ActivityItem.OrderFilled(orderId, symbol, side, status, quantity?, timestamp)` |
| `wsRepository.strategySignals` | `ActivityItem.Signal(symbol, action, confidence, strategyType, timestamp)` |
| `wsRepository.notifications` | `ActivityItem.RiskAlert(title, body, severity, timestamp)` |
| `wsRepository.portfolioUpdates` | `ActivityItem.PortfolioChange(totalValue?, symbol?, side?, quantity?, price?, dailyPnl?, timestamp)` |
| `wsRepository.catalystEvents` | `ActivityItem.CatalystEvent(symbol, eventType, title, timestamp)` — `eventType` <- `catalyst.catalystType`, `title` compose a partir d'un libelle ("Resultats"/"Spin-off") et de `catalyst.description` (nullable) |

`ActivityItem.PortfolioChange` affiche le trade execute (side + quantite + symbole) et la valeur totale du portfolio plutot qu'une NAV/P&L qui n'existe pas sur ce canal — `dailyPnl` reste `null` en pratique (le backend ne l'envoie jamais sur `portfolio_update`) et n'apparait dans l'UI que s'il devient un jour non-null.

Le `timestamp` de chaque `ActivityItem` est `Instant.now()` cote client (les payloads WS prives ne portent pas de timestamp serveur exploitable). Les champs manquants sont remplaces par des valeurs de fallback (`"unknown"`, `"--"`, `0.0`).

### 3.3 Consommation dans DashboardViewModel

Le `DashboardViewModel` collecte trois flux independants :

| Flux | UseCase | Comportement |
|------|---------|-------------|
| Portfolio updates (WS prive) | `GetPortfolioWsUpdatesUseCase` | Debounce 500ms. A chaque event, re-fetch NAV et PnL via REST. Backoff exponentiel sur erreur (5s->60s, max 5 retries). Flag `wsPrivateDegraded` si en erreur |
| Cours live (WS public) | `GetQuoteStreamUseCase` | Ecrit `QuoteUiState.Success(quote)` a chaque emission. En cas d'erreur (VPN, timeout, IO), bascule sur le polling REST 30s via `startPollingFallback()` |
| Activity feed (merge 5 flux) | `GetActivityFeedUseCase` | Prepend chaque item, cap a 12 elements. Erreurs ignorees silencieusement |

### 3.4 Consommation dans PositionsViewModel

Le `PositionsViewModel` collecte `GetPositionWsUpdatesUseCase` pour mettre a jour `currentPrice`, `unrealizedPnl` et `quantity` des positions en temps reel (affichage via `AnimatedPnlText`).

**Matching (`matchesPosition`)** : le backend n'envoie pas encore `position_id`, donc le matching se fait par symbole, **scope au statut OPEN** — un symbole seul ne peut pas distinguer une position cloturee d'une position rouverte sur le meme ticker (ex: deux lignes TSLA, une OPEN et une CLOSED, visibles ensemble sous le filtre ALL). Si `position_id` est fourni par un futur payload backend, le match se fait par id exact, sans contrainte de statut.

**Merge** : si `is_active == false` (le fill a completement cloture la position), la ligne est retiree de la liste sous le filtre OPEN, ou marquee `CLOSED` sous ALL/CLOSED. Sinon, `currentPrice <- last_price`, `unrealizedPnl <- unrealized_pnl`, `quantity <- quantity` (chaque champ ne remplace la valeur existante que s'il est present dans le payload).

### 3.5 Canal public : PublicWsEvent -> Quote -> UiState

```
PublicWsClient                    PublicWsRepositoryImpl          GetQuoteStreamUseCase
PublicWsEvent.MarketData ------> Quote (domain model) ---------> Flow<Quote>
  symbol, price, open,             symbol, price, bid, ask,         (propage tel quel)
  high, low, close,                volume, change=0,
  volume, bid?, ask?,              changePercent=0.0,
  timestamp                        timestamp, source="ws_public"
```

La conversion `MarketData -> Quote` :
- `bid` et `ask` : si null dans le stream, remplaces par `price`
- `change` et `changePercent` : mis a 0 (non disponibles via le WS public — uniquement via REST)
- `source` : `"ws_public"` pour tracer l'origine dans les logs

---

## 4. Etat de connexion (`WsConnectionState`)

### 4.1 Enum

```kotlin
enum class WsConnectionState {
    Connected,     // WebSocket connecte et authentifie
    Connecting,    // Tentative de connexion en cours (backoff ou premier connect)
    Disconnected,  // Deconnecte — fallback polling REST
    Degraded,      // Connexion instable — reconnexions frequentes
}
```

### 4.2 Emission par PrivateWsClient

| Evenement OkHttp | Transition |
|------------------|------------|
| `onOpen` | `-> Connected` |
| `connect()` appele | `-> Connecting` |
| `scheduleReconnect()` | `-> Connecting` |
| `onClosed` | `-> Disconnected` |
| `onFailure` | `-> Disconnected` |
| `disconnect()` | `-> Disconnected` |

L'etat est expose via `PrivateWsClient.connectionState: StateFlow<WsConnectionState>`.

### 4.3 Propagation vers l'UI

```
PrivateWsClient.connectionState (StateFlow)
    |
    v
WsRepository.connectionState (StateFlow, propage tel quel)
    |
    v
GetWsConnectionStateUseCase (propage tel quel)
    |
    v
DashboardViewModel.wsConnectionState (StateFlow)
    - debounce conditionnel :
      - Connected : propage immediatement (0ms)
      - Connecting / Disconnected / Degraded : debounce 2 000ms
    - distinctUntilChanged()
    - stateIn(WhileSubscribed(5_000), initialValue = Connecting)
    |
    v
DashboardScreen -> ConnectionStatusIndicator (Composable)
    - collectAsStateWithLifecycle()
    - Dot colore : vert (Connected), orange (Connecting/Degraded), rouge (Disconnected)
    - Animation de couleur 300ms (tween)
    - contentDescription TalkBack : "Temps reel actif", "Connexion en cours", etc.
```

Le debounce conditionnel evite le "flicker" lors des transitions rapides `Connected -> Connecting -> Connected` (< 2s). Seules les deconnexions durables (> 2s) sont propagees a l'UI.

Le `DashboardViewModel` derive egalement un `isWsLive: StateFlow<Boolean>` pour le badge "En direct" de la carte activity feed.

---

## 5. Fallback WS public -> polling REST

Le fallback est porte par le helper partage `ui/common/QuoteFallbackController` (aujourd'hui utilise
par `MarketDataViewModel` ; le Dashboard n'affiche plus de cours) :

1. Le controller collecte en permanence `GetQuoteStreamUseCase(symbol)` — le flux WS ne leve jamais
   sur coupure et n'est jamais annule, donc la resouscription est automatique apres reconnexion.
2. Quand `PublicWsClient.connectionState` n'est pas `Connected` depuis 2 s (debounce), le controller
   appelle `onStale` (le dernier cours reste affiche, marque perime) puis polle
   `GET /v1/market-data/quote/{symbol}` toutes les 30 s — uniquement si l'app est au premier plan.
3. Le polling gere ses propres erreurs (`onFetchError`) : VPN coupe -> perime, timeout / IOException
   -> etat inchange, autre -> erreur.
4. Le polling s'arrete des le retour a `Connected`.

---

## 6. Injection Hilt (`WebSocketModule`)

`WebSocketModule` (`di/WebSocketModule.kt`) est `@InstallIn(SingletonComponent::class)` et fournit :

| Binding | Implementation | Dependances |
|---------|----------------|-------------|
| `WsRepository` (domain interface) | `WsRepository` (data impl) | `PrivateWsClient` |
| `PublicWsRepository` (domain interface) | `PublicWsRepositoryImpl` | `PublicWsClient` |

Les deux clients (`PrivateWsClient`, `PublicWsClient`) sont `@Singleton @Inject constructor` — leurs dependances sont resolues directement par le graph Hilt :
- `OkHttpClient` : client principal (cert pinning + VPN interceptor)
- `CoroutineScope` : scope applicatif `@Singleton`
- `@Named("base_url") String` : URL de base du VPS
- `AuthRepository` : uniquement pour `PrivateWsClient` (obtention du token WS)

Les deux clients utilisent le client OkHttp principal (pas `@Named("bare")`) car le certificate pinning s'applique au handshake HTTP Upgrade et le `VpnRequiredInterceptor` garantit que la connexion passe par WireGuard.

---

## 7. Resume des constantes

| Constante | Emplacement | Valeur |
|-----------|-------------|--------|
| Backoff initial (prive et public) | `PrivateWsClient`, `PublicWsClient` | 5 000 ms |
| Backoff max (prive et public) | `PrivateWsClient`, `PublicWsClient` | 300 000 ms |
| Backoff multiplier | `PrivateWsClient`, `PublicWsClient` | 2.0 |
| Token refresh ratio | `PrivateWsClient` | 0.80 |
| Token refresh minimum | `PrivateWsClient` | 10 000 ms |
| SharedFlow buffer (prive et public) | `PrivateWsClient`, `PublicWsClient` | 64 elements |
| Quote sample interval (public) | `PublicWsRepositoryImpl` | 250 ms |
| WS state debounce (UI) | `DashboardViewModel` | 2 000 ms |
| Portfolio update debounce (WS prive) | `DashboardViewModel` | 500 ms |
| WS private retry initial | `DashboardViewModel` | 5 000 ms |
| WS private retry max | `DashboardViewModel` | 60 000 ms |
| WS private max consecutive failures | `DashboardViewModel` | 5 |
| Activity feed max items | `DashboardViewModel` | 12 |
| Polling REST fallback interval | `DashboardViewModel` | 30 000 ms |

---

## 8. Fichiers de reference

| Fichier | Role |
|---------|------|
| `data/websocket/PrivateWsClient.kt` | Client OkHttp WebSocket prive, lifecycle, reconnexion, refresh token |
| `data/websocket/PublicWsClient.kt` | Client OkHttp WebSocket public, subscribe/unsubscribe, reconnexion |
| `data/websocket/WsEvent.kt` | Sealed class des evenements du canal prive (couche data) |
| `data/websocket/PublicWsEvent.kt` | Sealed class des evenements du canal public (couche data) |
| `data/repository/WsRepository.kt` | Mapping WsEvent -> WsUpdate, expose les flows types |
| `data/repository/PublicWsRepositoryImpl.kt` | Mapping PublicWsEvent.MarketData -> Quote, sample 250ms, gestion souscriptions |
| `domain/repository/WsRepository.kt` | Interface domain pour le canal prive |
| `domain/repository/PublicWsRepository.kt` | Interface domain pour le canal public |
| `domain/model/WsUpdate.kt` | Sealed class des domain models WS (champs types, nullable) |
| `domain/model/WsConnectionState.kt` | Enum : Connected, Connecting, Disconnected, Degraded |
| `domain/model/ActivityItem.kt` | Sealed class du feed d'activite (merge des 5 flux) |
| `domain/model/WsTokenInfo.kt` | Token JWT WS + instant d'expiration |
| `domain/usecase/activity/GetActivityFeedUseCase.kt` | Merge les 5 flux WS prives en Flow\<ActivityItem\> |
| `domain/usecase/portfolio/GetPortfolioWsUpdatesUseCase.kt` | Pass-through vers WsRepository.portfolioUpdates |
| `domain/usecase/portfolio/GetPositionWsUpdatesUseCase.kt` | Pass-through vers WsRepository.positionUpdates |
| `domain/usecase/portfolio/GetWsConnectionStateUseCase.kt` | Pass-through vers WsRepository.connectionState |
| `domain/usecase/market/GetQuoteStreamUseCase.kt` | Pass-through vers PublicWsRepository.quoteUpdates |
| `di/WebSocketModule.kt` | Bindings Hilt : interfaces domain -> implementations data |
| `ui/components/ConnectionStatusIndicator.kt` | Composable dot colore avec animation et accessibilite |
| `ui/screens/dashboard/DashboardViewModel.kt` | Consommateur principal : debounce etat, fallback REST, activity feed |
