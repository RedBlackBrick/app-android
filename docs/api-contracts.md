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

**Query params :** `period = day | week | month | year | all`

**Response 200 :**
```json
{
  "period": "day",
  "realized_pnl": "250.00000000",
  "unrealized_pnl": "1500.00000000",
  "total_pnl": "1750.00000000",
  "total_pnl_percent": 1.75,
  "trades_count": 3,
  "winning_trades": 2,
  "losing_trades": 1
}
```

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

Retourne la liste de tous les symboles trackés par le backend.

**Response 200 :**
```json
["CAC40", "SP500", "NASDAQ", "DOW", "SBF120"]
```

### GET /v1/portfolios/{portfolio_id}/performance

Retourne les métriques de performance calculées côté serveur.

**Response 200 :**
```json
{
  "total_return": "5250.00",
  "total_return_pct": 10.5,
  "sharpe_ratio": 1.45,
  "sortino_ratio": 2.1,
  "max_drawdown": 8.3,
  "volatility": 15.2,
  "cagr": 12.5,
  "win_rate": 0.65,
  "profit_factor": 2.3,
  "avg_trade_return": "125.00"
}
```

Tous les champs sont nullable (retournent `null` si données insuffisantes).

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
      "hostname": "radxa-01"
    }
  ]
}
```

> Endpoint existant dans l'API Gateway (accès conditionnel `is_admin`).

---

### POST /v1/edge-control/devices/{id}/commands (admin uniquement)

Envoie une commande à un device Radxa via le VPS.

**Request :**
```json
{ "command": "reboot" }
```

**Response 200 :**
```json
{ "accepted": true }
```

Auth : JWT Bearer. Accessible uniquement depuis le sous-réseau VPN (`/v1/edge-control/*` restreint par Caddy).

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
