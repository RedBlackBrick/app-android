# Architecture Cible -- app-android

Spécifique a l'application Android Trading Platform. Ce document decrit l'etat final vise, pas l'etat actuel.

Pour l'architecture globale (VPS, Radxa, modele de donnees, flux reseau) : voir `/home/thomas/Codes/ARCHITECTURE-CIBLE.md`.

---

## 1. Onboarding Mobile via QR

### Principe

L'utilisateur, connecte au panel web sur son PC, va dans Parametres > Lier mon mobile. Le VPS genere une paire de cles WireGuard pour le mobile, attribue une IP tunnel (10.42.0.x), et affiche un QR Code a l'ecran du PC.

L'App Android scanne ce QR, stocke la cle privee WireGuard dans le Keystore materiel (via `EncryptedDataStore` / `KeystoreManager`), configure le tunnel, puis enchaine avec le login classique (email + mot de passe + 2FA optionnel).

### Format du QR

```json
{
  "wg_private_key": "base64...",
  "wg_public_key_server": "base64...",
  "endpoint": "vps.example.com:51820",
  "tunnel_ip": "10.42.0.101/32",
  "dns": "10.42.0.1"
}
```

### Flux

```
App Android                    VPS
  |                             |
  |  1. Scanne QR sur ecran PC  |
  |  (ParseSetupQrUseCase)      |
  |                             |
  |  2. Stocke wg_private_key   |
  |  dans EncryptedDataStore     |
  |  (DataStoreKeys.WG_PRIVATE_KEY) |
  |                             |
  |  3. Construit WireGuardConfig|
  |  via WireGuardManager        |
  |  .configureFromSetupQr()     |
  |                             |
  |  4. Active tunnel WireGuard  |
  |  WireGuardManager.connect()  |
  |----------------------------→|
  |  Handshake reussi            |
  |                             |
  |  5. VPS detecte handshake   |
  |  → vpn_peers.is_active=true  |
  |                             |
  |  6. Ecran login classique    |
  |  (LoginScreen existant)      |
  |----------------------------→|
  |  email + mdp + 2FA          |
  |                             |
  |  7. JWT + refresh cookie     |
  |←----------------------------|
```

### Securite

- La cle privee WireGuard transite uniquement via l'ecran du PC (canal visuel).
- Le VPS purge la cle privee de sa memoire immediatement apres generation du QR.
- Le QR a un TTL de 5 minutes. Apres expiration, le vpn_peer est supprime si non active.
- L'App stocke la cle privee dans `EncryptedDataStore` (Keystore AES-256-GCM materiel).
- Le certificate pinning SHA-256 du VPS est verifie des la premiere connexion HTTPS.

### Composants impliques

| Composant | Role |
|-----------|------|
| `ParseSetupQrUseCase` (nouveau) | Parse le QR du panel web, valide les champs |
| `WireGuardManager.configureFromSetupQr()` (nouvelle methode) | Construit `WireGuardConfig` depuis les donnees QR |
| `EncryptedDataStore` + `DataStoreKeys` | Stockage cle privee WG + config endpoint/tunnel_ip/dns |
| `SetupScreen` (nouveau) | UI scan QR initial, precede le `LoginScreen` |
| `AppNavGraph` | Nouvelle route `setup`, startDestination conditionnel |

---

## 2. Pairing Radxa — Chiffrement libsodium

### Principe

Le pairing Radxa est le flux existant (4 screens, `PairingViewModel` state machine) avec une modification majeure : le payload envoye en HTTP LAN vers la Radxa est desormais chiffre avec `crypto_box_seal(radxa_wg_pubkey)` via libsodium.

Le flux VPS reste identique. Seul l'envoi LAN (etape 6 du flux global) change : au lieu d'envoyer du JSON en clair, l'App chiffre `{session_id, session_pin, local_token}` avec la cle publique Curve25519 du Radxa (obtenue via le QR e-ink).

### Chiffrement

```
Donnees sensibles (JSON) → sérialisation bytes
    → crypto_box_seal(bytes, radxa_wg_pubkey)
    → bytes chiffres
    → POST http://radxa_ip:8099/pin
       Content-Type: application/octet-stream
       Body: bytes chiffres (opaque)
```

