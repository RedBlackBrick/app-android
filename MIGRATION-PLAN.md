# Plan de Migration -- app-android

Plan fichier par fichier. Chaque tache indique le fichier exact, ce qui change, et les noms de classes/methodes a reutiliser ou modifier.

Base du projet : `/home/thomas/Codes/app-android/`
Package source : `app/src/main/java/com/tradingplatform/app/`

---

## A. Dependances

### A1. `gradle/libs.versions.toml`

Ajouter dans `[versions]` :

```toml
lazysodium        = "5.1.0"
libsodium-jni     = "0.3.2"
```

Ajouter dans `[libraries]` :

```toml
lazysodium-android          = { group = "com.goterl", name = "lazysodium-android", version.ref = "lazysodium" }
libsodium-jni               = { group = "com.goterl", name = "lazysodium-jni", version.ref = "libsodium-jni" }
```

### A2. `app/build.gradle.kts`

Ajouter dans le bloc `dependencies` apres la section "Securite" (apres `implementation(libs.rootbeer)`) :

```kotlin
// Libsodium (chiffrement LAN)
implementation(libs.lazysodium.android)
implementation(libs.libsodium.jni)
```

Ajouter une regle ProGuard dans `proguard-rules.pro` :

```proguard
# Lazysodium — garder les classes JNI et natif
-keep class com.goterl.lazysodium.** { *; }
-keep class com.sun.jna.** { *; }
```

---

## B. Onboarding Mobile (nouveau flux)

### B1. Nouveau UseCase : `ParseSetupQrUseCase`

**Fichier a creer** : `domain/usecase/pairing/ParseSetupQrUseCase.kt`

```kotlin
package com.tradingplatform.app.domain.usecase.pairing

// Parse le QR du panel web (onboarding mobile)
// Format JSON : { "wg_private_key": "...", "wg_public_key_server": "...",
//                 "endpoint": "...", "tunnel_ip": "...", "dns": "..." }
// Retourne un domain model SetupQrData
// Reutilise UnrecognizedQrException et MalformedQrException du meme package
class ParseSetupQrUseCase @Inject constructor() {
    suspend operator fun invoke(raw: String): Result<SetupQrData>
}
```

**Validation** :
- `wg_private_key` : 44 chars base64 (cle Curve25519)
- `wg_public_key_server` : 44 chars base64
- `endpoint` : format `host:port` (port numerique)
- `tunnel_ip` : format CIDR `x.x.x.x/32`
- `dns` : IP valide

Reutilise `UnrecognizedQrException` et `MalformedQrException` definis dans `domain/usecase/pairing/ParseVpsQrUseCase.kt`.

### B2. Nouveau domain model : `SetupQrData`

**Fichier a creer** : `domain/model/SetupQrData.kt`

```kotlin
package com.tradingplatform.app.domain.model

data class SetupQrData(
    val wgPrivateKey: String,
    val wgPublicKeyServer: String,
    val endpoint: String,
    val tunnelIp: String,
    val dns: String,
)
```

### B3. Modifier `WireGuardManager` : nouvelle methode `configureFromSetupQr()`

**Fichier a modifier** : `vpn/WireGuardManager.kt`

Classe existante : `WireGuardManager` (singleton, `@Inject`, `CoroutineScope`).
Methodes existantes : `connect(config: WireGuardConfig)`, `disconnect()`.

**Ajouter** une methode qui construit un `WireGuardConfig` depuis `SetupQrData` et le passe a `connect()` :

```kotlin
/**
 * Configure et connecte le tunnel a partir des donnees du QR d'onboarding.
 * Stocke la config dans EncryptedDataStore avant de connecter.
 */
fun configureFromSetupQr(data: SetupQrData, dataStore: EncryptedDataStore) {
    applicationScope.launch(Dispatchers.IO) {
        // Stocker dans EncryptedDataStore
        dataStore.writeString(DataStoreKeys.WG_PRIVATE_KEY, data.wgPrivateKey)
        dataStore.writeString(DataStoreKeys.WG_ENDPOINT, data.endpoint)
        dataStore.writeString(DataStoreKeys.WG_SERVER_PUBKEY, data.wgPublicKeyServer)
        dataStore.writeString(DataStoreKeys.WG_TUNNEL_IP, data.tunnelIp)
        dataStore.writeString(DataStoreKeys.WG_DNS, data.dns)

        val config = WireGuardConfig(
            privateKey = data.wgPrivateKey,
            address = data.tunnelIp,
            dns = data.dns,
            peer = WireGuardPeer(
                publicKey = data.wgPublicKeyServer,
                endpoint = data.endpoint,
            ),
        )
        connect(config)
    }
}
```

Alternative : ajouter `EncryptedDataStore` en dependance du `WireGuardManager` via le constructeur (injecte par Hilt). La methode `connect()` existante prend deja un `WireGuardConfig` — pas besoin de la modifier.

### B4. Nouvelles cles `DataStoreKeys`

**Fichier a modifier** : `data/local/datastore/EncryptedDataStore.kt`

Classe existante : `DataStoreKeys` (object, cles `stringPreferencesKey`/`booleanPreferencesKey`).
Cles existantes : `ACCESS_TOKEN`, `USER_ID`, `IS_ADMIN`, `PORTFOLIO_ID`, `WG_PRIVATE_KEY`, `WG_CONFIG`.

**Ajouter** dans l'object `DataStoreKeys` :

```kotlin
val WG_ENDPOINT = stringPreferencesKey("wg_endpoint")
val WG_SERVER_PUBKEY = stringPreferencesKey("wg_server_pubkey")
val WG_TUNNEL_IP = stringPreferencesKey("wg_tunnel_ip")
val WG_DNS = stringPreferencesKey("wg_dns")
val SETUP_COMPLETED = booleanPreferencesKey("setup_completed")
// local_token par device — cle dynamique "local_token_{device_id}"
```

