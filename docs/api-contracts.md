# Contrats API — Référence Android

Extraits de `trading-platform/docs/08_reference/API_ENDPOINTS.md` et
`trading-platform/docs/02_architecture/API_CONVENTIONS.md`.

---

## Base URL et auth

```
Production  : https://10.42.0.1:443  (via tunnel WireGuard uniquement)
Dev         : http://localhost:8000

Authorization: Bearer <access_token>   ← header sur tous les endpoints protégés
```

---

## Authentification

### POST /v1/auth/login

**Request :**
```json
{ "email": "user@example.com", "password": "..." }
```

**Response 200 :**
```json
{
  "user": {
    "id": 1,
    "email": "user@example.com",
    "first_name": "John",
    "last_name": "Doe",
    "is_admin": false,
    "totp_enabled": false
  },
  "tokens": {
    "access_token": "eyJ...",
    "token_type": "bearer",
    "expires_in": 900
  }
}
```

> ⚠ **Le `refresh_token` est un httpOnly cookie**, pas dans le body.
> Implémenter `EncryptedCookieJar` : `CookieJar` OkHttp persisté dans `EncryptedDataStore`.
> Voir `CLAUDE.md §11` pour l'implémentation.

> ⚠ Si `totp_enabled: true`, naviguer vers `TotpScreen` avant `GET /v1/portfolios`.
> Voir `CLAUDE.md §11` pour le flow complet.

**Erreurs :**
- `401 AUTH_1001` — credentials invalides
- `401 AUTH_1004` — 2FA requis
- `429` — compte verrouillé (header `Retry-After`, max 5 tentatives)

---

### POST /v1/auth/refresh

Pas de body. Le cookie httpOnly est envoyé automatiquement par OkHttp via `CookieJar`.

**Response 200 :**
```json
{ "access_token": "eyJ...", "token_type": "bearer", "expires_in": 900 }
```

> Nouveau cookie refresh positionné automatiquement (rotation à chaque refresh).
> Déclencher via `OkHttp Authenticator` sur réponse `401 AUTH_1002`.

---

### POST /v1/auth/logout

**Response 200 :** `{ "message": "Logout successful", "success": true }`

---

### GET /v1/auth/me

**Response 200 :** même structure que `user` dans la réponse login.

> ⚠ `portfolio_id` absent de cette réponse. Obtenir via `GET /v1/portfolios` après login.

Utilisé par `ProfileScreen` via `GetUserProfileUseCase`.

---

### POST /v1/auth/ws-token

Obtient un token JWT avec claim `"websocket"` pour établir la connexion `PrivateWsClient`.

Pas de body.

**Response 200 :**
```json
{ "token": "eyJ...", "expires_at": "2025-11-17T15:30:00Z" }
```

Auth : JWT Bearer.

---

### POST /v1/auth/2fa/verify (si totp_enabled)

**Request :**
```json
{ "session_token": "...", "totp_code": "123456" }
```

**Response 200 :** `{ "verified": true }`

---

## Portfolio

### GET /v1/portfolios

Appelé immédiatement après login pour découvrir le `portfolio_id` de l'utilisateur.

**Response 200 :**
```json
{
  "portfolios": [
    { "id": 1, "name": "Main Portfolio", "currency": "USD" }
  ]
}
```

Stocker `portfolios[0].id` dans `EncryptedDataStore` (clé `auth_portfolio_id`).
Réutiliser pour tous les appels suivants sans re-fetch.

---

### GET /v1/portfolios/{portfolio_id}/positions

**Query params :** `status = OPEN | CLOSED | ALL` (défaut: `OPEN`)

**Response 200 :**
```json
{
  "positions": [
    {
      "id": 42,
      "symbol": "AAPL",
      "quantity": "100.0000",
      "avg_price": "150.00000000",
      "current_price": "160.00000000",
      "unrealized_pnl": "1000.00000000",
      "unrealized_pnl_percent": 6.67,
      "status": "open",
      "opened_at": "2025-11-01T10:00:00Z"
    }
  ],
  "total": 1
}
```

---

### GET /v1/portfolios/{portfolio_id}/pnl

**Query params :** `period = day | week | month | ytd | all` (backend `router.py` —
`PnlPeriod.YEAR.toApiString() == "ytd"` ; une valeur inconnue comme `year` n'est pas rejetée
mais retombe silencieusement sur `all`).