Seul le Radxa peut dechiffrer avec sa `wg_private_key` Curve25519 (via PyNaCl `nacl.public.SealedBox`).

### Changements par rapport a l'existant

| Element actuel | Element cible |
|----------------|---------------|
| `SendPinToDeviceUseCase` envoie session_id + session_pin | Envoie session_id + session_pin + local_token, le tout chiffre |
| `PairingRepositoryImpl.sendPin()` envoie `Map<String, String>` JSON | Envoie `ByteArray` (octet-stream) chiffre par `SealedBoxHelper` |
| `PairingLanApi.sendPin()` accepte `@Body Map<String, String>` | Accepte `@Body RequestBody` (octet-stream) |
| `PairingSession` contient sessionId, sessionPin, deviceWgIp | Contient aussi `localToken` (recu du VPS a la creation de session) |
| Pas de chiffrement LAN | `SealedBoxHelper.seal(payload, pubkey)` dans `security/` |

### `local_token`

- Genere par le VPS lors de la creation de session (etape 3 du flux global).
- Transmis a l'App dans la reponse `POST /v1/pairing/initiate`.
- L'App le stocke dans `EncryptedDataStore` (cle `local_token_{device_id}`) apres confirmation du pairing.
- L'App le transmet au Radxa dans le payload chiffre du sendPin.
- Le Radxa le stocke dans `agent.conf` (permissions 600).
- Hash SHA-256 stocke dans `edge_devices.local_token_hash` cote VPS.

### Composants impliques

| Composant | Role |
|-----------|------|
| `SealedBoxHelper` (nouveau, `security/`) | Wrapper libsodium `crypto_box_seal` / `crypto_box_seal_open` |
| `SendPinToDeviceUseCase` (modifie) | Inclut local_token dans le payload, delegue le chiffrement au repository |
| `PairingRepositoryImpl` (modifie) | Chiffre le payload avec `SealedBoxHelper` avant envoi HTTP |
| `PairingLanApi` (modifie) | POST avec `RequestBody` (octet-stream) au lieu de `Map` |
| `PairingRepository` (interface modifiee) | Signature `sendPin()` inclut `localToken` et `wgPubkey` |
| `PairingSession` (modifie) | Ajoute champ `localToken` |
| `ParseVpsQrUseCase` (modifie) | Parse le nouveau format QR VPS qui inclut local_token |

---

## 3. Roue de Secours LAN (Maintenance Offline)

### Principe

Quand le Radxa perd sa connexion VPN ou Internet, l'utilisateur peut intervenir localement via l'App Android. L'App detecte que le ping VPN echoue et propose le mode "Depannage Local".

Toutes les requetes LAN de la roue de secours sont chiffrees avec `crypto_box_seal(radxa_wg_pubkey)` et authentifiees avec le `local_token` stocke dans `EncryptedDataStore`.

### Endpoints Radxa (port 8099, LAN uniquement)

| Methode | Endpoint | Auth | Description |
|---------|----------|------|-------------|
| GET | `/identity` | Non | device_id, wg_pubkey, IP locale (public) |
| GET | `/status` | Non | Etat WireGuard, WiFi, uptime, derniere erreur (public, pas de donnee sensible) |
| POST | `/pin` | Chiffre (pas de local_token) | Reception du package d'appairage |
| POST | `/command` | Chiffre + local_token | Actions de maintenance |

### Format des requetes chiffrees

```
POST http://radxa_ip:8099/command
Content-Type: application/octet-stream

Body: crypto_box_seal({
  "action": "wifi_configure",
  "local_token": "<token>",
  "params": { "ssid": "...", "password": "..." }
}, radxa_wg_pubkey)
```

### Actions de maintenance

| Action | Description |
|--------|-------------|
| `wifi_configure` | Configurer un nouveau reseau WiFi |
| `wireguard_restart` | Redemarrer le service WireGuard |
| `logs` | Dernieres lignes de logs (journalctl filtre) |
| `reboot` | Redemarrage physique du Radxa |

### Detection offline