Pour les `local_token_{device_id}`, ajouter une methode helper dans `EncryptedDataStore` :

```kotlin
suspend fun writeLocalToken(deviceId: String, token: String) =
    writeString(stringPreferencesKey("local_token_$deviceId"), token)

suspend fun readLocalToken(deviceId: String): String? =
    readString(stringPreferencesKey("local_token_$deviceId"))
```

### B5. Nouveau screen : `SetupScreen` + `SetupViewModel`

**Fichier a creer** : `ui/screens/setup/SetupScreen.kt`

- Composable `SetupScreen` avec scanner camera (reutilise le composant `QrScannerView` existant dans `ui/components/QrScannerView.kt`)
- Affiche des instructions "Scannez le QR affiche sur le panel web"
- Apres scan reussi : affiche un indicateur de connexion VPN (etat `VpnState`)
- Une fois `VpnState.Connected` : navigue vers `LoginScreen`

**Fichier a creer** : `ui/screens/setup/SetupViewModel.kt`

```kotlin
@HiltViewModel
class SetupViewModel @Inject constructor(
    private val parseSetupQrUseCase: ParseSetupQrUseCase,
    private val wireGuardManager: WireGuardManager,
    private val dataStore: EncryptedDataStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow<SetupUiState>(SetupUiState.Scanning)
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    fun onQrScanned(raw: String) { /* parse, configure, connect */ }
}

sealed interface SetupUiState {
    data object Scanning : SetupUiState
    data object Connecting : SetupUiState
    data object Connected : SetupUiState
    data class Error(val message: String) : SetupUiState
}
```

### B6. Modifier la navigation : `Screen.kt` + `AppNavGraph.kt`

**Fichier a modifier** : `ui/navigation/Screen.kt`

Classe existante : `Screen` (sealed class avec `Login`, `Dashboard`, etc.).

**Ajouter** :

```kotlin
data object Setup : Screen("setup")
```

**Fichier a modifier** : `ui/navigation/AppNavGraph.kt`

Classe existante : `AppNavViewModel` (lit `isLoggedIn` et `isAdmin` depuis `GetAuthContextUseCase`).

**Modifier `AppNavViewModel`** : ajouter la lecture de `SETUP_COMPLETED` depuis `EncryptedDataStore` :

```kotlin
private val _isSetupCompleted = MutableStateFlow<Boolean?>(null)
val isSetupCompleted: StateFlow<Boolean?> = _isSetupCompleted.asStateFlow()

init {
    viewModelScope.launch {
        val context = getAuthContextUseCase()
        _isAdmin.value = context.isAdmin
        _isLoggedIn.value = context.isLoggedIn
        _isSetupCompleted.value = dataStore.readBoolean(DataStoreKeys.SETUP_COMPLETED) ?: false
    }
}
```

**Modifier `AppNavGraph`** : conditionner le `startDestination` :

```kotlin
val setupCompleted = isSetupCompleted ?: return
val startDestination = when {
    !setupCompleted -> Screen.Setup.route
    loggedIn -> Screen.Dashboard.route
    else -> Screen.Login.route
}
```

**Ajouter** le composable `Screen.Setup` dans le `NavHost` :

```kotlin
composable(Screen.Setup.route) {
    SetupScreen(
        onSetupComplete = {
            navController.navigate(Screen.Login.route) {
                popUpTo(Screen.Setup.route) { inclusive = true }
            }
        },
    )
}
```

### B7. Modifier `GetAuthContextUseCase`

**Fichier a modifier** : `domain/usecase/auth/GetAuthContextUseCase.kt`

Ce UseCase doit aussi retourner `setupCompleted` pour que `AppNavViewModel` puisse determiner le `startDestination`.

Ajouter un champ `setupCompleted: Boolean` au resultat retourne (soit dans le domain model `AuthContext`, soit en ajoutant un champ au retour).

---

## C. Pairing Radxa (chiffrement libsodium)

### C1. Nouveau helper : `SealedBoxHelper`

**Fichier a creer** : `security/SealedBoxHelper.kt`

```kotlin
package com.tradingplatform.app.security

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Box
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wrapper libsodium crypto_box_seal pour chiffrement asymetrique anonyme.
 *
 * Utilise la cle publique Curve25519 du Radxa (= sa wg_pubkey) pour chiffrer.
 * Seul le Radxa peut dechiffrer avec sa cle privee.
 *
 * Usage :
 *   val encrypted = sealedBoxHelper.seal(jsonBytes, radxaWgPubkeyBytes)
 *   // envoyer encrypted en octet-stream au Radxa
 */
@Singleton
class SealedBoxHelper @Inject constructor() {
    private val sodium: LazySodiumAndroid = LazySodiumAndroid(SodiumAndroid())

    /**
     * Chiffre [plaintext] avec la [recipientPublicKey] (32 bytes Curve25519).
     * Retourne les bytes chiffres (plaintext.size + Box.SEALBYTES).
     */
    fun seal(plaintext: ByteArray, recipientPublicKey: ByteArray): ByteArray {
        require(recipientPublicKey.size == Box.PUBLICKEYBYTES) {
            "Invalid public key size: ${recipientPublicKey.size} (expected ${Box.PUBLICKEYBYTES})"
        }
        val ciphertext = ByteArray(plaintext.size + Box.SEALBYTES)
        val success = sodium.cryptoBoxSeal(ciphertext, plaintext, plaintext.size.toLong(), recipientPublicKey)
        check(success) { "crypto_box_seal failed" }
        return ciphertext
    }
}
```

### C2. Modifier `PairingSession` — ajouter `localToken`