**Response 200 :**
```json
{
  "period": "day",
  "realized_pnl": "250.00",
  "unrealized_pnl": "1500.00",
  "total_pnl": "1750.00",
  "total_pnl_percent": 1.75,
  "trades_count": 3,
  "winning_trades": 2,
  "losing_trades": 1
}
```

**Unités :** `total_pnl_percent` est un **pourcentage** (1.75 = 1,75 %) ; l'app le convertit en
**fraction** (0.0175) dans les mappers (`PnlResponseDto.toPnlSummary()` / `toEntity()`) — le domaine
et la table `pnl_snapshots` ne manipulent que des fractions. Les montants P&L sont cumulés sur la
vie du portefeuille ; `period` ne borne que `trades_count` / `winning_trades` / `losing_trades`.

**Cache :** seul chemin PnL de l'app. `PortfolioRepositoryImpl.getPnlSummary` (Dashboard et
`WidgetUpdateWorker` via `GetPnlUseCase`) upsert une ligne `pnl_snapshots` par période
(`period` = clé primaire), lue par `PnlWidget`.

---

### GET /v1/portfolios/{portfolio_id}/nav

**Response 200 :**
```json
{
  "nav": "101750.00000000",
  "cash": "50000.00000000",
  "positions_value": "51750.00000000",
  "timestamp": "2025-11-17T14:30:00Z"
}
```

---

### GET /v1/portfolios/{portfolio_id}/transactions

**Query params :** `limit` (défaut 50), `offset` (défaut 0), `symbol`, `from_date`, `to_date`

**Response 200 :**
```json
{
  "transactions": [
    {
      "id": 1,
      "symbol": "AAPL",
      "action": "buy",
      "quantity": "100.0000",
      "price": "150.00000000",
      "commission": "2.00000000",
      "total": "15002.00000000",
      "executed_at": "2025-11-17T10:00:00Z"
    }
  ],
  "total": 1,
  "limit": 50,
  "offset": 0
}
```

---

## Notifications

### POST /v1/notifications/fcm-token

Enregistre ou met à jour le token FCM de l'appareil pour l'utilisateur authentifié.

**Request :**
```json
{ "fcm_token": "...", "device_fingerprint": "..." }
```

**Response 200 :**
```json
{ "registered": true }
```

Auth : JWT Bearer. Appelé depuis `TradingFirebaseMessagingService.onNewToken()` via `RegisterFcmTokenUseCase`.

---

## Market Data

### GET /v1/market-data/quote/{symbol}

**Response 200 :**
```json
{
  "symbol": "AAPL",
  "price": "160.00000000",
  "bid": "159.98000000",
  "ask": "160.02000000",
  "volume": 45231000,
  "change": "2.50000000",
  "change_percent": 1.59,
  "timestamp": "2025-11-17T14:30:00Z",
  "source": "yahoo"
}
```

### GET /v1/market-data/symbols

Catalogue paginé des symboles trackés par le backend, avec recherche serveur.
`MarketDataViewModel` débounce la recherche (300 ms) et pagine par offset
(`GetAvailableSymbolsUseCase(search, limit, offset)`) pour `SymbolPickerSheet`.

**Query params :** `search` (optionnel), `exchange` (optionnel), `currency` (optionnel),
`index_sid` (optionnel), `limit` (défaut 100, max 500), `offset` (défaut 0)

**Response 200 :**
```json
{
  "symbols": [
    {"sid": 1, "ticker": "AAPL", "name": "Apple Inc.", "exchange": "NASDAQ", "currency": "USD", "is_active": true}
  ],
  "total": 4,
  "limit": 100,
  "offset": 0,
  "has_more": false
}
```

Décodé en `SymbolListResponseDto` (`SymbolListItemDto` par entrée). Le repository filtre
`is_active=false` et mappe vers `SymbolInfo(ticker, name, exchange?, currency?)` /
`SymbolPage(items, hasMore, nextOffset)`. `MarketDataRepository.getAvailableSymbols()`
(sans argument) reste disponible comme raccourci — première page, non filtrée par recherche —
pour les appelants qui n'ont pas besoin de pagination (widgets).

---

### GET /v1/market-data/{symbol}/history