1. L'App detecte que le Radxa ne repond plus via VPN (ping `10.42.0.x` echoue ou timeout heartbeat).
2. L'App propose le mode "Depannage Local" via un banner dans `DeviceDetailScreen`.
3. L'utilisateur entre l'IP affichee sur l'ecran e-ink du Radxa ou scanne le QR e-ink.
4. L'App valide `isLocalNetwork(ip)` avant toute requete.

### Composants

| Composant | Role |
|-----------|------|
| `SendLocalCommandUseCase` (nouveau) | Chiffre la commande + local_token avec `SealedBoxHelper`, delegue au repository |
| `GetLocalStatusUseCase` (nouveau) | Recupere le statut chiffre depuis le Radxa |
| `LocalMaintenanceRepository` (nouveau, interface) | Interface dans `domain/repository/` |
| `LocalMaintenanceRepositoryImpl` (nouveau) | Implementation dans `data/repository/`, utilise le client OkHttp LAN |
| `LocalMaintenanceApi` (nouveau) | Interface Retrofit : POST /command, GET /status, GET /identity |
| `LocalMaintenanceScreen` (nouveau) | UI : WiFi config, WG restart, logs, reboot |
| `LocalMaintenanceViewModel` (nouveau) | Gere l'etat de la maintenance locale |
| `Screen.LocalMaintenance` (nouvelle route) | Navigation depuis `DeviceDetailScreen` |

---

## 4. Widgets Offline-First

### Principe inchange

Les widgets lisent uniquement Room. `WidgetUpdateWorker` (WorkManager, 15 min en Doze) ouvre le tunnel VPN, fetch l'API VPS, stocke dans Room, met a jour `synced_at`. Chaque widget affiche `synced_at`. Si le reseau est coupe, les donnees datees restent affichees.

### Changements lies a la migration

Aucun changement dans le fonctionnement des widgets. La seule modification possible est l'ajout d'un widget `DeviceStatusWidget` pour afficher l'etat LAN/VPN du Radxa (admin uniquement), mais ce n'est pas dans le scope de cette migration.

---

## 5. Chaine d'intercepteurs OkHttp (inchangee)

```
Requete sortante (client principal VPS)
  |
  +- 1. CsrfInterceptor         → injecte X-CSRF-Token
  +- 2. VpnRequiredInterceptor  → bloque si VPN deconnecte
  +- 3. AuthInterceptor          → injecte Authorization: Bearer <JWT>
  +- 4. CertificatePinner        → verifie SHA-256 du cert serveur (release)
  +- 5. TokenAuthenticator       → gere 401 → refresh transparent
  +- 6. HttpLoggingInterceptor   → debug uniquement, tokens [REDACTED]
```

Les intercepteurs ne changent pas. Le client LAN (`@Named("lan")`) n'a toujours aucun intercepteur VPS.

---

## 6. Trois clients OkHttp

| Client | Usage | Intercepteurs | Pinning | Chiffrement payload |
|--------|-------|---------------|---------|---------------------|
| **Main** (par defaut) | API VPS | CSRF + VPN + Auth + TokenAuth + Logging | Oui (SHA-256) | Non (TLS via VPN) |
| **Bare** (`@Named("bare")`) | Fetch CSRF uniquement | Aucun | Oui (SHA-256) | Non |
| **LAN** (`@Named("lan")`) | Pairing + roue de secours | Aucun | Non (HTTP local) | Oui (libsodium `crypto_box_seal`) |

Le client LAN reste identique au niveau OkHttp. Le chiffrement libsodium est applique au niveau Repository avant construction du `RequestBody`, pas dans un intercepteur OkHttp.

---

## 7. Certificate Pinning (Root CA)

```kotlin
// CertificatePinnerProvider.buildCertificatePinner()
// BuildConfig.CERT_PIN_SHA256 = SPKI hash de la Root CA Caddy (pas du cert leaf)
// BuildConfig.CERT_PIN_SHA256_BACKUP = backup (meme hash ou future CA)
// Desactive en DEV_MODE pour les tests locaux
// Generer les hashes : cd trading-platform2 && ./scripts/extract_caddy_ca.sh
```

Root CA pinning : OkHttp verifie le SPKI hash contre toute la chaine TLS. Le cert leaf renouvele par Caddy (~2 mois) est transparent — pas de MAJ client necessaire. Root CA valide ~10 ans.