**Fichier a modifier** : `domain/model/PairingSession.kt`

Classe existante :
```kotlin
data class PairingSession(
    val sessionId: String,
    val sessionPin: String,
    val deviceWgIp: String,
)
```

**Modifier** en ajoutant `localToken` :

```kotlin
data class PairingSession(
    val sessionId: String,
    val sessionPin: String,
    val deviceWgIp: String,
    val localToken: String,
)
```

### C3. Modifier `ParseVpsQrUseCase` — parser `local_token`

**Fichier a modifier** : `domain/usecase/pairing/ParseVpsQrUseCase.kt`

Classe existante : `ParseVpsQrUseCase` (parse JSON `{session_id, session_pin, device_wg_ip}`).

**Modifier** pour parser le champ supplementaire `local_token` :

```kotlin
// Ajouter apres les champs existants :
val localToken = obj.optString("local_token").takeIf { it.isNotEmpty() }
    ?: throw MalformedQrException("local_token")

PairingSession(
    sessionId = sessionId,
    sessionPin = sessionPin,
    deviceWgIp = deviceWgIp,
    localToken = localToken,
)
```

**Source du `local_token`** : le `local_token` est inclus dans le QR VPS (genere par `POST /v1/pairing/session`, retourne dans `PairingSessionResponse`, et encode dans le QR SVG via `GET /v1/pairing/{session_id}/qr`). L'app le recoit donc en scannant le QR VPS. Format QR VPS :

```json
{
  "session_id": "uuid",
  "session_pin": "472938",
  "device_wg_ip": "10.42.0.5",
  "local_token": "random-256-bit-hex"
}
```

### C4. Modifier `PairingRepository` (interface) — signature `sendPin`

**Fichier a modifier** : `domain/repository/PairingRepository.kt`

Interface existante :
```kotlin
interface PairingRepository {
    suspend fun sendPin(
        deviceIp: String,
        devicePort: Int,
        sessionId: String,
        sessionPin: String,
    ): Result<Unit>

    fun pollStatus(...): Flow<PairingStatus>
}
```

**Modifier `sendPin`** pour ajouter `localToken` et `wgPubkey` :

```kotlin
suspend fun sendPin(
    deviceIp: String,
    devicePort: Int,
    sessionId: String,
    sessionPin: String,
    localToken: String,
    radxaWgPubkey: String,
): Result<Unit>
```

### C5. Modifier `SendPinToDeviceUseCase` — transmettre `localToken` et `wgPubkey`

**Fichier a modifier** : `domain/usecase/pairing/SendPinToDeviceUseCase.kt`

Classe existante : `SendPinToDeviceUseCase` (delegue a `PairingRepository.sendPin()`).

**Modifier** la signature de `invoke()` pour ajouter `localToken` et `radxaWgPubkey` :

```kotlin
suspend operator fun invoke(
    deviceIp: String,
    devicePort: Int,
    sessionId: String,
    sessionPin: String,
    localToken: String,
    radxaWgPubkey: String,
): Result<Unit> {
    Timber.d("SendPinToDevice: ip=$deviceIp port=$devicePort sessionId=$sessionId pin=[REDACTED] token=[REDACTED]")
    return repository.sendPin(deviceIp, devicePort, sessionId, sessionPin, localToken, radxaWgPubkey)
}
```

### C6. Modifier `PairingRepositoryImpl` — chiffrement + octet-stream

**Fichier a modifier** : `data/repository/PairingRepositoryImpl.kt`

Classe existante : `PairingRepositoryImpl` (singleton, injecte `@Named("lan") PairingLanApi`).
Methode existante : `sendPin()` — envoie `Map<String, String>` JSON via `pairingApi.sendPin(url, body)`.

**Modifier** :

1. Ajouter `SealedBoxHelper` en dependance constructeur.
2. Modifier `sendPin()` pour chiffrer le payload :

```kotlin
@Singleton
class PairingRepositoryImpl @Inject constructor(
    @Named("lan") private val pairingApi: PairingLanApi,
    private val sealedBoxHelper: SealedBoxHelper,
) : PairingRepository {

    override suspend fun sendPin(
        deviceIp: String,
        devicePort: Int,
        sessionId: String,
        sessionPin: String,
        localToken: String,
        radxaWgPubkey: String,
    ): Result<Unit> = runCatching {
        if (!isLocalNetwork(deviceIp)) {
            error("Refused: $deviceIp is not a local network address (RFC-1918 required)")
        }

        // Construire le JSON payload
        val payloadJson = JSONObject().apply {
            put("session_id", sessionId)
            put("session_pin", sessionPin)
            put("local_token", localToken)
        }.toString()

        // Decoder la cle publique WireGuard base64 → 32 bytes
        val pubkeyBytes = Base64.decode(radxaWgPubkey, Base64.NO_WRAP)

        // Chiffrer avec crypto_box_seal
        val encrypted = sealedBoxHelper.seal(payloadJson.toByteArray(Charsets.UTF_8), pubkeyBytes)

        // Envoyer en octet-stream
        val url = "http://$deviceIp:$devicePort/pin"
        val requestBody = encrypted.toRequestBody("application/octet-stream".toMediaType())

        val response = pairingApi.sendPin(url, requestBody)
        if (!response.isSuccessful) {
            error("sendPin failed: HTTP ${response.code()}")
        }
    }
}
```

### C7. Modifier `PairingLanApi` — accepter `RequestBody`

**Fichier a modifier** : `data/api/PairingLanApi.kt`

Interface existante :
```kotlin
interface PairingLanApi {
    @POST
    suspend fun sendPin(
        @Url url: String,
        @Body body: Map<String, String>,
    ): Response<Unit>

    @GET
    suspend fun getStatus(@Url url: String): Response<Map<String, String>>
}
```