Historique OHLCV pour les sparklines de la watchlist (30 derniers points close).
Utilise `get_range` (et non `GET /v1/market-data/` = `get_latest`, qui est trié DESC) :
la réponse est déjà chronologique (plus ancien en premier), donc **aucun** `asReversed()`
n'est appliqué côté app.

**Query params :** `start`, `end` (ISO-8601, **requis** côté backend — l'app envoie une
fenêtre glissante `now - 45 jours` à `now`), `timeframe` (défaut `1d`), `limit` (défaut 1000,
l'app passe la valeur de `GetSymbolHistoryUseCase`, défaut 30), `cursor` (optionnel),
`adjusted` (optionnel, défaut `false`)

**Response 200 :**
```json
{
  "symbol": "AAPL",
  "data": [
    {
      "timestamp": "2026-09-23T00:00:00Z",
      "open": "226.1000",
      "high": "229.4500",
      "low": "225.8000",
      "close": "228.9200",
      "volume": 51234567
    }
  ],
  "count": 1,
  "timeframe": "1d",
  "next_cursor": null
}
```

Décodé en `MarketDataResponseDto` (`data: List<MarketDataPointDto>`). Utilisé par
`MarketDataScreen` via `GetSymbolHistoryUseCase` (extraction du champ `close` pour
sparklines, ordre préservé tel quel).

### GET /v1/portfolios/{portfolio_id}/performance

Retourne les métriques de performance calculées côté serveur.

**Response 200 :**
```json
{
  "total_return": "5250.00",
  "total_return_pct": 0.105,
  "sharpe_ratio": 1.45,
  "sortino_ratio": 2.1,
  "max_drawdown": 8.3,
  "volatility": 0.152,
  "cagr": 0.125,
  "win_rate": 0.65,
  "profit_factor": 2.3,
  "avg_trade_return": "125.00"
}
```

Tous les champs sont nullable (retournent `null` si données insuffisantes).

**Unités :** `total_return_pct`, `volatility`, `cagr` et `win_rate` sont des **fractions**
(0.105 = 10,5 %). **Exception : `max_drawdown` est un pourcentage positif** (8.3 = 8,3 %,
`calculators/performance.py` `* 100`) — converti en fraction côté app dans
`PerformanceResponseDto.toPerformanceMetrics()` (8.3 → 0.083). Le domaine `PerformanceMetrics`
ne contient que des fractions ; `PerformanceScreen` multiplie par 100 à l'affichage.
Endpoint consommé uniquement par `getPerformance()` → `PerformanceMetrics` (jamais écrit dans Room).

---

## Devices Edge

> **Réservé aux comptes admin** (`user.is_admin == true`). L'onglet Devices et le workflow
> de pairing sont masqués pour les comptes standard.

### GET /v1/edge/devices (admin uniquement)

Liste les devices enregistrés avec leur statut.

**Response 200 :**
```json
{
  "devices": [
    {
      "id": "uuid",
      "name": "Radxa-01",
      "status": "online",
      "wg_ip": "10.42.0.5",
      "last_heartbeat": "2025-11-17T14:28:00Z",
      "cpu_pct": 12.5,
      "memory_pct": 45.0,
      "temperature": 52.3,
      "disk_pct": 38.0,
      "uptime_seconds": 86400,
      "firmware_version": "1.2.0",
      "hostname": "radxa-01",
      "last_ticks_sent": 12345,
      "last_scraper_errors": 2,
      "scrapers_circuit": {
        "yahoo": {"state": "closed", "consecutive_failures": 0, "total_trips": 0},
        "boursorama": {"state": "open", "consecutive_failures": 5, "total_trips": 1}
      },
      "broker_gateway_enabled": true,
      "broker_gateway_status": "running",
      "broker_gateway_broker_id": 1
    }
  ]
}
```

> Endpoint existant dans l'API Gateway (accès conditionnel `is_admin`).

---

### POST /v1/edge-control/commands (admin uniquement)

Envoie une commande à un device Radxa via le VPS.

**Request :**
```json
{ "device_id": "uuid", "command_type": "reboot", "params": {} }
```

**Response 200 :** `200 OK`

Auth : JWT Bearer. Accessible uniquement depuis le sous-réseau VPN.

---

### GET /v1/edge/broker-connections/{device_id} (admin uniquement)

Connexions broker d'un device.

**Response 200 :**
```json
[
  {
    "device_id": "uuid",
    "portfolio_id": "uuid",
    "broker_code": "interactive_brokers",
    "connection_status": "connected",
    "execution_mode": "device",
    "created_at": "2026-03-01T10:00:00Z"
  }
]
```

---

### POST /v1/edge/broker-connections (admin uniquement)

Déploie un broker sur un device.

**Request :**
```json
{ "device_id": "uuid", "broker_code": "interactive_brokers", "portfolio_id": "uuid" }
```

**Response 200 :** même structure que l'objet dans la liste ci-dessus.

---

### DELETE /v1/edge/broker-connections/{device_id}/{portfolio_id} (admin uniquement)

Retire un broker d'un device.

**Response 200 :** `200 OK`

---

### POST /v1/edge/broker-connections/{device_id}/test (admin uniquement)

Teste la connexion broker du device.

**Response 200 :**
```json
{ "healthy": true, "message": null }
```

---

## Format d'erreur standard

```json
{
  "error_code": "AUTH_1002",
  "message": "Token expired",
  "details": { "field": "...", "reason": "..." },
  "timestamp": "2025-11-17T14:30:00Z",
  "success": false
}
```

| Code | Signification |
|------|--------------|
| `AUTH_1001` | Credentials invalides |
| `AUTH_1002` | Token expiré → déclencher refresh |
| `AUTH_1003` | Token invalide → logout |
| `AUTH_1004` | 2FA requis → écran TOTP |
| `AUTH_1006` | Session expirée → logout |
| `AUTH_1008` | Compte verrouillé |
| `PORTFOLIO_5001` | Portfolio introuvable |
| `SYSTEM_9001` | Erreur interne |
| `SYSTEM_9005` | Timeout |

---

## Conventions de types

| Type | Sérialisation JSON | Type Kotlin |
|------|--------------------|-------------|
| Prix, montants (Numeric 18,8) | `"150.00000000"` **string** | `BigDecimal` |
| Quantités (Numeric 18,4) | `"100.0000"` **string** | `BigDecimal` |
| Pourcentages | `6.67` float | `Double` |
| Dates | ISO 8601 UTC `"2025-11-17T14:30:00Z"` | `Instant` |
| ID user / order | BigInteger | `Long` |
| ID portfolio / position / strategy | Integer | `Int` |
| ID symbol | SmallInteger (`sid`) | `Int` |
| Enums | lowercase `"open"`, `"buy"` | `sealed class` ou `enum` |

> ⚠ Ne jamais mapper prix/quantités vers `Double` — perte de précision sur les calculs financiers.

---

## Cycle de vie des tokens

| Token | Durée | Stockage Android |
|-------|-------|-----------------|
| Access token | 900s (15 min) | `EncryptedDataStore` clé `auth_access_token` |
| Refresh token | Non documenté | **httpOnly cookie** → `EncryptedCookieJar` OkHttp |
| CSRF token | Durée session | Mémoire (`CsrfInterceptor` cache) |

**Stratégie de refresh (transparente pour l'utilisateur) :**
1. `TokenAuthenticator` intercepte `401 AUTH_1002`
2. Appelle `POST /v1/auth/refresh` (cookie envoyé automatiquement par `EncryptedCookieJar`)
3. Succès → nouveau access_token → retry la requête originale
4. Échec (`401 AUTH_1003`) → logout forcé → `LoginScreen`

**Rotation :** le refresh token est renouvelé à chaque appel `/refresh`.
Grace period 5s pour les appels concurrents (un seul refresh via `Mutex`).

**CSRF :** `CsrfInterceptor` appelle `GET /csrf-token` et injecte `X-CSRF-Token` sur tous les
`POST/PUT/DELETE/PATCH`. Le VPS n'exempte pas les requêtes Bearer — interceptor obligatoire.
Voir `CLAUDE.md §3` pour la chaîne d'intercepteurs complète et `§11` pour l'implémentation.

---

## Onboarding mobile — QR format

```json
{
  "wg_private_key": "base64... (44 chars)",
  "wg_public_key_server": "base64... (44 chars)",
  "endpoint": "vps.example.com:51820",
  "tunnel_ip": "10.42.0.101/32",
  "dns": "10.42.0.1"
}
```

QR Version ~10, TTL 5 minutes. Généré par `GET /v1/vpn-peers/mobile-setup-qr` (admin, VPS).

---

## Pairing — QR VPS

```json
{
  "session_id": "uuid",
  "session_pin": "472938",
  "device_wg_ip": "10.42.0.5",
  "local_token": "hex-256-bit",
  "nonce": "64-char-hex"
}
```

Le `nonce` est un token anti-replay one-time-use (64 caractères hex) avec TTL 5 minutes. Il est consommé atomiquement par le VPS lors de la complétion du pairing.

---

## Pairing — POST LAN /pin (format chiffré)

```
POST http://{radxa_ip}:8099/pin
Content-Type: application/octet-stream

Body: crypto_box_seal(
  '{"session_id":"uuid","session_pin":"472938","local_token":"hex","nonce":"64-char-hex"}',
  radxa_wg_pubkey
)
```

Réponse : `200 OK` (body vide ou `{"status": "ok"}`).
En cas de nonce invalide ou rejoué, le VPS retourne `409 Conflict` à la Radxa.

---

## Maintenance LAN — POST /command (format chiffré)

```
POST http://{radxa_ip}:8099/command
Content-Type: application/octet-stream

Body: crypto_box_seal(
  '{"action":"wifi_configure","local_token":"hex","params":{"ssid":"...","password":"..."}}',
  radxa_wg_pubkey
)
```

Actions : `wifi_configure`, `wireguard_restart`, `logs`, `reboot`.

---

## Maintenance LAN — GET /identity

```
GET http://{radxa_ip}:8099/identity

Response 200:
{
  "device_id": "radxa-001",
  "wg_pubkey": "base64...",
  "local_ip": "192.168.1.42"
}
```

---

## Maintenance LAN — GET /status

```
GET http://{radxa_ip}:8099/status

Response 200:
{
  "device_id": "radxa-001",
  "wg_status": "up",
  "wifi_ssid": "MyNetwork",
  "uptime": "3d 12h",
  "last_error": null
}
```

---

## Garde de drift de contrats

Audit `PLAN.md` Phase 0 §3 / PR 5c — conception détaillée : `audit/plan-ui-tests-ci.md` PART 2
§(3). Objectif : détecter automatiquement un décalage entre ce que l'app suppose (DTOs Moshi,
chemins Retrofit, clés lues dans les payloads WebSocket) et ce que le backend envoie
réellement, sans dépendre d'un VPS/MockWebServer — la source de vérité est l'OpenAPI backend
commité (`trading-platform2/audit/front/openapi.json`, 3.1, ~490 chemins) plus un fichier
maintenu à la main pour les événements WebSocket (qui n'ont pas d'export OpenAPI/AsyncAPI).

### Fichiers

| Fichier | Rôle |
|---|---|
| `scripts/extract_openapi_contracts.py` | Lit l'OpenAPI backend, ne garde que les chemins appelés par les interfaces Retrofit d'`app/src/main/.../data/api/*.kt` et les schémas `components.schemas` référencés transitivement par leurs requêtes/réponses. Écrit `app/src/test/resources/contracts/openapi.json` (trié, formaté). |
| `app/src/test/resources/contracts/openapi.json` | Sortie du script ci-dessus — **généré, à regénérer et commiter**, jamais édité à la main. |
| `app/src/test/resources/contracts/ws_events.json` | Maintenu à la main : pour chaque type d'événement WS privé/public, la liste des clés JSON que le backend envoie réellement (union sur tous les points d'émission), avec la référence au fichier backend source. |
| `app/src/test/java/com/tradingplatform/app/contracts/DtoContractTest.kt` | Les 4 tests JUnit qui comparent le code app à ces deux fichiers (réflexion Kotlin sur `@Json(name)` / nullabilité / valeurs par défaut des DTOs, réflexion sur les annotations Retrofit `@GET`/`@POST`/…). |