Le pinning s'applique aux clients Main et Bare. Le client LAN n'a pas de pinning (HTTP non chiffre, payload chiffre libsodium).

---

## 8. Stockage Securise

### EncryptedDataStore — cles existantes

| Cle | Type | Usage |
|-----|------|-------|
| `auth_access_token` | String | JWT access token |
| `auth_user_id` | Long | ID utilisateur |
| `auth_is_admin` | Boolean | Flag admin |
| `auth_portfolio_id` | String | ID du portfolio |
| `wg_private_key` | String | Cle privee WireGuard |
| `wg_config` | String | Config WireGuard serialisee |
| `cookie_{name}` | String | Cookies httpOnly (refresh token) |

### EncryptedDataStore — nouvelles cles

| Cle | Type | Usage |
|-----|------|-------|
| `wg_endpoint` | String | Endpoint WireGuard du VPS (ex: `vps.example.com:51820`) |
| `wg_server_pubkey` | String | Cle publique WireGuard du VPS |
| `wg_tunnel_ip` | String | IP tunnel attribuee (ex: `10.42.0.101/32`) |
| `wg_dns` | String | DNS du tunnel (ex: `10.42.0.1`) |
| `setup_completed` | Boolean | true apres onboarding QR + premier login reussi |
| `local_token_{device_id}` | String | local_token par device Radxa (roue de secours) |

---

## 9. Securite — Libsodium

### Bibliotheque

**lazysodium-android** + **libsodium-jni** : bindings Android pour libsodium. Fournit `crypto_box_seal` (chiffrement asymetrique avec cle publique Curve25519, anonyme).

### SealedBoxHelper

Classe utilitaire dans `security/` qui encapsule les appels libsodium :

```kotlin
// security/SealedBoxHelper.kt
class SealedBoxHelper @Inject constructor() {
    fun seal(plaintext: ByteArray, recipientPublicKey: ByteArray): ByteArray
    fun open(ciphertext: ByteArray, recipientKeyPair: KeyPair): ByteArray
}
```

- `seal()` : utilise cote App Android pour chiffrer les payloads LAN avant envoi au Radxa.
- `open()` : utilise cote Radxa (Python/PyNaCl), pas cote App Android.

### Ou le chiffrement s'applique

| Flux | Donnees chiffrees | Cle publique utilisee |
|------|-------------------|-----------------------|
| Pairing — envoi PIN | `{session_id, session_pin, local_token}` | `radxa_wg_pubkey` (du QR e-ink) |
| Roue de secours — commande | `{action, local_token, params}` | `radxa_wg_pubkey` (stocke depuis le pairing) |
| Roue de secours — status | Reponse du Radxa (optionnel, si le Radxa chiffre la reponse) | N/A cote App |

---

## 10. Navigation — Nouvelles routes

### Routes ajoutees

| Route | Screen | Condition |
|-------|--------|-----------|
| `setup` | `SetupScreen` | Affiche si `setup_completed == false` |
| `local-maintenance/{deviceId}` | `LocalMaintenanceScreen` | Admin uniquement, depuis `DeviceDetailScreen` |

### Flux de navigation mis a jour

```
Demarrage App
  |
  +- setup_completed == false → SetupScreen (scan QR) → WG connect → LoginScreen
  +- setup_completed == true, access_token absent → LoginScreen
  +- setup_completed == true, access_token present → Dashboard
```

---

## 11. Mode Developpement Local (DEV_MODE)

### Principe

En mode debug, l'App peut se connecter directement a une instance trading-platform tournant sur le PC de developpement, sans tunnel WireGuard.

### Configuration

```properties
# local.properties
VPS_BASE_URL=http://192.168.1.X:8000
DEV_MODE=true
```

`DEV_MODE` est injecte dans `BuildConfig` via `build.gradle.kts` :

```kotlin
buildTypes {
    debug {
        buildConfigField("boolean", "DEV_MODE", project.findProperty("DEV_MODE")?.toString() ?: "false")
    }
    release {
        buildConfigField("boolean", "DEV_MODE", "false")  // jamais en release
    }
}
```

### Effets

