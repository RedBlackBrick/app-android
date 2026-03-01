# Contrats API — Référence Android

Extraits de `trading-platform/docs/08_reference/API_ENDPOINTS.md` et
`trading-platform/docs/02_architecture/API_CONVENTIONS.md`.

---

## Base URL et auth

```
Production  : https://10.42.0.1:8013  (via tunnel WireGuard uniquement)
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
      "last_heartbeat": "2025-11-17T14:28:00Z"
    }
  ]
}
```

> Endpoint existant dans l'API Gateway (accès conditionnel `is_admin`).
> L'API edge-control (port 8013) est Radxa → VPS uniquement — pas accessible depuis l'app.

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