### Quand regénérer

```bash
python3 scripts/extract_openapi_contracts.py
# ou, si l'export OpenAPI backend n'est pas au chemin par défaut :
python3 scripts/extract_openapi_contracts.py /chemin/vers/openapi.json
```

À relancer (et commiter le fichier `openapi.json` régénéré) :
- après tout changement de schéma backend touchant un endpoint que l'app appelle (champ
  renommé/supprimé, `required` modifié, ajout d'un endpoint consommé côté app) ;
- avant de merger toute PR qui modifie un DTO Android ou une interface Retrofit `data/api/*Api.kt` ;
- en CI, idéalement via une future cible `make openapi-android` côté backend qui régénère et
  copie l'export réduit (non encore câblée — actuellement un geste manuel).

`ws_events.json` n'a pas de script d'extraction (pas d'export WS côté backend) — le mettre à
jour à la main en relisant les fichiers cités dans son champ `source` par événement
(`app/portfolio/consumer.py`, `app/execution/consumer.py`, `app/execution/exit_consumer.py`,
`app/notification/service.py`, `app/notification/consumer.py`, `app/strategy/consumer/base.py`,
`app/events/catalyst/consumer.py`, `app/websocket/market_data_bridge.py` côté trading-platform2).

### Ce que `DtoContractTest` vérifie

1. **Champs DTO présents dans le schéma** — chaque `@Json(name=...)` (ou nom de paramètre à
   défaut) d'un DTO de la table `DTO_SCHEMA_TABLE` existe dans `schema.properties` (en suivant
   `allOf`/`$ref`).
