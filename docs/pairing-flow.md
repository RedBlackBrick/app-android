# Flux de pairing — Android

Documentation du flux d'appairage entre l'interface web admin (VPS), l'application Android
et le device Radxa. Basee sur le code source du module `ui/screens/pairing/`, des UseCases
`domain/usecase/pairing/` et du `PairingRepositoryImpl`.

---

## Table des matieres

1. [Diagramme de sequence tri-systeme](#1-diagramme-de-sequence-tri-systeme)
2. [Formats QR](#2-formats-qr)
3. [Machine a etats PairingStep](#3-machine-a-etats-pairingstep)
4. [Flux des ecrans](#4-flux-des-ecrans)
5. [UseCases impliques](#5-usecases-impliques)
6. [Couche Repository et API LAN](#6-couche-repository-et-api-lan)
7. [Securite](#7-securite)
8. [Gestion des erreurs](#8-gestion-des-erreurs)
9. [Contraintes](#9-contraintes)
10. [Persistance post-pairing](#10-persistance-post-pairing)

---

## 1. Diagramme de sequence tri-systeme

```
 Admin Web (VPS)          App Android              Radxa (LAN)             VPS Backend
 ───────────────          ───────────              ───────────             ───────────
       │                       │                        │                       │
       │  Cree session pairing │                        │                       │
       │──────────────────────────────────────────────────────────────────────>│
       │                       │                        │    {session_id,       │
       │  Affiche QR VPS       │                        │     session_pin,      │
       │  (interface admin)    │                        │     device_wg_ip,     │
       │                       │                        │     local_token,      │
       │                       │                        │     nonce}            │
       │                       │                        │                       │
       │                       │  Scan QR VPS (camera)  │                       │
       │                       │◄─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─│                       │
       │                       │                        │                       │
       │                       │  Scan QR Radxa (e-ink) │                       │
       │                       │◄──────────────────────>│                       │
       │                       │  pairing://radxa?...   │                       │
       │                       │                        │                       │
       │                       │  POST /pin             │                       │
       │                       │  (crypto_box_seal)     │                       │
       │                       │───────────────────────>│                       │
       │                       │         200 OK         │                       │
       │                       │◄───────────────────────│                       │
       │                       │                        │                       │
       │                       │                        │  Enregistre pair WG   │
       │                       │                        │─────────────────────>│
       │                       │                        │                       │
       │                       │  GET /status (poll 2s) │                       │
       │                       │───────────────────────>│                       │
       │                       │  {"status":"pairing"}  │                       │
       │                       │◄───────────────────────│                       │
       │                       │         ...            │                       │
       │                       │  GET /status           │                       │
       │                       │───────────────────────>│                       │
       │                       │  {"status":"paired"}   │                       │
       │                       │◄───────────────────────│                       │
       │                       │                        │                       │
       │                       │  Sauvegarde locale     │                       │
       │                       │  (EncryptedDataStore)  │                       │
       │                       │                        │                       │
```

Les deux QR (VPS et Radxa) sont scannables dans n'importe quel ordre. L'app attend d'avoir
les deux avant de demarrer l'envoi du PIN.

---

## 2. Formats QR

### QR VPS

Affiche sur l'interface admin web (`Admin > Edge Devices`). Format JSON.

```json
{
  "session_id": "uuid",
  "session_pin": "472938",
  "device_wg_ip": "10.42.0.5",
  "local_token": "hex-256-bit",
  "nonce": "64-char-hex"
}
```

| Champ          | Type   | Description                                                    |
|----------------|--------|----------------------------------------------------------------|
| `session_id`   | String | UUID de la session de pairing, genere par le VPS               |
| `session_pin`  | String | PIN a usage unique (6 chiffres), TTL 120s, invalide apres usage |
| `device_wg_ip` | String | IP WireGuard attribuee au device (affichage confirmation)      |
| `local_token`  | String | Token hex 256 bits pour chiffrement LAN futur                  |
| `nonce`        | String | Nonce hex 64 chars, anti-rejeu                                 |

Parse par `ParseVpsQrUseCase`. Le JSON doit commencer par `{`. Tous les champs sont
obligatoires ; un champ manquant leve `MalformedQrException`.

### QR Radxa

Affiche sur l'ecran e-ink du device Radxa. Format URI.

```
pairing://radxa?id={device_id}&pub={wg_pubkey}&ip={local_ip}&port=8099
```

| Parametre | Type   | Validation                                    | Description                                       |
|-----------|--------|-----------------------------------------------|---------------------------------------------------|
| `id`      | String | Non vide                                      | Identifiant unique du device                       |
| `pub`     | String | 44 caracteres (base64 Curve25519)             | Cle publique WireGuard du device                   |
| `ip`      | String | IPv4 litterale (regex, pas de hostname)        | IP LAN du device                                   |
| `port`    | Int    | Doit etre `8099`                              | Port du serveur de pairing Radxa                   |

Parse par `ScanDeviceQrUseCase`. Validations strictes :
- Scheme : `pairing`
- Host : `radxa`
- IP : regex IPv4 uniquement (bloque les hostnames contre le DNS rebinding)
- Port : doit etre exactement `8099`
- Pubkey : exactement 44 caracteres base64

Le parsing utilise `java.net.URI` (pas `android.net.Uri`) pour rester dans le domaine
pur Kotlin/JVM, testable sans Android runtime.

---

## 3. Machine a etats PairingStep

Definie dans `PairingViewModel.kt` comme `sealed interface PairingStep`.

```
                    ┌─────────────────┐
                    │      Idle       │
                    └────┬───────┬────┘
                VPS QR   │       │   Device QR
                scanne   │       │   scanne
                    ┌────▼──┐ ┌──▼───────┐
                    │ Vps   │ │ Device   │
                    │Scanned│ │ Scanned  │
                    └───┬───┘ └────┬─────┘
              Device QR │          │ VPS QR
              scanne    │          │ scanne
                    ┌───▼──────────▼───┐
                    │   BothScanned    │
                    └────────┬─────────┘
                startPairing()│
                    ┌────────▼─────────┐
                    │   SendingPin     │
                    └──┬───────────┬───┘
              succes   │           │ echec
                    ┌──▼───────┐   │
                    │ Waiting  │   │
                    │Confirm.  │   │
                    └──┬───┬───┘   │
            PAIRED     │   │FAILED │
                       │   │ /tmout│
                    ┌──▼┐ ┌▼──────▼──┐
                    │ S │ │  Error   │
                    └───┘ └──────────┘

  S = Success
```

### Etats

| Etat                  | Description                                                  |
|-----------------------|--------------------------------------------------------------|
| `Idle`                | Etat initial, aucun QR scanne                                |
| `VpsScanned(session)` | QR VPS parse, en attente du QR Radxa                         |
| `DeviceScanned(device)` | QR Radxa parse, en attente du QR VPS                       |
| `BothScanned(session, device)` | Les deux QR scannes, pret a lancer le pairing        |
| `SendingPin`          | PIN chiffre en cours d'envoi vers le device LAN              |
| `WaitingConfirmation` | PIN envoye, polling du statut toutes les 2s                  |
| `Success`             | Pairing termine, donnees persistees dans EncryptedDataStore  |
| `Error(message, retryable)` | Echec avec message et indicateur de retry possible    |

### Transitions

| De               | Evenement                     | Vers                |
|------------------|-------------------------------|---------------------|
| `Idle`           | `onVpsQrScanned()` succes     | `VpsScanned`        |
| `Idle`           | `onDeviceQrScanned()` succes  | `DeviceScanned`     |
| `VpsScanned`     | `onDeviceQrScanned()` succes  | `BothScanned`       |
| `DeviceScanned`  | `onVpsQrScanned()` succes     | `BothScanned`       |
| `BothScanned`    | `startPairing()`              | `SendingPin`        |
| `SendingPin`     | sendPin succes                | `WaitingConfirmation` |
| `SendingPin`     | sendPin echec                 | `Error(retryable=false)` |
| `WaitingConfirmation` | poll retourne `PAIRED`   | `Success`           |
| `WaitingConfirmation` | poll retourne `FAILED`   | `Error(retryable=false)` |
| `WaitingConfirmation` | timeout 120s             | `Error(retryable=false)` |
| `*` (scan)       | QR non reconnu                | `Error(retryable=true)` |
| `Error`/`Success` | `retry()` ou `reset()`       | `Idle`              |

---

## 4. Flux des ecrans

Quatre ecrans, navigation geree par `AppNavGraph.kt` dans un nested navigation graph
(`pairing_graph/{source}`). Le `PairingViewModel` est partage entre les quatre ecrans
via `hiltViewModel(navController.getBackStackEntry(PAIRING_GRAPH_ROUTE))`.

### Routes

| Ecran                  | Route                  |
|------------------------|------------------------|
| `ScanVpsQrScreen`      | `pairing/scan-vps`     |
| `ScanDeviceQrScreen`   | `pairing/scan-device`  |
| `PairingProgressScreen`| `pairing/progress`     |
| `PairingDoneScreen`    | `pairing/done`         |

### Enchainement

```
DeviceListScreen ──(bouton "Ajouter")──> ScanVpsQrScreen
                                              │
                            VpsScanned ───────▼
                                        ScanDeviceQrScreen
                                              │
                          BothScanned ────────▼
                                        PairingProgressScreen
                                              │
                         Success/Error ───────▼
                                        PairingDoneScreen
                                              │
                              ┌────────────────┤
                              │                │
                        "Reessayer"        "Terminer"/"Fermer"
                              │                │
                              ▼                ▼
                        ScanVpsQrScreen   DeviceListScreen
                        (reset Idle)      (retour source)
```

La destination de retour depend du parametre `source` passe au graphe de navigation :
- `"devices"` : retour vers `Screen.Devices`
- `"my-devices"` : retour vers `Screen.MyDevices`

### ScanVpsQrScreen

- Affiche le viewfinder camera via `QrScannerView`
- Instruction : "Scannez le QR affiche sur l'interface admin VPS"
- Sous-titre : "Le QR code est disponible dans : Admin > Edge Devices"
- Sur QR detecte : appelle `viewModel.onVpsQrScanned(raw)`
- Navigation via `LaunchedEffect(step)` :
  - `VpsScanned` : navigue vers `ScanDeviceQrScreen`
  - `BothScanned` : navigue directement vers `PairingProgressScreen` (si le QR Device a deja ete scanne)
  - `Error` : affiche `ErrorBanner` en bas de l'ecran
- Bouton retour : appelle `viewModel.reset()` et retourne a la source

### ScanDeviceQrScreen

- Affiche le viewfinder camera via `QrScannerView`
- Instruction : "Scannez le QR affiche sur l'ecran e-ink du device Radxa"
- Sous-titre : "Le QR code apparait sur l'ecran du device au demarrage du pairing"
- Sur QR detecte : appelle `viewModel.onDeviceQrScanned(raw)`
- Navigation via `LaunchedEffect(step)` :
  - `BothScanned` : navigue vers `PairingProgressScreen`
  - `Error` : affiche `ErrorBanner`
- Bouton retour : appelle `viewModel.reset()` et revient en arriere

### PairingProgressScreen

- Demarre automatiquement le pairing via `LaunchedEffect(Unit) { viewModel.startPairing() }`
- `startPairing()` est idempotent : ignore si l'etat n'est pas `BothScanned`
- Affiche un `DeviceContextCard` avec `deviceId` et `localIp:port`
- Indicateur de progression en 3 etapes : "Envoi PIN" / "Confirmation" / "Termine"
- Contenu anime via `AnimatedContent` (slide vertical + fade) :
  - `SendingPin` : `CircularProgressIndicator` + "Envoi du PIN au device..."
  - `WaitingConfirmation` : `LinearProgressIndicator` + "Attente de confirmation..." + "(jusqu'a 2 min)"
- Navigation vers `PairingDoneScreen` sur etat terminal (`Success` ou `Error`)

### PairingDoneScreen

- Recoit le `PairingStep` courant en parametre
- **Succes** : icone checkmark + "Pairing reussi !" + "Le device est maintenant connecte au VPS via le tunnel WireGuard." + bouton "Terminer"
- **Erreur retryable** : icone warning + message + bouton "Reessayer" (reset vers `ScanVpsQrScreen`) + bouton "Fermer"
- **Erreur non-retryable** : icone warning + message + bouton "Fermer" (couleur error)
- "Terminer"/"Fermer" : retour vers la route source (`DeviceListScreen`)
- "Reessayer" : appelle `viewModel.reset()`, navigue vers `ScanVpsQrScreen`

---

## 5. UseCases impliques

Tous les UseCases sont dans `domain/usecase/pairing/`.

### ParseVpsQrUseCase

- **Fichier** : `ParseVpsQrUseCase.kt`
- **Injection** : aucune dependance (parsing pur)
- **Entree** : `String` (contenu brut du QR)
- **Sortie** : `Result<PairingSession>`
- **Logique** : verifie que le contenu commence par `{`, parse le JSON, extrait les 5 champs obligatoires (`session_id`, `session_pin`, `device_wg_ip`, `local_token`, `nonce`)
- **Exceptions** : `UnrecognizedQrException` si pas du JSON, `MalformedQrException` si un champ manque

### ScanDeviceQrUseCase

- **Fichier** : `ScanDeviceQrUseCase.kt`
- **Injection** : aucune dependance (parsing pur)
- **Entree** : `String` (contenu brut du QR)
- **Sortie** : `Result<DevicePairingInfo>`
- **Logique** : parse l'URI, valide scheme (`pairing`), host (`radxa`), extrait les 4 parametres (`id`, `pub`, `ip`, `port`), valide IP via regex IPv4, port == 8099, pubkey == 44 chars
- **Exceptions** : `UnrecognizedQrException` si scheme/host incorrect, `MalformedQrException` si parametre invalide

### SendPinToDeviceUseCase

- **Fichier** : `SendPinToDeviceUseCase.kt`
- **Injection** : `PairingRepository`
- **Entree** : `deviceIp`, `devicePort`, `sessionId`, `sessionPin`, `localToken`, `nonce`, `radxaWgPubkey`
- **Sortie** : `Result<Unit>`
- **Logique** : delegue a `repository.sendPin(...)`. Log le sessionId mais redacte le PIN, le token et le nonce
- **Securite** : le session_pin, local_token et nonce ne sont jamais logges

### ConfirmPairingUseCase

- **Fichier** : `ConfirmPairingUseCase.kt`
- **Injection** : `PairingRepository`
- **Entree** : `deviceIp`, `devicePort`, `sessionId`
- **Sortie** : `Result<PairingStatus>`
- **Logique** : appelle `repository.pollStatus()` dans un `withTimeout(120_000L)`, attend le premier emit `PAIRED` ou `FAILED` via `Flow.first { ... }`
- **Timeout** : 120 secondes. Leve `PairingTimeoutException` via catch de `TimeoutCancellationException`
- **Intervalle de polling** : 2 secondes (impose cote Repository)

### StoreDevicePairingResultUseCase

- **Fichier** : `StoreDevicePairingResultUseCase.kt`
- **Injection** : `EncryptedDataStore`
- **Entree** : `deviceId`, `localToken`, `wgPubkey`, `localIp`
- **Sortie** : `Result<Unit>`
- **Logique** : persiste 3 valeurs dans `EncryptedDataStore` :
  - `local_token_{deviceId}` : token pour chiffrement LAN futur
  - `device_wg_pubkey_{deviceId}` : cle publique Curve25519 du device
  - `device_local_ip_{deviceId}` : IP LAN du device
- **Securite** : le localToken n'est jamais logge

### ParseSetupQrUseCase (hors flux de pairing device)

- **Fichier** : `ParseSetupQrUseCase.kt`
- **Role** : parse le QR d'onboarding mobile (configuration WireGuard initiale)
- **Format** : JSON avec `wg_private_key`, `wg_public_key_server`, `endpoint`, `tunnel_ip`, `dns`
- **Validations** : cles base64 de 44 chars, endpoint au format `host:port`, tunnel_ip en CIDR, dns en IP

---

## 6. Couche Repository et API LAN

### PairingRepository (interface)

Fichier : `domain/repository/PairingRepository.kt`

```kotlin
interface PairingRepository {
    suspend fun sendPin(...): Result<Unit>
    fun pollStatus(...): Flow<PairingStatus>
}
```

### PairingRepositoryImpl

Fichier : `data/repository/PairingRepositoryImpl.kt`

- Annote `@Singleton`, injecte via Hilt
- Dependances : `@Named("lan") PairingLanApi`, `SealedBoxHelper`
- Utilise un `OkHttpClient` dedie sans intercepteurs VPS (pas de CSRF, pas d'Auth, pas de VPN check)

**sendPin** :
1. Valide que `deviceIp` est RFC-1918 via `isLocalNetwork()`
2. Construit le JSON payload : `{session_id, session_pin, local_token, nonce}`
3. Decode la cle publique WireGuard base64 en 32 bytes Curve25519
4. Chiffre le payload avec `sealedBoxHelper.seal(jsonBytes, pubkeyBytes)`
5. Envoie en `application/octet-stream` via `POST http://{deviceIp}:{devicePort}/pin`
6. Verifie la reponse HTTP ; leve `PairingDeviceException` si non-2xx

**pollStatus** :
1. Valide que `deviceIp` est RFC-1918 ; emet `FAILED` et termine si non-local
2. Boucle infinie avec `GET http://{deviceIp}:{devicePort}/status?session_id={sessionId}`
3. Mappe la reponse via `PairingStatus.fromString()` (supporte "unpaired", "pairing", "waiting", "paired", "error", "failed")
4. Emet chaque statut dans le `Flow`
5. Termine le flow des que `PAIRED` ou `FAILED`
6. Delai de 2 secondes entre chaque requete (`delay(2_000L)`)

### PairingLanApi (Retrofit)

Fichier : `data/api/PairingLanApi.kt`

- Interface Retrofit avec `@Url` dynamique (pas de base URL fixe)
- `sendPin(@Url, @Body RequestBody)` : POST avec body octet-stream
- `getStatus(@Url)` : GET retournant `Map<String, Any>`
- Le client Retrofit associe utilise `http://localhost/` comme base URL fallback

### PairingStatus (enum)

Fichier : `domain/model/PairingStatus.kt`

Trois etats : `PENDING`, `PAIRED`, `FAILED`.

Mapping des statuts Radxa :
- `"pending"`, `"unpaired"`, `"pairing"`, `"waiting"` -> `PENDING`
- `"paired"` -> `PAIRED`
- `"failed"`, `"error"` -> `FAILED`
- Tout statut inconnu -> `PENDING`

---

## 7. Securite

### Validation isLocalNetwork

Avant chaque appel reseau vers le device (sendPin et pollStatus), `PairingRepositoryImpl`
valide que l'IP cible est RFC-1918 via `isLocalNetwork()` defini dans `security/NetworkUtils.kt`.

La fonction :
1. Applique une regex IPv4 stricte (bloque les hostnames, protection DNS rebinding)
2. Verifie que chaque octet est <= 255
3. Utilise `InetAddress.getByName()` puis teste `isSiteLocalAddress`, `isLinkLocalAddress`
   ou `isLoopbackAddress`

### Chiffrement crypto_box_seal

Le payload JSON envoye au Radxa (contenant `session_pin`, `local_token`, `nonce`) est chiffre
avec `crypto_box_seal` de libsodium (via `lazysodium-android`).

Implemente dans `security/SealedBoxHelper.kt` :
- Utilise la cle publique Curve25519 du Radxa (extraite du QR, 44 chars base64, decodee en 32 bytes)
- Produit un ciphertext de taille `plaintext.size + Box.SEALBYTES`
- Seul le Radxa peut dechiffrer avec sa cle privee WireGuard
- Le body HTTP envoye est `application/octet-stream` (bytes chiffres, pas de JSON en clair)

### Nonce anti-rejeu

Le champ `nonce` du QR VPS (64 chars hex) est inclus dans le payload chiffre. Il permet au
Radxa de rejeter les replays : un payload avec un nonce deja utilise est refuse.

### PIN a usage unique

Le VPS invalide le `session_pin` apres un usage reussi. L'app ne retente jamais
`SendPinToDeviceUseCase` apres un succes de `ConfirmPairingUseCase`. Le TTL de la session
est de 120 secondes.

### Donnees sensibles jamais loguees

Les champs `session_pin`, `local_token` et `nonce` sont systematiquement remplaces par
`[REDACTED]` dans tous les logs Timber. Le `PairingSession.toString()` est surcharge pour
masquer ces champs.

---

## 8. Gestion des erreurs

### Exceptions

| Exception                | Declencheur                                | retryable |
|--------------------------|--------------------------------------------|-----------|
| `UnrecognizedQrException` | QR non-JSON, scheme invalide, host invalide | `true`   |
| `MalformedQrException`   | Champ manquant ou format invalide dans le QR | `true`  |
| `PairingDeviceException` | Reponse HTTP non-2xx du Radxa lors du sendPin | `false` |
| `PairingTimeoutException`| Timeout 120s atteint pendant le polling      | `false`  |

### Comportement par etat

**Echec de scan QR** (`UnrecognizedQrException`, `MalformedQrException`) :
- Le ViewModel passe a `Error(message="QR non reconnu, reessayez", retryable=true)`
- L'ecran de scan affiche un `ErrorBanner` avec option de retry
- Le retry appelle `viewModel.reset()` et reste sur l'ecran de scan

**Echec d'envoi du PIN** (`PairingDeviceException`, erreur reseau) :
- Le ViewModel passe a `Error(retryable=false)`
- Navigation vers `PairingDoneScreen` avec message d'erreur
- Le bouton "Fermer" retourne a la liste des devices

**Timeout de confirmation** (`PairingTimeoutException`) :
- Message specifique : "Session expiree -- relancez le pairing depuis le VPS"
- `retryable=false` : la session VPS est expiree, un retry cote app ne suffit pas

**Echec du polling** (erreur reseau pendant `pollStatus`) :
- Les erreurs reseau transitoires sont absorbees et le statut est traite comme `PENDING`
- Le polling continue jusqu'au timeout de 120s

**Echec de sauvegarde** (`StoreDevicePairingResultUseCase` echoue) :
- Message : "Echec de la sauvegarde des cles du device"
- `retryable=false`
- Le pairing cote VPS et Radxa a reussi mais les donnees locales n'ont pas ete persistees

---

## 9. Contraintes

### VPN actif obligatoire

Le VPN WireGuard doit etre connecte (`VpnState.Connected`) pour acceder au reseau LAN
des devices. La connexion vers `radxa_ip:8099` est faite uniquement si le VPN est actif
(le VPN garantit l'acces au bon reseau avant de contacter le LAN).

### Acces reserve aux admins

Le flux de pairing est accessible uniquement aux comptes admin (`user.is_admin == true`).
L'onglet Devices dans la navigation est masque pour les comptes standard. Le bouton
"Ajouter un device" (qui demarre le pairing) est reserve aux admins.

### Pas de reprise de session

Si l'utilisateur quitte le flux (bouton retour), `viewModel.reset()` remet la machine
a etats a `Idle`. La session est abandonnee cote VPS a l'expiration du TTL 120s.
Il n'y a pas de mecanisme de reprise de session en cours.

### Intervalle de polling fixe

Le polling du statut est fixe a 2 secondes (`delay(2_000L)` dans `PairingRepositoryImpl`).
Cette valeur ne doit pas etre modifiee pour preserver la batterie et limiter la charge
sur le Radxa.

---

## 10. Persistance post-pairing

Apres un pairing reussi, `StoreDevicePairingResultUseCase` persiste dans `EncryptedDataStore` :

| Cle EncryptedDataStore            | Contenu                              |
|-----------------------------------|--------------------------------------|
| `local_token_{deviceId}`          | Token hex 256 bits pour chiffrement LAN futur |
| `device_wg_pubkey_{deviceId}`     | Cle publique Curve25519 du device    |
| `device_local_ip_{deviceId}`      | IP LAN du device                     |

Ces donnees sont reutilisees pour les communications LAN ulterieures
(maintenance locale via `LocalMaintenanceRepository`).

---

## Fichiers source de reference

| Fichier | Role |
|---------|------|
| `ui/screens/pairing/PairingViewModel.kt` | State machine, orchestration du flux |
| `ui/screens/pairing/ScanVpsQrScreen.kt` | Ecran scan QR VPS |
| `ui/screens/pairing/ScanDeviceQrScreen.kt` | Ecran scan QR Radxa |
| `ui/screens/pairing/PairingProgressScreen.kt` | Ecran progression (envoi PIN + polling) |
| `ui/screens/pairing/PairingDoneScreen.kt` | Ecran resultat (succes/echec) |
| `domain/usecase/pairing/ParseVpsQrUseCase.kt` | Parsing QR VPS |
| `domain/usecase/pairing/ScanDeviceQrUseCase.kt` | Parsing QR Radxa |
| `domain/usecase/pairing/SendPinToDeviceUseCase.kt` | Envoi PIN via Repository |
| `domain/usecase/pairing/ConfirmPairingUseCase.kt` | Polling confirmation avec timeout 120s |
| `domain/usecase/pairing/StoreDevicePairingResultUseCase.kt` | Persistance EncryptedDataStore |
| `domain/model/PairingSession.kt` | Modele session VPS (5 champs) |
| `domain/model/DevicePairingInfo.kt` | Modele device Radxa (4 champs) |
| `domain/model/PairingStatus.kt` | Enum PENDING/PAIRED/FAILED + mapping |
| `domain/repository/PairingRepository.kt` | Interface Repository |
| `data/repository/PairingRepositoryImpl.kt` | Implementation : crypto_box_seal, isLocalNetwork, polling |
| `data/api/PairingLanApi.kt` | Interface Retrofit LAN (sendPin, getStatus) |
| `security/SealedBoxHelper.kt` | Wrapper libsodium crypto_box_seal |
| `security/NetworkUtils.kt` | Validation RFC-1918 (isLocalNetwork) |
| `domain/exception/PairingException.kt` | PairingTimeoutException, PairingDeviceException |
| `ui/navigation/AppNavGraph.kt` | Navigation nested graph pairing_graph |