**Modifier `sendPin`** pour accepter `RequestBody` au lieu de `Map<String, String>` :

```kotlin
@POST
suspend fun sendPin(
    @Url url: String,
    @Body body: RequestBody,   // application/octet-stream (bytes chiffres)
): Response<Unit>
```

Ajouter les imports `okhttp3.RequestBody`.

Ajouter aussi les endpoints de maintenance (GET /status chiffre, POST /command, GET /identity) — ou les mettre dans `LocalMaintenanceApi` (voir D3). Si la separation est preferee, ne modifier que `sendPin` ici.

### C8. Modifier `PairingViewModel` — passer les nouveaux parametres

**Fichier a modifier** : `ui/screens/pairing/PairingViewModel.kt`

Classe existante : `PairingViewModel` (HiltViewModel, state machine `PairingStep`).
Methode existante : `startPairing()` — appelle `sendPinToDeviceUseCase()` avec les donnees de `BothScanned`.

**Modifier `startPairing()`** pour passer `localToken` et `radxaWgPubkey` :

```kotlin
// Dans startPairing(), modifier l'appel existant :
sendPinToDeviceUseCase(
    deviceIp = current.device.localIp,
    devicePort = current.device.port,
    sessionId = current.session.sessionId,
    sessionPin = current.session.sessionPin,
    localToken = current.session.localToken,           // NOUVEAU
    radxaWgPubkey = current.device.wgPubkey,           // NOUVEAU
)
```

Ajouter aussi l'injection de `EncryptedDataStore` pour persister le `local_token` apres confirmation du pairing :

```kotlin
@HiltViewModel
class PairingViewModel @Inject constructor(
    private val parseVpsQrUseCase: ParseVpsQrUseCase,
    private val scanDeviceQrUseCase: ScanDeviceQrUseCase,
    private val sendPinToDeviceUseCase: SendPinToDeviceUseCase,
    private val confirmPairingUseCase: ConfirmPairingUseCase,
    private val dataStore: EncryptedDataStore,          // NOUVEAU
) : ViewModel() {
```

Dans le callback `onSuccess` de `confirmPairingUseCase` (quand `status == PairingStatus.PAIRED`), persister le local_token :

```kotlin
.onSuccess { status ->
    if (status == PairingStatus.PAIRED) {
        // Persister local_token pour la roue de secours
        val bothScanned = current // captured avant le launch
        dataStore.writeLocalToken(
            deviceId = bothScanned.device.deviceId,
            token = bothScanned.session.localToken,
        )
        // Persister aussi la wgPubkey du device pour le chiffrement futur
        dataStore.writeString(
            stringPreferencesKey("device_wg_pubkey_${bothScanned.device.deviceId}"),
            bothScanned.device.wgPubkey,
        )
        _step.value = PairingStep.Success
    } else {
        _step.value = PairingStep.Error(...)
    }
}
```

---

## D. Roue de Secours LAN (nouveau)

### D1. Nouveau UseCase : `SendLocalCommandUseCase`

**Fichier a creer** : `domain/usecase/maintenance/SendLocalCommandUseCase.kt`

```kotlin
package com.tradingplatform.app.domain.usecase.maintenance

class SendLocalCommandUseCase @Inject constructor(
    private val repository: LocalMaintenanceRepository,
) {
    /**
     * Chiffre la commande + local_token avec crypto_box_seal(radxa_wg_pubkey)
     * et l'envoie via HTTP LAN au Radxa.
     */
    suspend operator fun invoke(
        deviceIp: String,
        devicePort: Int,
        action: String,
        localToken: String,
        radxaWgPubkey: String,
        params: Map<String, String> = emptyMap(),
    ): Result<String>  // Retourne la reponse du Radxa (ou le message de confirmation)
}
```

### D2. Nouveau UseCase : `GetLocalStatusUseCase`

**Fichier a creer** : `domain/usecase/maintenance/GetLocalStatusUseCase.kt`

```kotlin
package com.tradingplatform.app.domain.usecase.maintenance

class GetLocalStatusUseCase @Inject constructor(
    private val repository: LocalMaintenanceRepository,
) {
    /**
     * Recupere le statut du Radxa via GET /status.
     * La reponse peut etre chiffree — le Repository gere le dechiffrement si necessaire.
     */
    suspend operator fun invoke(
        deviceIp: String,
        devicePort: Int,
    ): Result<DeviceLocalStatus>
}
```

### D3. Nouveau domain model : `DeviceLocalStatus`

**Fichier a creer** : `domain/model/DeviceLocalStatus.kt`

```kotlin
package com.tradingplatform.app.domain.model

data class DeviceLocalStatus(
    val deviceId: String,
    val wgStatus: String,       // "up" / "down"
    val wifiSsid: String?,
    val uptime: String,
    val lastError: String?,
)
```

### D4. Nouveau Repository : `LocalMaintenanceRepository`

**Fichier a creer** : `domain/repository/LocalMaintenanceRepository.kt`

```kotlin
package com.tradingplatform.app.domain.repository

interface LocalMaintenanceRepository {
    suspend fun sendCommand(
        deviceIp: String,
        devicePort: Int,
        action: String,
        localToken: String,
        radxaWgPubkey: String,
        params: Map<String, String> = emptyMap(),
    ): Result<String>

    suspend fun getStatus(
        deviceIp: String,
        devicePort: Int,
    ): Result<DeviceLocalStatus>

    suspend fun getIdentity(
        deviceIp: String,
        devicePort: Int,
    ): Result<DeviceIdentity>
}
```

### D5. Implementation : `LocalMaintenanceRepositoryImpl`

**Fichier a creer** : `data/repository/LocalMaintenanceRepositoryImpl.kt`