2. **Champs DTO non-nullables sans défaut couverts par le schéma** — chaque paramètre non-nullable
   sans valeur par défaut doit correspondre à un champ `required` **ou** portant un `default`
   explicite dans le schéma (Pydantic sérialise toujours un champ avec défaut, même absent de
   `required`). Sinon : risque réel de crash Moshi (le backend peut légalement omettre ou
   `null`-er le champ).
3. **Chemins Retrofit présents dans l'OpenAPI réduit** — chaque `@GET/@POST/@PUT/@DELETE/@PATCH`
   des interfaces `data/api/*Api.kt` (lu par réflexion sur les annotations, pas par re-parsing
   du `.kt`) existe dans `openapi.json`, aux noms de `{param}` près.
4. **Clés WS lues par l'app ⊆ clés envoyées par le backend** — pour chaque type d'événement WS,
   l'ensemble de clés lu (codé en dur dans le test, miroir de `WsRepository.kt` /
   `PrivateWsClient.kt` / `PublicWsClient.kt`) doit être un sous-ensemble de `ws_events.json`.

Aurait attrapé les findings d'audit #6, #7 et #23 (formes d'enveloppe market-data, noms de
champs des payloads WS position/portfolio) s'il avait existé avant leurs correctifs en PRs
2.1/2.3.

