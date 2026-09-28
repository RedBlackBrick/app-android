# Plan partiel — verrou biométrique + stockage sécurisé (#1, #8, #17, LOW biométriques)
Source : agent planificateur, 2026-09-28. Base : app/src/main/java/com/tradingplatform/app/.

## Faits vérifiés qui pilotent la conception
- security-crypto 1.1.0 : les keysets Tink (`__androidx_security_crypto_encrypted_prefs_key_keyset__` / `_value_keyset__`) sont stockés DANS `trading_secure_prefs` ; alias master key `_androidx_security_master_key_`. `deleteSharedPreferences("trading_secure_prefs")` + `keyStore.deleteEntry(alias)` = wipe complet.
- biometric 1.2.0-alpha05 : `BiometricPrompt` n'accepte que FragmentActivity/Fragment. Sur API 26-27 il utilise `FingerprintDialogFragment` → `appcompat AlertDialog` → **plante avec le thème actuel** `android:Theme.Material.Light.NoActionBar` (res/values/themes.xml:3).
- `KeystoreManager.initCipher()` lève `UserNotAuthenticatedException` après 300 s ; `BiometricManager.authenticate` (:79-86) n'attrape que `KeyPermanentlyInvalidatedException` → une fois #1 corrigé, l'exception s'échappe du LaunchedEffect et **plante l'app**. `setUserAuthenticationValidityDurationSeconds` est déprécié (API 30) → `setUserAuthenticationParameters`.
- Le pattern ProcessLifecycleOwner + DefaultLifecycleObserver existe déjà (PublicWsClient.kt:103-127).