```kotlin
package com.tradingplatform.app.data.repository

@Singleton
class LocalMaintenanceRepositoryImpl @Inject constructor(
    @Named("lan") private val maintenanceApi: LocalMaintenanceApi,
    private val sealedBoxHelper: SealedBoxHelper,
) : LocalMaintenanceRepository {

    override suspend fun sendCommand(
        deviceIp: String,
        devicePort: Int,
        action: String,
        localToken: String,
        radxaWgPubkey: String,
        params: Map<String, String>,
    ): Result<String> = runCatching {
        if (!isLocalNetwork(deviceIp)) {
            error("Refused: $deviceIp is not RFC-1918")
        }

        val payloadJson = JSONObject().apply {
            put("action", action)
            put("local_token", localToken)
            put("params", JSONObject(params))
        }.toString()

        val pubkeyBytes = Base64.decode(radxaWgPubkey, Base64.NO_WRAP)
        val encrypted = sealedBoxHelper.seal(payloadJson.toByteArray(Charsets.UTF_8), pubkeyBytes)

        val url = "http://$deviceIp:$devicePort/command"
        val body = encrypted.toRequestBody("application/octet-stream".toMediaType())

        val response = maintenanceApi.sendCommand(url, body)
        if (!response.isSuccessful) error("command failed: HTTP ${response.code()}")
        response.body()?.string() ?: "OK"
    }

    override suspend fun getStatus(
        deviceIp: String,
        devicePort: Int,
    ): Result<DeviceLocalStatus> = runCatching {
        if (!isLocalNetwork(deviceIp)) error("Refused: $deviceIp is not RFC-1918")
        val url = "http://$deviceIp:$devicePort/status"
        val response = maintenanceApi.getStatus(url)
        if (!response.isSuccessful) error("status failed: HTTP ${response.code()}")
        // Parser la reponse JSON en DeviceLocalStatus
        parseStatusResponse(response.body())
    }

    override suspend fun getIdentity(
        deviceIp: String,
        devicePort: Int,
    ): Result<DeviceIdentity> = runCatching {
        if (!isLocalNetwork(deviceIp)) error("Refused: $deviceIp is not RFC-1918")
        val url = "http://$deviceIp:$devicePort/identity"
        val response = maintenanceApi.getIdentity(url)
        if (!response.isSuccessful) error("identity failed: HTTP ${response.code()}")
        parseIdentityResponse(response.body())
    }
}
```

### D6. Nouvelle API : `LocalMaintenanceApi`

**Fichier a creer** : `data/api/LocalMaintenanceApi.kt`

```kotlin
package com.tradingplatform.app.data.api

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * API pour les appels LAN de maintenance vers la Radxa (port 8099).
 * Pas de base URL fixe — @Url dynamique.
 * Pas d'intercepteurs VPS.
 */
interface LocalMaintenanceApi {

    @POST
    suspend fun sendCommand(
        @Url url: String,             // "http://{radxa_ip}:8099/command"
        @Body body: RequestBody,      // application/octet-stream (bytes chiffres)
    ): Response<ResponseBody>

    @GET
    suspend fun getStatus(
        @Url url: String,             // "http://{radxa_ip}:8099/status"
    ): Response<Map<String, String>>

    @GET
    suspend fun getIdentity(
        @Url url: String,             // "http://{radxa_ip}:8099/identity"
    ): Response<Map<String, String>>
}
```

### D7. Nouveau domain model : `DeviceIdentity`

**Fichier a creer** : `domain/model/DeviceIdentity.kt`

```kotlin
package com.tradingplatform.app.domain.model

data class DeviceIdentity(
    val deviceId: String,
    val wgPubkey: String,
    val localIp: String,
)
```

### D8. Nouveau screen : `LocalMaintenanceScreen` + `LocalMaintenanceViewModel`

**Fichier a creer** : `ui/screens/maintenance/LocalMaintenanceScreen.kt`

Composable avec 4 sections :
- **Statut** : affiche l'etat WireGuard, WiFi, uptime, derniere erreur (GET /status)
- **WiFi** : formulaire SSID + password → action `wifi_configure`
- **WireGuard** : bouton redemarrage → action `wireguard_restart`
- **Systeme** : boutons logs (action `logs`) et reboot (action `reboot` avec confirmation dialog)

**Fichier a creer** : `ui/screens/maintenance/LocalMaintenanceViewModel.kt`

```kotlin
@HiltViewModel
class LocalMaintenanceViewModel @Inject constructor(
    private val sendLocalCommandUseCase: SendLocalCommandUseCase,
    private val getLocalStatusUseCase: GetLocalStatusUseCase,
    private val dataStore: EncryptedDataStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    // deviceId depuis SavedStateHandle (argument de navigation)
    // Lit local_token et wg_pubkey depuis EncryptedDataStore
    // Expose uiState: StateFlow<MaintenanceUiState>
}

sealed interface MaintenanceUiState {
    data object Loading : MaintenanceUiState
    data class Ready(val status: DeviceLocalStatus) : MaintenanceUiState
    data class CommandResult(val message: String) : MaintenanceUiState
    data class Error(val message: String) : MaintenanceUiState
}
```

### D9. Nouvelle route `Screen.LocalMaintenance`

**Fichier a modifier** : `ui/navigation/Screen.kt`

Ajouter :

```kotlin
data object LocalMaintenance : Screen("local-maintenance/{deviceId}") {
    fun createRoute(deviceId: String): String = "local-maintenance/${Uri.encode(deviceId)}"
}
```

### D10. Modifier `AppNavGraph.kt` — ajouter destination

**Fichier a modifier** : `ui/navigation/AppNavGraph.kt`

Ajouter un `composable` dans le `NavHost` pour `Screen.LocalMaintenance` :