### Findings PR 5c (nouveaux, non corrigés dans cette PR)

En écrivant ce test, plusieurs dérives **non tracées auparavant** dans `audit/PLAN.md` sont
apparues. Cette PR ne modifie pas le code source principal (hors périmètre) — les tests
suivants sont donc **rouges intentionnellement** tant qu'un correctif dédié n'est pas fait :

| Test | Constat |
|---|---|
| DTO non-nullables | `PositionDto.quantity` / `PositionDto.avgPrice` (← `average_price`) sont non-nullables sans défaut côté Kotlin, mais `PositionResponse.quantity`/`average_price` sont `Decimal \| None = None` côté backend (trading-platform2 `app/portfolio/schemas.py:980-981`) — ni `required`, ni `default`. |
| DTO champs présents | `DeviceDto.hostname` / `.scrapersCircuit` / `.availableMemoryMb` n'ont aucune propriété correspondante dans `DeviceResponse` (`app/edge/schemas.py:186-221`) — toujours `null`, code mort côté app. |
| WS `order_update` | L'app lit `fill_price` (`WsRepository.kt:102`) ; aucun point d'émission (`execution/consumer.py`, `execution/exit_consumer.py`) n'envoie cette clé, seulement `price`. |
| WS `notification` | L'app lit `data.type` avec fallback `"info"` (`PrivateWsClient.kt:445`) ; le backend envoie `notification_type`, jamais `type`. |
| WS `catalyst_event` | L'app lit `event_type`/`title`/`description` (`WsRepository.kt:123-126`) ; le backend envoie `catalyst_type` (pas `event_type`) et aucun `title`/`description` au niveau racine (seulement `data` imbriqué). |
| WS `market_data` (public) | L'app lit `source_name`/`source_type`/`quality` (`PublicWsClient.kt:143-147`) ; le canal public n'envoie que `source` (texte libre) — `source_name`/`source_type`/`quality` n'existent que sur le canal admin (`app/websocket/admin_events.py`). |

Voir le rapport de la PR 5c pour les citations complètes (fichier:ligne backend) et les
recommandations de correctif.