## A. Rendre le verrou réel — M
Fichiers : MainActivity.kt, ui/components/BiometricLockOverlay.kt, security/BiometricManager.kt, security/KeystoreManager.kt, gradle/libs.versions.toml, app/build.gradle.kts, res/values/themes.xml.
1. `MainActivity : FragmentActivity()`. Vérifié : @AndroidEntryPoint OK, setContent OK, FLAG_SECURE/immersive inchangés, BackHandler (SetupScreen.kt:67, PairingProgressScreen.kt:96) OK, aucun cast `as ComponentActivity`, les activités de config widget restent ComponentActivity. Ajouter `androidx.fragment:fragment-ktx` 1.8.x explicite.
2. Thème : parent `Theme.AppCompat.DayNight.NoActionBar` (+ appcompat 1.7.x explicite), requis pour le dialog d'empreinte API 26-27. Alternative : `minSdk = 28`. À décider, documenter dans CLAUDE.md §4.
3. Fail-closed dans `triggerBiometricAuth` : `context.findFragmentActivity()` (dérouler la chaîne ContextWrapper) ; si null ou biometricManager null → `onError("Authentification indisponible")`, jamais onSuccess. L'escape hatch 60 s → logout forcé reste la seule sortie.
4. @Preview : `LocalInspectionMode.current` → UI statique sans LaunchedEffect d'auth.
5. `BiometricManager.authenticate` : pré-check `keystoreManager.checkAuthValidity()` → Invalidated (KeyPermanentlyInvalidatedException) → regenerateKey + onKeyInvalidated ; Expired (UserNotAuthenticatedException) / Valid → prompt. Remplacer par `setUserAuthenticationParameters(300, AUTH_BIOMETRIC_STRONG)` si SDK ≥ 30. `PromptInfo.setConfirmationRequired(false)`, BIOMETRIC_STRONG seul.
6. `BackHandler(enabled = isLocked) {}` dans l'overlay.
Tests : JVM Robolectric `BiometricLockOverlayTest` (hôte ComponentActivity → overlay reste, onAuthSuccess jamais appelé, message d'erreur) ; instrumenté avec `createAndroidComposeRule<FragmentActivity>()` + BiometricManager réel → overlay reste 2 s ; `BiometricManagerTest` JVM (KeystoreManager mocké lançant UserNotAuthenticatedException → pas de crash, prompt demandé ; factory injectable `(FragmentActivity, Executor, Callback) -> BiometricPrompt`).
Risques : bump fragment/appcompat, changement de thème (seuls les dialogs BiometricPrompt sont non-Compose), chemin API 26-27 non testé sans émulateur.

## B. Propriétaire unique de l'inactivité et de l'état de verrou — M (dépend de A)
Fichiers : security/BiometricLockManager.kt, MainActivity.kt, TradingApplication.kt, EncryptedDataStore.kt (clés), AppNavGraph.kt:248-260.
BiometricLockManager (singleton, DefaultLifecycleObserver sur ProcessLifecycleOwner) possède `lastInteractionAt`, le job de poll et `isLocked`. MainActivity ne fait que `dispatchTouchEvent → biometricLockManager.onUserInteraction()` ; supprimer inactivityJob/isBiometricLocked/lastInteractionAt/startInactivityTimer/showBiometricLock/onBiometricUnlocked (:34-49, :79, :94-127). La recréation d'Activity ne remet plus l'horloge à zéro.
```kotlin
@Singleton class BiometricLockManager @Inject constructor(dataStore, appScope, clock: () -> Long = System::currentTimeMillis) : DefaultLifecycleObserver {
    private val lastInteractionAt = AtomicLong(0); private var pollJob: Job? = null
    private val _isLocked = MutableStateFlow(true)                    // fail-closed jusqu'à restore()
    fun onUserInteraction() { if (!_isLocked.value) lastInteractionAt.set(clock()) }
    override fun onStart(o) { if (!_isLocked.value && expired()) lock(); startPolling() }
    override fun onStop(o) { pollJob?.cancel(); persist(_isLocked.value, lastInteractionAt.get()) }
    fun lock() { _isLocked.value = true; persist(true, lastInteractionAt.get()) }
    fun unlock() { lastInteractionAt.set(clock()); _isLocked.value = false; persist(false, clock()) }
}
```
Persistance : `BIOMETRIC_LOCKED` + nouvelle clé `LAST_INTERACTION_AT` dans `criticalKeys` (commit=true). `restorePersistedState()` : `locked = readBoolean ?: true`, `last = readLong ?: 0` ; `_isLocked = locked || (now - last ≥ TIMEOUT)`. **Politique au démarrage à froid : verrouillé dès qu'un token de session existe** ; restore appelé dans la même coroutine que la lecture de ACCESS_TOKEN (TradingApplication), avant `tokenHolder.setToken` ; si NotFound (pas de session) → unlock silencieux (Setup/Login non gatés). Corrompu → reste verrouillé, le reset de C déverrouille. Le prompt réussi réinitialise les deux mécanismes (l'OS rafraîchit la clé temporisée sur auth BIOMETRIC_STRONG). Mettre à jour le snippet CLAUDE.md §4 (le timer quitte MainActivity).
Tests JVM (Turbine, horloge injectée) : verrou après timeout ; interaction diffère ; unlock() réarme puis re-verrouille (régression #8) ; onStop persiste avec commit ; restore selon (locked,last) ; touches ignorées quand verrouillé. Instrumenté optionnel : `ActivityScenario.recreate()` ne remet pas le timestamp.

## C. EncryptedDataStore récupérable — M (indépendant ; B s'appuie sur sa sémantique Corrupted)
Fichiers : EncryptedDataStore.kt, AppNavGraph.kt (:226-230, :319-330, :421-431, :475-479), TradingApplication.kt:82-86, nouveau use case.
1. Holder réinitialisable : remplacer `by lazy` par `@Volatile private var prefs` + `initLock` + `prefs()` double-check ; `createPrefs()` attrape GeneralSecurityException | IOException | RuntimeException (Tink alpha lève SecurityException/IllegalStateException). 20 sites d'appel → `prefs()`.
2. `resetCorruptedStore()` : sous lock, `prefs = null` ; `deleteSharedPreferences(PREFS_NAME)` ; `KeyStore("AndroidKeyStore").deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)` ; recréer une fois. **Rien ne survit** : clés WG, config, SETUP_COMPLETED, tokens, cookies, local_token_* → l'utilisateur doit rescanner le QR de setup.
3. `catch (e: SecurityException)` dans tous les readString*/readLocalToken (traité comme GeneralSecurityException).
4. Routage : `AppNavViewModel.onKeystoreCorruptionAcknowledged()` → nouveau `RecoverFromKeystoreCorruptionUseCase` (privateWsClient.disconnect ; wireGuardManager.disconnect ; tokenHolder.clear ; cookieJar.clear ; csrfInterceptor.clearToken ; appDatabase.clearAllTables ; dataStore.resetCorruptedStore ; biometricLockManager.unlock) puis `_isLoggedIn=false; _isSetupCompleted=false` ; AppNavGraph : `LaunchedEffect(isSetupCompleted)` → navigate(Setup) popUpTo(0) pour que l'effet « isLoggedIn==false → Login » ne gagne pas ; texte du dialog : « Vos données locales et la configuration VPN ont été réinitialisées — scannez à nouveau le QR de configuration. » Échec du reset → dialog à nouveau (« stockage indisponible »).
Tests : instrumenté `EncryptedDataStoreResetTest` (Keystore réel) ; JVM Robolectric avec factory `prefsFactory: (Context) -> SharedPreferences` injectable lançant SecurityException ; `AppNavViewModelTest` (acknowledge → use case, setup=false, dialog masqué).
Risques : `deleteSharedPreferences` pendant qu'un autre thread tient l'ancienne instance → `prefs = null` sous lock avant suppression.

## Ordre
1. **C** (indépendant, fiabilise readStringSafe et le restore de B). 2. **A** (corrige le CRITIQUE). 3. **B** juste après A (A rend #8 visible : un seul verrouillage puis plus jamais). Docs : CLAUDE.md §4 (emplacement du timer, politique « verrouillé au démarrage », sémantique du reset, exigence de thème). Effort ≈ M+M+M ; A porte le plus de risque de régression (thème/fragment), C la plus grande valeur de test instrumenté.