```kotlin
composable(
    route = Screen.LocalMaintenance.route,
    arguments = listOf(navArgument("deviceId") { type = NavType.StringType }),
) { backStackEntry ->
    val deviceId = backStackEntry.arguments?.getString("deviceId") ?: ""
    LocalMaintenanceScreen(
        onNavigateBack = { navController.popBackStack() },
    )
}
```

### D11. Modifier `DeviceDetailScreen` — lien vers maintenance locale

**Fichier a modifier** : `ui/screens/devices/DeviceDetailScreen.kt`

Ajouter un bouton "Depannage local" (visible quand le device est offline ou inaccessible via VPN).
Au clic : `navController.navigate(Screen.LocalMaintenance.createRoute(deviceId))`.

Le callback de navigation est a passer en parametre du composable :

```kotlin
@Composable
fun DeviceDetailScreen(
    deviceId: String,
    onNavigateBack: () -> Unit,
    onNavigateToLocalMaintenance: () -> Unit,   // NOUVEAU
)
```

Adapter l'appel dans `AppNavGraph.kt` au niveau du `composable(Screen.DeviceDetail.route)`.

---

## E. Securite

### E1. `SealedBoxHelper` — deja traite en C1

Voir section C1.

### E2. Modifier `SecurityModule.kt` (si necessaire)

**Fichier a modifier** : `di/SecurityModule.kt`

Classe existante : fournit `BiometricManager`, `CertificatePinnerProvider`, `KeystoreManager`, `RootDetector`.

`SealedBoxHelper` est une classe `@Singleton @Inject constructor()` — Hilt peut l'injecter sans provider explicite dans le module. **Aucune modification necessaire** si le constructeur est annote `@Inject`.

Si un binding explicite est prefere pour la lisibilite :

```kotlin
@Provides
@Singleton
fun provideSealedBoxHelper(): SealedBoxHelper = SealedBoxHelper()
```

### E3. Modifier `NetworkUtils.kt` (aucune modification necessaire)

**Fichier** : `security/NetworkUtils.kt`

La fonction `isLocalNetwork(ip: String): Boolean` existante est deja correcte et suffisante. Elle est reutilisee par `PairingRepositoryImpl` et sera reutilisee par `LocalMaintenanceRepositoryImpl`.

**Aucune modification.**

### E4. Modifier `NetworkModule.kt` — provide `LocalMaintenanceApi`

**Fichier a modifier** : `di/NetworkModule.kt`

Ajouter un provider pour `LocalMaintenanceApi` utilisant le Retrofit LAN existant :

```kotlin
@Provides
@Singleton
@Named("lan")
fun provideLocalMaintenanceApi(@Named("lan") lanRetrofit: Retrofit): LocalMaintenanceApi =
    lanRetrofit.create(LocalMaintenanceApi::class.java)
```

### E5. Modifier `RepositoryModule.kt` — bind `LocalMaintenanceRepository`

**Fichier a modifier** : `di/RepositoryModule.kt`

Ajouter :

```kotlin
@Binds
@Singleton
abstract fun bindLocalMaintenanceRepository(
    impl: LocalMaintenanceRepositoryImpl
): LocalMaintenanceRepository
```

---

## F. Documentation

### F1. Modifier `CLAUDE.md`

**Fichier a modifier** : `CLAUDE.md`

Modifications :

1. **Section 2 (ARCHITECTURE)** — structure des packages : ajouter `security/SealedBoxHelper.kt` dans l'arbre, ajouter `domain/usecase/maintenance/` avec `SendLocalCommandUseCase`, `GetLocalStatusUseCase`. Ajouter `ui/screens/setup/` et `ui/screens/maintenance/`. Ajouter `data/api/LocalMaintenanceApi.kt`.

2. **Section 2 — UseCases a creer** : mettre a jour la liste dans `domain/usecase/pairing/` pour inclure `ParseSetupQrUseCase`. Ajouter un bloc `domain/usecase/maintenance/`.

3. **Section 8 (PAIRING)** :
   - Mettre a jour le flux de pairing : le body HTTP de sendPin est desormais `application/octet-stream` (bytes chiffres par `SealedBoxHelper`), pas du JSON en clair.
   - Mettre a jour le QR VPS pour inclure `local_token`.
   - Ajouter une note sur le chiffrement libsodium `crypto_box_seal(radxa_wg_pubkey)`.
   - Supprimer la mention "Le session_pin transite en clair sur ce troncon — risque accepte" dans la note d'implementation (ce n'est plus le cas).

4. **Section 3 (VPN)** : documenter la methode `WireGuardManager.configureFromSetupQr()` et le flux d'onboarding QR.

5. **Section 1 (REGLES IMPERATIVES)** : ajouter dans "TOUJOURS FAIRE" : "Chiffrer les payloads LAN avec `SealedBoxHelper.seal()` avant envoi HTTP au Radxa".

6. **Section 11 (BUILD)** : ajouter la dependance lazysodium-android dans les dependances.

### F2. Modifier `docs/architecture-decisions.md`

**Fichier a modifier** : `docs/architecture-decisions.md`

Ajouter deux ADR :

**ADR J — Chiffrement LAN libsodium (crypto_box_seal)** :

Toute donnee sensible transitant en HTTP sur le LAN (session_pin, local_token, commandes maintenance) est chiffree avec `crypto_box_seal(radxa_wg_pubkey)` via lazysodium-android. Le HTTP reste en clair mais les secrets sont illisibles pour le reseau. Le Radxa dechiffre avec sa `wg_private_key` via PyNaCl. Voir `security/SealedBoxHelper.kt`.

**ADR K — Onboarding mobile via QR** :