| Composant | Production | DEV_MODE=true |
|-----------|-----------|---------------|
| `VpnRequiredInterceptor` | Bloque si VPN off | Skip le check |
| `CertificatePinner` | SHA-256 actif | Desactive (HTTP local) |
| `VPS_BASE_URL` | `https://10.42.0.1:443` | `http://192.168.1.X:8000` |

### Implementation

`VpnRequiredInterceptor` :

```kotlin
override fun intercept(chain: Interceptor.Chain): Response {
    if (BuildConfig.DEV_MODE) return chain.proceed(chain.request())
    // ... check VPN normal
}
```

`CertificatePinnerProvider` :

```kotlin
fun buildCertificatePinner(): CertificatePinner? {
    if (BuildConfig.DEV_MODE) return null
    // ... pinning normal
}
```

### Contraintes

- `DEV_MODE` est toujours `false` dans le build variant `release` — impossible a activer accidentellement en production.
- L'authentification JWT/CSRF reste active — seul le transport reseau change.
- Le pairing LAN fonctionne normalement en DEV_MODE (le Radxa est deja accessible en LAN).

---

## 12. Resume des composants

### Nouveaux fichiers

| Chemin | Description |
|--------|-------------|
| `security/SealedBoxHelper.kt` | Wrapper libsodium crypto_box_seal |
| `domain/usecase/pairing/ParseSetupQrUseCase.kt` | Parse QR d'onboarding mobile |
| `domain/usecase/maintenance/SendLocalCommandUseCase.kt` | Chiffre + envoie commande LAN |
| `domain/usecase/maintenance/GetLocalStatusUseCase.kt` | Recupere statut chiffre LAN |
| `domain/repository/LocalMaintenanceRepository.kt` | Interface repository maintenance LAN |
| `data/repository/LocalMaintenanceRepositoryImpl.kt` | Implementation maintenance LAN |
| `data/api/LocalMaintenanceApi.kt` | Interface Retrofit endpoints Radxa maintenance |
| `ui/screens/setup/SetupScreen.kt` | UI scan QR onboarding |
| `ui/screens/setup/SetupViewModel.kt` | ViewModel onboarding |
| `ui/screens/maintenance/LocalMaintenanceScreen.kt` | UI maintenance LAN |
| `ui/screens/maintenance/LocalMaintenanceViewModel.kt` | ViewModel maintenance LAN |

### Fichiers modifies

| Chemin | Modification |
|--------|-------------|
| `gradle/libs.versions.toml` | Ajout lazysodium-android, libsodium-jni |
| `app/build.gradle.kts` | Ajout dependances lazysodium |
| `domain/usecase/pairing/SendPinToDeviceUseCase.kt` | Ajout local_token dans les parametres |
| `domain/repository/PairingRepository.kt` | Signature sendPin modifiee (+ localToken, wgPubkey) |
| `data/repository/PairingRepositoryImpl.kt` | Chiffrement SealedBoxHelper, body octet-stream |
| `data/api/PairingLanApi.kt` | sendPin accepte RequestBody au lieu de Map |
| `domain/model/PairingSession.kt` | Ajout champ localToken |
| `domain/usecase/pairing/ParseVpsQrUseCase.kt` | Parse local_token depuis le QR VPS |
| `vpn/WireGuardManager.kt` | Nouvelle methode configureFromSetupQr() |
| `data/local/datastore/EncryptedDataStore.kt` | Nouvelles cles DataStoreKeys |
| `ui/navigation/Screen.kt` | Nouvelles routes Setup, LocalMaintenance |
| `ui/navigation/AppNavGraph.kt` | Nouvelles destinations, startDestination conditionnel |
| `ui/screens/pairing/PairingViewModel.kt` | Passe local_token et wgPubkey au SendPinToDeviceUseCase |
| `di/NetworkModule.kt` | Provide LocalMaintenanceApi via LAN Retrofit |
| `di/RepositoryModule.kt` | Bind LocalMaintenanceRepository |
| `di/SecurityModule.kt` | Provide SealedBoxHelper |
| `CLAUDE.md` | Reflet des nouveaux UseCases, libsodium, onboarding QR |
| `docs/architecture-decisions.md` | ADR libsodium LAN, ADR onboarding QR |
| `docs/api-contracts.md` | Endpoints onboarding, format QR, requetes LAN chiffrees |
