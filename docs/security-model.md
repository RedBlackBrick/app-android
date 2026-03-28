# Modele de securite — app-android

Reference technique du modele de securite de l'app Android Trading Platform.
Ce document decrit les mecanismes implementes dans le code source.

---

## Table des matieres

1. [Modele de menaces](#1-modele-de-menaces)
2. [Chaine de confiance](#2-chaine-de-confiance)
3. [Cycle de vie des tokens](#3-cycle-de-vie-des-tokens)
4. [Chaine d'intercepteurs OkHttp](#4-chaine-dintercepteurs-okhttp)
5. [Certificate pinning](#5-certificate-pinning)
6. [Verrou biometrique](#6-verrou-biometrique)
7. [Detection root](#7-detection-root)
8. [Securite LAN (pairing et maintenance)](#8-securite-lan-pairing-et-maintenance)
9. [EncryptedDataStore et corruption Keystore](#9-encrypteddatastore-et-corruption-keystore)
10. [Nettoyage au logout](#10-nettoyage-au-logout)
11. [Controle de version applicative](#11-controle-de-version-applicative)

---

## 1. Modele de menaces

### Device roote

**Risque** : un device roote permet l'extraction de cles Keystore, l'injection dans le process
de l'app et le bypass des controles client.

**Mitigations** :
- `RootDetector` (`security/RootDetector.kt`) utilise RootBeer (`com.scottyab.rootbeer.RootBeer`)
  pour la detection cote client. La detection est wrappee dans un try/catch qui retourne `false`
  en cas d'exception (fail-open, couche de defense en profondeur uniquement).
- RootBeer est bypassable par Magisk/Zygisk. Pour les distributions via le Play Store, Play
  Integrity API est recommande en complement (attestation serveur non bypassable cote client).
- Pour la distribution en sideload (usage VPN-only), RootBeer reste le seul mecanisme disponible.

### Man-in-the-Middle (MitM)

**Risque** : interception du trafic TLS entre l'app et le VPS.

**Mitigations** :
- Tout le trafic VPS transite par le tunnel WireGuard (`VpnRequiredInterceptor` bloque si
  `VpnState != Connected`).
- Certificate pinning OkHttp sur le SPKI hash de la Root CA Caddy (voir section 5).
- Le `network_security_config.xml` bloque le cleartext par defaut (`cleartextTrafficPermitted="false"`
  dans `<base-config>`), avec une exception limitee aux prefixes LAN RFC-1918 pour le pairing Radxa.
- Les headers `Authorization` et `X-CSRF-Token` sont masques dans les logs HTTP
  (`HttpLoggingInterceptor.redactHeader()`).

### Attaques par rejeu (replay)

**Risque** : capture et rejeu de requetes authentifiees.

**Mitigations** :
- Double-submit cookie CSRF : chaque requete mutante (`POST/PUT/DELETE/PATCH`) doit porter
  le header `X-CSRF-Token` ET le cookie `csrf_token` avec des valeurs identiques.
- Le token CSRF est invalide et re-fetche sur reponse 403 (une seule tentative de retry).
- Les sessions de pairing utilisent un `nonce` unique (64 chars hex) et un TTL de 120 secondes.
- Le `session_pin` est a usage unique cote VPS — il est invalide apres usage reussi.

### Device vole

**Risque** : acces physique au device avec l'app installee.

**Mitigations** :
- Verrou biometrique a double mecanisme (voir section 6) : Keystore avec
  `setUserAuthenticationValidityDurationSeconds(300)` + timer d'inactivite dans `MainActivity`.
- Overlay opaque sur l'ecran quand le verrou est actif — donnees non visibles.
- `BiometricLockManager` (`security/BiometricLockManager.kt`) expose un `StateFlow<Boolean>`
  partage entre `MainActivity` et l'UI.
- Les tokens et cles prives sont chiffres via `EncryptedSharedPreferences` (AES256-GCM) protege
  par le MasterKey Android Keystore.
- Les ecritures critiques (access token, cles WireGuard) utilisent `commit = true` (synchrone)
  pour eviter la perte en cas de kill du process.

### Corruption du Keystore

**Risque** : sur certains devices (Samsung, Xiaomi), le Keystore Android peut etre invalide au
reboot, rendant les `EncryptedSharedPreferences` illisibles.

**Mitigations** :
- `EncryptedDataStore.readString()` wrappe chaque lecture dans un try/catch
  `IOException` + `GeneralSecurityException`, retourne `null` en cas d'echec.
- `EncryptedDataStore.readStringSafe()` retourne un `SecureReadResult<T>` sealed interface
  avec trois cas : `Found(value)`, `NotFound`, `Corrupted(cause)`.
- `AuthInterceptor` utilise `readStringSafe()` et distingue "token absent" (logout force via
  `SessionManager.notifyForcedLogout()`) de "Keystore corrompu" (notification via
  `SessionManager.notifyKeystoreCorruption()` avec message explicite a l'utilisateur).
- `SessionManager` expose un `keystoreCorruptionEvents: SharedFlow<Unit>` distinct de
  `forcedLogoutEvents` pour que l'UI affiche un message adapte.

---

## 2. Chaine de confiance

```
Android Keystore (AES256-GCM, MasterKey)
    |
    v
EncryptedSharedPreferences ("trading_secure_prefs")
    |-- auth_access_token (JWT)
    |-- wg_private_key (cle privee WireGuard)
    |-- cookie_refresh_token (httpOnly cookie)
    |-- local_token_{device_id} (tokens LAN par device)
    |-- csrf_token (cache)
    |
    v
Certificate Pinning OkHttp (Root CA SPKI SHA-256)
    |-- BuildConfig.CERT_PIN_SHA256
    |-- BuildConfig.CERT_PIN_SHA256_BACKUP
    |
    v
WireGuard VPN Tunnel (GoBackend, wireguard-android)
    |-- Cle privee : lue depuis EncryptedDataStore, jamais loggee
    |-- Tunnel : foreground service (WireGuardVpnService)
    |-- Etat : StateFlow<VpnState> immutable (Disconnected | Connecting | Connected | Error)
    |
    v
VPS (HTTPS via tunnel, 10.42.0.1:443)
```

### Invariants

- La cle privee WireGuard est generee une seule fois, stockee dans `EncryptedDataStore`,
  protegee par Android Keystore. Elle ne sort jamais de l'app.
- Toute requete API vers le VPS est conditionnee par `VpnState.Connected`
  (`VpnRequiredInterceptor`), sauf les endpoints exempts : `/v1/auth/login`,
  `/v1/auth/refresh`, `/v1/auth/2fa/verify`, `/csrf-token`.
- Le `MutableStateFlow<VpnState>` est interne a `WireGuardManager` — l'API publique expose
  un `StateFlow<VpnState>` immutable (`asStateFlow()`).
- `VpnNotConnectedException` etend `Exception` (pas `IOException`) pour permettre un catch
  distinct dans `WidgetUpdateWorker` sans declencher les retries WorkManager.

---

## 3. Cycle de vie des tokens

### Access token (JWT Bearer)

- **Stockage** : `EncryptedDataStore` cle `auth_access_token` + cache in-memory `TokenHolder`
  (`@Volatile var accessToken`).
- **Injection** : `AuthInterceptor` lit `TokenHolder.accessToken` (volatile read, ~0ns). Si null
  (cold start apres process kill), fallback vers `EncryptedDataStore.readStringSafe()` puis
  peuplement du cache.
- **Refresh** : sur reponse 401, `TokenAuthenticator` envoie `POST /v1/auth/refresh` avec le
  cookie httpOnly `refresh_token`. Pattern concurrent `Mutex + Deferred` : si N requetes
  recoivent 401 simultanement, une seule lance le refresh, les autres attendent le `Deferred`.
  Timeout global de 15 secondes (`AUTHENTICATE_TIMEOUT_MS`).
- **Ecriture** : `TokenHolder.setToken()` avant `EncryptedDataStore.writeString()` (cache
  mémoire d'abord, disque ensuite). Si le process est tue entre les deux, le fallback DataStore
  relira l'ancien token, declenchera un nouveau 401, puis un nouveau refresh.

### Refresh token (httpOnly cookie)

- **Stockage** : `EncryptedCookieJar` persiste la valeur du cookie `refresh_token` dans
  `EncryptedDataStore` (cle `cookie_refresh_token`, `commit = true` synchrone).
- **Sauvegarde** : uniquement sur les reponses des paths exacts `/v1/auth/login` et
  `/v1/auth/refresh`. Filtre sur `cookie.name == "refresh_token"` (pas tous les cookies).
- **Envoi** : uniquement sur les requetes vers `/v1/auth/refresh`. Le cookie est reconstruit
  via `Cookie.Builder()` avec les flags `httpOnly()` et `secure()`.
- **Echec refresh** : si `POST /v1/auth/refresh` retourne un code non-2xx,
  `TokenAuthenticator.handleLogout()` efface `TokenHolder`, `AppDatabase.clearAllTables()`,
  `EncryptedDataStore.clearAll()`, puis notifie `SessionManager.notifyForcedLogout()`.

### Token CSRF

- **Stockage** : cache `@Volatile` en memoire dans `CsrfInterceptor` + persistance
  `EncryptedDataStore` (cle `csrf_token`).
- **Fetch** : `GET {baseUrl}/csrf-token` via le `@Named("bare")` OkHttpClient (5s connect,
  5s read, pas d'intercepteurs). Parse la reponse JSON `{"csrf_token":"..."}`.
- **Injection** : header `X-CSRF-Token` + cookie `csrf_token` sur chaque `POST/PUT/DELETE/PATCH`.
- **Paths exempts** : `/v1/auth/refresh`, `/v1/auth/login`, `/v1/auth/csrf-token`.
- **Invalidation** : sur reponse 403, le cache est vide, un nouveau fetch est lance, la
  requete est rejouee une seule fois.
- **Pre-fetch** : `CsrfInterceptor.preFetch()` est appele apres le login pour eviter la
  contention `runBlocking` lors des premieres requetes POST paralleles.
- **Timeout** : 10 secondes (`CSRF_TIMEOUT_MS`) par operation `runBlocking`. Si le timeout
  est atteint, la requete est envoyee sans header CSRF (le serveur retournera 403).

### Token WebSocket

- Obtenu via `POST /v1/auth/ws-token`. Token distinct de l'access token.
- Utilise pour la connexion au WebSocket prive `wss://vps/v1/ws/private`.
- TTL defini par le serveur. Fetch avant chaque connexion WS.

---

## 4. Chaine d'intercepteurs OkHttp

### Client principal (VPS)

L'ordre est defini dans `NetworkModule.provideMainOkHttpClient()` :

```
1. TimeoutInterceptor        -> applique des timeouts par endpoint (FAST/MEDIUM/STANDARD/SLOW)
2. UpgradeRequiredInterceptor -> detecte HTTP 426, notifie SessionManager
3. CsrfInterceptor           -> injecte X-CSRF-Token + cookie csrf_token sur POST/PUT/DELETE/PATCH
4. VpnRequiredInterceptor    -> bloque si VpnState != Connected (leve VpnNotConnectedException)
5. AuthInterceptor           -> injecte Authorization: Bearer + X-App-Version
6. TokenAuthenticator         -> (Authenticator OkHttp) refresh sur 401, pattern Mutex+Deferred
7. HttpLoggingInterceptor    -> BODY en debug, NONE en release, headers sensibles masques
```

**TimeoutInterceptor** applique des profils de timeout par endpoint :

| Profil    | Connect | Read   | Write  | Endpoints                                  |
|-----------|---------|--------|--------|--------------------------------------------|
| FAST      | 5s      | 5s     | 5s     | `/v1/market-data/*`                        |
| MEDIUM    | 10s     | 10s    | 10s    | `/v1/portfolios*`                          |
| STANDARD  | 15s     | 15s    | 15s    | `/v1/auth/*`, `/v1/notifications/*`, `/csrf-token` |
| SLOW      | 30s     | 30s    | 30s    | `/v1/edge-control/*`, `/v1/edge/*`         |

**UpgradeRequiredInterceptor** laisse passer la reponse 426 (le body n'est pas consomme) et
notifie `SessionManager.notifyUpgradeRequired()`. Place avant CSRF pour intercepter toute
reponse 426 independamment de l'etat du token CSRF.

**CsrfInterceptor** bufferise le request body via `ReplayableRequestBody` (snapshot `ByteString`)
pour pouvoir rejouer la requete sur retry 403 sans consommer le body original one-shot.

**VpnRequiredInterceptor** bypass complet en `DEV_MODE` (`BuildConfig.DEV_MODE`, toujours
`false` en release). Endpoints exempts : `/v1/auth/login`, `/v1/auth/refresh`,
`/v1/auth/2fa/verify`, `/csrf-token`.

**AuthInterceptor** retourne une reponse 401 synthetique (sans appel reseau) si le token est
absent ou si le Keystore est corrompu. Trois cas via `SecureReadResult` :
- `Found` : injecte le token.
- `NotFound` : logout force via `SessionManager.notifyForcedLogout()`.
- `Corrupted` : notification via `SessionManager.notifyKeystoreCorruption()`.

### Client bare (CSRF fetch)

`@Named("bare")` OkHttpClient : connectTimeout 5s, readTimeout 5s, certificate pinning
identique au client principal, aucun intercepteur. Utilise exclusivement par `CsrfInterceptor`
pour `GET /csrf-token` afin d'eviter la dependance circulaire.

### Client LAN (pairing/maintenance)

`@Named("lan")` OkHttpClient : uniquement `VpnRequiredInterceptor` (pas de CSRF, pas d'Auth,
pas de certificate pinning). ConnectTimeout 10s, readTimeout 10s. La validation
`isLocalNetwork()` est effectuee dans `PairingRepositoryImpl` avant chaque appel.

---

## 5. Certificate pinning

### Strategie : Root CA pinning

L'app pinne le SPKI hash SHA-256 de la **Root CA Caddy**, pas le certificat serveur leaf.
OkHttp verifie le hash contre toute la chaine TLS, donc la Root CA match automatiquement.

**Avantage** : les renouvellements automatiques du cert leaf par Caddy (environ tous les 2 mois)
sont transparents. La Root CA est valide environ 10 ans.

### Implementation

`CertificatePinnerProvider` (`security/CertificatePinner.kt`) construit un `CertificatePinner`
OkHttp avec deux pins :
- `BuildConfig.CERT_PIN_SHA256` — pin principal
- `BuildConfig.CERT_PIN_SHA256_BACKUP` — pin de backup

Les valeurs proviennent de `local.properties` via `BuildConfig` (jamais hardcodees dans le code).

En `DEV_MODE`, `buildCertificatePinner()` retourne `null` pour permettre les tests sur backend
local. `DEV_MODE` est toujours `false` en release.

Le pinning est applique a la fois sur le client principal et le client bare. Il n'est **pas**
gere par `network_security_config.xml` (les valeurs `local.properties` ne peuvent pas etre
injectees dans des XML Android au build time).

### Hostname

Le hostname cible est extrait de `BuildConfig.VPS_BASE_URL` dans `NetworkModule` :
```
"https://10.42.0.1:443" -> "10.42.0.1"
```

---

## 6. Verrou biometrique

### Double mecanisme (decision architecture B)

L'implementation combine deux mecanismes independants et complementaires :

| Mecanisme | Composant | Role |
|-----------|-----------|------|
| Keystore auth validity | `KeystoreManager` | Invalide la cle AES-256-GCM 5 min apres la derniere auth biometrique. Gere par Android. |
| Timer d'inactivite | `MainActivity` (`dispatchTouchEvent`) | Reinitialise a chaque interaction ecran. Declenche l'overlay biometrique apres 5 min sans interaction. |

### KeystoreManager (`security/KeystoreManager.kt`)

- Cle AES-256-GCM dans Android Keystore, alias `trading_platform_main_key`.
- `setUserAuthenticationRequired(true)` + `setUserAuthenticationValidityDurationSeconds(300)`.
- `initCipher()` leve `KeyPermanentlyInvalidatedException` si la cle est invalidee.
- `regenerateKey()` supprime l'ancienne cle et en genere une nouvelle.

### BiometricManager (`security/BiometricManager.kt`)

- Verifie la disponibilite via `AndroidBiometricManager.canAuthenticate(BIOMETRIC_STRONG)`.
- Avant d'afficher le prompt, appelle `keystoreManager.initCipher()` pour detecter une cle
  invalidee. Si `KeyPermanentlyInvalidatedException` est levee : regenere la cle et appelle le
  callback `onKeyInvalidated()` (l'utilisateur doit se re-authentifier).
- Prompt configure avec `BIOMETRIC_STRONG` uniquement (pas de fallback PIN/pattern).

### BiometricLockManager (`security/BiometricLockManager.kt`)

- `@Singleton` partage entre `MainActivity` (qui lock) et l'UI (qui observe).
- Expose `isLocked: StateFlow<Boolean>` immutable.
- `lock()` / `unlock()` modifient l'etat.

### Widgets

Les widgets Glance n'ont pas de verrou biometrique. Ils lisent des donnees Room en cache via
`WidgetUpdateWorker` (WorkManager periodique). Le `WireGuardVpnService` reste actif pendant le
verrou (foreground service independant).

---

## 7. Detection root

### RootDetector (`security/RootDetector.kt`)

- `@Singleton`, injecte par Hilt avec `@ApplicationContext`.
- `isRooted()` : appelle `RootBeer(context).isRooted`.
- En cas d'exception dans RootBeer, retourne `false` (fail-open — ne pas bloquer l'app sur un
  faux positif).
- Couche de defense en profondeur uniquement — bypassable avec Magisk/Zygisk.

### Play Integrity API (non implemente)

Pour les distributions via le Play Store, Play Integrity API fournit une attestation serveur
non bypassable cote client. Le VPS pourrait rejeter les sessions dont l'attestation echoue.
Pour les distributions en sideload (VPN-only), cette API n'est pas disponible.

---

## 8. Securite LAN (pairing et maintenance)

### Validation d'adresse IP

`isLocalNetwork()` (`security/NetworkUtils.kt`) valide qu'une IP est RFC-1918 avant tout appel
LAN :

1. Verifie le format via regex IPv4 (bloque les hostnames — protection contre le DNS rebinding).
2. Verifie que chaque octet est <= 255.
3. Utilise `InetAddress.getByName(ip)` (pas de DNS lookup sur une IP literale).
4. Verifie `isSiteLocalAddress || isLinkLocalAddress || isLoopbackAddress`.

Retourne `false` en cas d'exception (fail-safe).

### Chiffrement des payloads LAN (crypto_box_seal)

`SealedBoxHelper` (`security/SealedBoxHelper.kt`) wrappe libsodium via lazysodium-android :

- `seal(plaintext, recipientPublicKey)` : chiffrement asymetrique anonyme avec la cle publique
  Curve25519 du Radxa (= sa wg_pubkey, 32 bytes).
- Seul le Radxa peut dechiffrer avec sa cle privee (cote Python : PyNaCl).
- Taille output : `plaintext.size + Box.SEALBYTES`.
- Validation : `require(recipientPublicKey.size == Box.PUBLICKEYBYTES)`.

### HTTP cleartext — decision architecture C

Le `network_security_config.xml` bloque le cleartext par defaut et l'autorise uniquement pour
les prefixes LAN (`10.0.0.0`, `172.16.0.0`, `192.168.0.0`) via `<domain-config>`.

Defense en profondeur pour les appels LAN :
1. `network_security_config.xml` : cleartext restreint aux prefixes LAN.
2. `VpnRequiredInterceptor` : le VPN doit etre actif (meme pour le LAN).
3. `isLocalNetwork()` : validation RFC-1918 dans le Repository avant chaque appel.
4. `SealedBoxHelper.seal()` : les secrets (`session_pin`, `local_token`) sont chiffres dans le
   payload HTTP.

### Client OkHttp LAN

Le `@Named("lan")` OkHttpClient dans `NetworkModule` n'a que le `VpnRequiredInterceptor` :
pas de CSRF, pas d'Auth, pas de certificate pinning (HTTP non chiffre, LAN local uniquement).

---

## 9. EncryptedDataStore et corruption Keystore

### Implementation (`data/local/datastore/EncryptedDataStore.kt`)

- Utilise `EncryptedSharedPreferences` avec `MasterKey.KeyScheme.AES256_GCM`.
- Chiffrement cles : `AES256_SIV`. Chiffrement valeurs : `AES256_GCM`.
- Nom du fichier : `"trading_secure_prefs"`.
- Initialisation lazy : si `MasterKey` ou `EncryptedSharedPreferences` echoue
  (`GeneralSecurityException` ou `IOException`), `sharedPreferences` est `null` et toutes
  les operations retournent `null` / no-op.

### Gestion de la corruption

Chaque methode de lecture wrappe l'acces dans un try/catch :
- `IOException` : fichier corrompu.
- `GeneralSecurityException` : Keystore invalide (reboot, suppression biometrie, reset device).

`readStringSafe()` retourne un `SecureReadResult<T>` :

```kotlin
sealed interface SecureReadResult<out T> {
    data class Found<T>(val value: T)
    data object NotFound
    data class Corrupted(val cause: Throwable)
}
```

Extensions disponibles : `valueOrNull()` (compatibilite backward), `isCorrupted()`.

### Ecritures critiques

Les cles suivantes utilisent `commit = true` (ecriture synchrone) pour eviter la perte
en cas de kill du process entre `apply()` et la persistance asynchrone :

- `auth_access_token`
- `wg_private_key`, `wg_config`, `wg_endpoint`, `wg_server_pubkey`, `wg_tunnel_ip`, `wg_dns`
- `cookie_refresh_token` (via `saveCookie()`)
- `local_token_{deviceId}` (via `writeLocalToken()`)

### Cles stockees

| Cle | Type | Contenu |
|-----|------|---------|
| `auth_access_token` | String | JWT access token (Bearer) |
| `auth_user_id` | Long | `user.id` |
| `auth_is_admin` | Boolean | `user.is_admin` |
| `auth_portfolio_id` | String | `portfolioId` |
| `wg_private_key` | String | Cle privee WireGuard (base64) |
| `wg_config` | String | Config WireGuard complete (JSON) |
| `wg_endpoint` | String | Endpoint WireGuard du VPS |
| `wg_server_pubkey` | String | Cle publique WireGuard du VPS |
| `wg_tunnel_ip` | String | IP tunnel attribuee |
| `wg_dns` | String | DNS du tunnel |
| `setup_completed` | Boolean | `true` apres onboarding QR + premier login |
| `csrf_token` | String | Token CSRF en cache |
| `pending_fcm_token` | String | Token FCM en attente d'enregistrement |
| `pending_fcm_fingerprint` | String | Fingerprint FCM pour retry |
| `cookie_refresh_token` | String | Cookie httpOnly refresh_token |
| `local_token_{deviceId}` | String | Token LAN par device Radxa |

---

## 10. Nettoyage au logout

### Logout volontaire (`LogoutUseCase`)

```
1. authRepository.logout()        -> POST /v1/auth/logout (best-effort)
2. appDatabase.clearAllTables()   -> efface Room (positions, alerts, quotes, devices, watchlist)
3. dataStore.clearAll()           -> efface tous les tokens, cookies, is_admin, portfolio_id, config WG
```

`clearAllTables()` est wrappe dans un try/catch — un echec Room ne bloque pas le logout.

### Logout force (`TokenAuthenticator.handleLogout()`)

Declenche quand `POST /v1/auth/refresh` retourne un code non-2xx :

```
1. tokenHolder.clear()            -> efface le cache memoire du JWT
2. appDatabase.clearAllTables()   -> efface Room (try/catch)
3. dataStore.clearAll()           -> efface tous les tokens, cookies, config
4. sessionManager.notifyForcedLogout() -> SharedFlow -> AppNavViewModel -> navigation LoginScreen
```

### Corruption Keystore (`AuthInterceptor`)

Si `readStringSafe()` retourne `SecureReadResult.Corrupted` :

```
1. sessionManager.notifyKeystoreCorruption() -> SharedFlow distinct -> message explicite UI
2. Reponse 401 synthetique retournee         -> la requete n'est pas envoyee au serveur
```

L'evenement `keystoreCorruptionEvents` est distinct de `forcedLogoutEvents` pour afficher
un message adapte ("Donnees de session corrompues -- reconnexion necessaire").

---

## 11. Controle de version applicative

### Header X-App-Version

`AuthInterceptor` injecte le header `X-App-Version: {BuildConfig.VERSION_CODE}` sur chaque
requete authentifiee.

### Reponse 426 Upgrade Required

`UpgradeRequiredInterceptor` (premiere position dans la chaine, avant CSRF) detecte les
reponses HTTP 426 et appelle `SessionManager.notifyUpgradeRequired()`.

`SessionManager.upgradeRequiredEvents` est un `SharedFlow<Unit>` (replay=0) collecte par
`AppNavViewModel` pour afficher un dialog bloquant non dismissable "Mise a jour requise".

L'intercepteur laisse passer la reponse 426 sans consommer le body pour ne pas perturber
les callers Retrofit en aval.

---

## References

| Sujet | Fichier |
|-------|---------|
| KeystoreManager | `app/src/main/java/.../security/KeystoreManager.kt` |
| BiometricManager | `app/src/main/java/.../security/BiometricManager.kt` |
| BiometricLockManager | `app/src/main/java/.../security/BiometricLockManager.kt` |
| RootDetector | `app/src/main/java/.../security/RootDetector.kt` |
| SealedBoxHelper | `app/src/main/java/.../security/SealedBoxHelper.kt` |
| CertificatePinnerProvider | `app/src/main/java/.../security/CertificatePinner.kt` |
| NetworkUtils (isLocalNetwork) | `app/src/main/java/.../security/NetworkUtils.kt` |
| CsrfInterceptor | `app/src/main/java/.../data/api/interceptor/CsrfInterceptor.kt` |
| VpnRequiredInterceptor | `app/src/main/java/.../data/api/interceptor/VpnRequiredInterceptor.kt` |
| AuthInterceptor | `app/src/main/java/.../data/api/interceptor/AuthInterceptor.kt` |
| TokenAuthenticator | `app/src/main/java/.../data/api/interceptor/TokenAuthenticator.kt` |
| EncryptedCookieJar | `app/src/main/java/.../data/api/interceptor/EncryptedCookieJar.kt` |
| UpgradeRequiredInterceptor | `app/src/main/java/.../data/api/interceptor/UpgradeRequiredInterceptor.kt` |
| TimeoutInterceptor | `app/src/main/java/.../data/api/interceptor/TimeoutInterceptor.kt` |
| EncryptedDataStore | `app/src/main/java/.../data/local/datastore/EncryptedDataStore.kt` |
| SecureReadResult | `app/src/main/java/.../data/local/datastore/SecureReadResult.kt` |
| TokenHolder | `app/src/main/java/.../data/session/TokenHolder.kt` |
| SessionManager | `app/src/main/java/.../data/session/SessionManager.kt` |
| WireGuardManager | `app/src/main/java/.../vpn/WireGuardManager.kt` |
| VpnState | `app/src/main/java/.../vpn/VpnState.kt` |
| NetworkModule | `app/src/main/java/.../di/NetworkModule.kt` |
| LogoutUseCase | `app/src/main/java/.../domain/usecase/auth/LogoutUseCase.kt` |
| network_security_config.xml | `app/src/main/res/xml/network_security_config.xml` |
| Architecture decisions | `docs/architecture-decisions.md` |