L'App scanne un QR affiche sur le panel web PC. Le QR contient la cle privee WireGuard, la cle publique serveur, l'endpoint, l'IP tunnel et le DNS. L'App stocke la cle privee dans le Keystore (via `EncryptedDataStore`), configure le tunnel (`WireGuardManager.configureFromSetupQr()`), puis enchaine avec le login classique. La cle privee ne transite jamais par le reseau — uniquement par le canal visuel (ecran PC → camera mobile). Voir `ui/screens/setup/SetupScreen.kt` et `domain/usecase/pairing/ParseSetupQrUseCase.kt`.

### F3. Modifier `docs/api-contracts.md`

**Fichier a modifier** : `docs/api-contracts.md`

Ajouter les sections suivantes :

**Onboarding mobile — QR format** :

```json
{
  "wg_private_key": "base64... (44 chars)",
  "wg_public_key_server": "base64... (44 chars)",
  "endpoint": "vps.example.com:51820",
  "tunnel_ip": "10.42.0.101/32",
  "dns": "10.42.0.1"
}
```

QR Version ~10, TTL 5 minutes. Genere par `GET /v1/vpn-peers/mobile-setup-qr` (admin, VPS).

**Pairing — QR VPS mis a jour** :

```json
{
  "session_id": "uuid",
  "session_pin": "472938",
  "device_wg_ip": "10.42.0.5",
  "local_token": "hex-256-bit"
}
```

**Pairing — POST LAN /pin (format chiffre)** :

```
POST http://{radxa_ip}:8099/pin
Content-Type: application/octet-stream

Body: crypto_box_seal(
  '{"session_id":"uuid","session_pin":"472938","local_token":"hex"}',
  radxa_wg_pubkey
)
```

Reponse : `200 OK` (body vide ou `{"status": "ok"}`).

**Maintenance LAN — POST /command (format chiffre)** :

```
POST http://{radxa_ip}:8099/command
Content-Type: application/octet-stream

Body: crypto_box_seal(
  '{"action":"wifi_configure","local_token":"hex","params":{"ssid":"...","password":"..."}}',
  radxa_wg_pubkey
)
```

Actions : `wifi_configure`, `wireguard_restart`, `logs`, `reboot`.

**Maintenance LAN — GET /identity** :

```
GET http://{radxa_ip}:8099/identity

Response 200:
{
  "device_id": "radxa-001",
  "wg_pubkey": "base64...",
  "local_ip": "192.168.1.42"
}
```

**Maintenance LAN — GET /status** :

```
GET http://{radxa_ip}:8099/status

Response 200:
{
  "status": "ok",
  "wg_status": "up",
  "wifi_ssid": "MyNetwork",
  "uptime": "3d 12h",
  "last_error": null
}
```

---

## G. Nettoyage

### G1. Supprimer les references a l'envoi en clair du PIN

**Fichier a modifier** : `CLAUDE.md`

Supprimer ou reediter ces mentions :
- Section 8, note d'implementation : "La connexion vers `radxa_ip:8099` est **HTTP non chiffre** (LAN uniquement). Le `session_pin` transite en clair sur ce troncon — risque accepte (LAN requis, TTL 120s, 3 tentatives VPS)."
- Remplacer par : "La connexion vers `radxa_ip:8099` est HTTP. Le payload (session_pin, local_token) est chiffre avec `crypto_box_seal(radxa_wg_pubkey)` avant envoi — illisible sans la cle privee du Radxa."

**Fichier a modifier** : `data/repository/PairingRepositoryImpl.kt`

Supprimer le commentaire en-tete de `sendPin()` :
- "Envoie le PIN de session a la Radxa via HTTP LAN (non chiffre — LAN uniquement, TTL 120s)."
- Remplacer par : "Envoie le PIN de session a la Radxa via HTTP LAN (payload chiffre libsodium)."

### G2. Supprimer les donnees en clair dans le body Map

**Fichier a modifier** : `data/repository/PairingRepositoryImpl.kt`

Supprimer completement :

```kotlin
val body = mapOf(
    "session_id" to sessionId,
    "session_pin" to sessionPin,
)
```

Ce bloc est remplace par la construction du JSON chiffre (voir C6).

---

## H. Mode Developpement Local (DEV_MODE)

### H1. Modifier `app/build.gradle.kts`

Ajouter dans le bloc `buildTypes` :

```kotlin
debug {
    buildConfigField("boolean", "DEV_MODE", project.findProperty("DEV_MODE")?.toString() ?: "false")
}
release {
    buildConfigField("boolean", "DEV_MODE", "false")
}
```

### H2. Modifier `VpnRequiredInterceptor`

**Fichier** : `vpn/VpnRequiredInterceptor.kt` (ou `data/api/interceptors/`)

Ajouter au debut de `intercept()` :

```kotlin
if (BuildConfig.DEV_MODE) return chain.proceed(chain.request())
```

### H3. Modifier `CertificatePinnerProvider`

**Fichier** : `security/CertificatePinner.kt`

Modifier pour retourner `null` en DEV_MODE :

```kotlin
fun buildCertificatePinner(): CertificatePinner? {
    if (BuildConfig.DEV_MODE) return null
    // ... construction normale
}
```

Adapter `NetworkModule.kt` pour gerer le `null` :

```kotlin
val builder = OkHttpClient.Builder()
certificatePinnerProvider.buildCertificatePinner()?.let { builder.certificatePinner(it) }
```

### H4. Modifier `local.properties.example`

Ajouter :

```properties
# --- Dev Mode (direct LAN, no WireGuard) ---
# DEV_MODE=true
# VPS_BASE_URL=http://192.168.1.X:8013
```

---

## Ordre d'execution recommande

| Phase | Taches | Dependances |
|-------|--------|-------------|
| 1 | A1, A2 | Aucune (dependances Gradle) |
| 2 | C1 (SealedBoxHelper) | Phase 1 (lazysodium disponible) |
| 3 | B1-B4 (ParseSetupQrUseCase, SetupQrData, DataStoreKeys, WireGuardManager) | Phase 2 |
| 4 | B5-B7 (SetupScreen, SetupViewModel, navigation) | Phase 3 |
| 5 | C2-C8 (modification pairing : PairingSession, ParseVpsQr, Repository, API, ViewModel) | Phase 2 |
| 6 | D1-D11 (roue de secours : UseCases, Repository, API, Screen, navigation) | Phase 2 + Phase 5 |
| 7 | E4-E5 (DI modules) | Phase 5 + Phase 6 |
| 8 | F1-F3 (documentation) | Phase 7 |
| 9 | G1-G2 (nettoyage) | Phase 8 |

---

## Resume des fichiers

### Fichiers a creer (14)

| # | Chemin complet | Description |
|---|----------------|-------------|
| 1 | `app/src/main/java/com/tradingplatform/app/security/SealedBoxHelper.kt` | Wrapper libsodium crypto_box_seal |
| 2 | `app/src/main/java/com/tradingplatform/app/domain/usecase/pairing/ParseSetupQrUseCase.kt` | Parse QR onboarding mobile |
| 3 | `app/src/main/java/com/tradingplatform/app/domain/model/SetupQrData.kt` | Domain model QR onboarding |
| 4 | `app/src/main/java/com/tradingplatform/app/domain/model/DeviceLocalStatus.kt` | Domain model statut maintenance |
| 5 | `app/src/main/java/com/tradingplatform/app/domain/model/DeviceIdentity.kt` | Domain model identite Radxa |
| 6 | `app/src/main/java/com/tradingplatform/app/domain/usecase/maintenance/SendLocalCommandUseCase.kt` | UseCase commande maintenance LAN |
| 7 | `app/src/main/java/com/tradingplatform/app/domain/usecase/maintenance/GetLocalStatusUseCase.kt` | UseCase statut maintenance LAN |
| 8 | `app/src/main/java/com/tradingplatform/app/domain/repository/LocalMaintenanceRepository.kt` | Interface repository maintenance |
| 9 | `app/src/main/java/com/tradingplatform/app/data/repository/LocalMaintenanceRepositoryImpl.kt` | Implementation repository maintenance |
| 10 | `app/src/main/java/com/tradingplatform/app/data/api/LocalMaintenanceApi.kt` | Interface Retrofit maintenance LAN |
| 11 | `app/src/main/java/com/tradingplatform/app/ui/screens/setup/SetupScreen.kt` | UI onboarding QR |
| 12 | `app/src/main/java/com/tradingplatform/app/ui/screens/setup/SetupViewModel.kt` | ViewModel onboarding |
| 13 | `app/src/main/java/com/tradingplatform/app/ui/screens/maintenance/LocalMaintenanceScreen.kt` | UI maintenance LAN |
| 14 | `app/src/main/java/com/tradingplatform/app/ui/screens/maintenance/LocalMaintenanceViewModel.kt` | ViewModel maintenance LAN |

### Fichiers a modifier (17)

| # | Chemin complet | Modification |
|---|----------------|-------------|
| 1 | `gradle/libs.versions.toml` | Ajout lazysodium-android + libsodium-jni |
| 2 | `app/build.gradle.kts` | Ajout implementation lazysodium |
| 3 | `app/proguard-rules.pro` | Ajout regles lazysodium/JNA |
| 4 | `app/src/main/java/.../data/local/datastore/EncryptedDataStore.kt` | Nouvelles cles + helpers local_token |
| 5 | `app/src/main/java/.../domain/model/PairingSession.kt` | Ajout champ localToken |
| 6 | `app/src/main/java/.../domain/usecase/pairing/ParseVpsQrUseCase.kt` | Parser local_token |
| 7 | `app/src/main/java/.../domain/usecase/pairing/SendPinToDeviceUseCase.kt` | Ajout params localToken, radxaWgPubkey |
| 8 | `app/src/main/java/.../domain/repository/PairingRepository.kt` | Signature sendPin modifiee |
| 9 | `app/src/main/java/.../data/repository/PairingRepositoryImpl.kt` | Chiffrement SealedBoxHelper + RequestBody |
| 10 | `app/src/main/java/.../data/api/PairingLanApi.kt` | sendPin accepte RequestBody |
| 11 | `app/src/main/java/.../vpn/WireGuardManager.kt` | Nouvelle methode configureFromSetupQr() |
| 12 | `app/src/main/java/.../ui/navigation/Screen.kt` | Routes Setup, LocalMaintenance |
| 13 | `app/src/main/java/.../ui/navigation/AppNavGraph.kt` | Destinations + startDestination conditionnel |
| 14 | `app/src/main/java/.../ui/screens/pairing/PairingViewModel.kt` | Passe localToken/wgPubkey, persiste local_token |
| 15 | `app/src/main/java/.../di/NetworkModule.kt` | Provide LocalMaintenanceApi |
| 16 | `app/src/main/java/.../di/RepositoryModule.kt` | Bind LocalMaintenanceRepository |
| 17 | `app/src/main/java/.../ui/screens/devices/DeviceDetailScreen.kt` | Lien vers LocalMaintenanceScreen |

### Documentation a modifier (3)

| # | Fichier | Modification |
|---|---------|-------------|
| 1 | `CLAUDE.md` | Reflet UseCases, libsodium, onboarding QR, suppression mention "clair" |
| 2 | `docs/architecture-decisions.md` | ADR J (libsodium LAN), ADR K (onboarding QR) |
| 3 | `docs/api-contracts.md` | Endpoints onboarding, QR formats, requetes LAN chiffrees |
