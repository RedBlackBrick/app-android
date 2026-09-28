package com.tradingplatform.app.data.local.datastore

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore

// Clés de référence (CLAUDE.md §12)
object DataStoreKeys {
    val ACCESS_TOKEN = stringPreferencesKey("auth_access_token")
    val USER_ID = longPreferencesKey("auth_user_id")
    val IS_ADMIN = booleanPreferencesKey("auth_is_admin")
    val PORTFOLIO_ID = stringPreferencesKey("auth_portfolio_id")
    val WG_PRIVATE_KEY = stringPreferencesKey("wg_private_key")
    val WG_CONFIG = stringPreferencesKey("wg_config")
    // WireGuard onboarding — Phase 3
    val WG_ENDPOINT = stringPreferencesKey("wg_endpoint")
    val WG_SERVER_PUBKEY = stringPreferencesKey("wg_server_pubkey")
    val WG_TUNNEL_IP = stringPreferencesKey("wg_tunnel_ip")
    val WG_DNS = stringPreferencesKey("wg_dns")
    val SETUP_COMPLETED = booleanPreferencesKey("setup_completed")
    val CSRF_TOKEN = stringPreferencesKey("csrf_token")
    // FCM token registration retry (write-ahead)
    val PENDING_FCM_TOKEN = stringPreferencesKey("pending_fcm_token")
    val PENDING_FCM_FINGERPRINT = stringPreferencesKey("pending_fcm_fingerprint")
    // Biometric inactivity lock state — persisté pour restaurer le verrou après un
    // process kill (app tuée pendant qu'elle était verrouillée → redémarrage → encore verrouillée)
    val BIOMETRIC_LOCKED = booleanPreferencesKey("biometric_locked")
    // Symbole par défaut affiché par le Dashboard et utilisé pour le sync quote initial
    // des widgets (fallback). Configurable par l'utilisateur via ProfileScreen. Non sensible
    // stricto-sensu, mais stocké dans EncryptedDataStore pour homogénéité avec les autres
    // préférences applicatives (évite un second SharedPreferences store à maintenir).
    val DEFAULT_QUOTE_SYMBOL = stringPreferencesKey("default_quote_symbol")
    // Cookies : clé dynamique "cookie_${name}"
}

private const val PREFS_NAME = "trading_secure_prefs"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"

/** Factory de production : MasterKey (Android Keystore) + EncryptedSharedPreferences. */
private fun createEncryptedPrefs(context: Context): SharedPreferences {
    val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    return EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}

/** Supprime l'alias MasterKey par défaut de l'Android Keystore (reset après corruption). */
private fun deleteDefaultMasterKeyAlias() {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
    keyStore.load(null)
    if (keyStore.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
        keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
    }
}

/**
 * Stockage chiffré (EncryptedSharedPreferences + Android Keystore).
 *
 * ## Récupération après corruption (audit #17)
 * L'instance [SharedPreferences] est tenue dans un holder réinitialisable (pas de `by lazy`,
 * qui mettait `null` en cache pour toute la durée du @Singleton) :
 * - si la création échoue (Keystore invalidé, keyset Tink illisible), [prefs] retourne null
 *   et la création est retentée au prochain accès ;
 * - [resetCorruptedStore] supprime le fichier chiffré ET l'alias MasterKey puis recrée un
 *   store vide. **Rien ne survit** (tokens, cookies, config WireGuard, SETUP_COMPLETED,
 *   local_token_*) — l'utilisateur doit rescanner le QR de setup.
 *
 * Le constructeur interne permet aux tests JVM d'injecter une factory de prefs et un
 * suppresseur d'alias (pas d'Android Keystore sous Robolectric). La production (Hilt,
 * via [com.tradingplatform.app.di.SecurityModule]) utilise le constructeur public.
 */
class EncryptedDataStore internal constructor(
    private val context: Context,
    private val prefsFactory: (Context) -> SharedPreferences,
    private val masterKeyDeleter: () -> Unit,
) {
    constructor(context: Context) : this(
        context,
        { ctx -> createEncryptedPrefs(ctx) },
        { deleteDefaultMasterKeyAlias() },
    )

    private val initLock = Any()

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    /**
     * Retourne l'instance courante, en la créant si nécessaire (double-checked locking).
     * null si le stockage chiffré est indisponible — la création sera retentée au prochain appel.
     */
    private fun prefs(): SharedPreferences? {
        cachedPrefs?.let { return it }
        return synchronized(initLock) {
            cachedPrefs ?: createPrefs().also { cachedPrefs = it }
        }
    }

    /** Appelé sous [initLock]. Ne logge jamais de valeur stockée. */
    private fun createPrefs(): SharedPreferences? = try {
        prefsFactory(context)
    } catch (e: GeneralSecurityException) {
        Timber.e(e, "EncryptedDataStore: MasterKey/keyset creation failed — encrypted storage unavailable")
        null
    } catch (e: IOException) {
        Timber.e(e, "EncryptedDataStore: IO error during init")
        null
    } catch (e: RuntimeException) {
        // security-crypto alpha / Tink lèvent SecurityException / IllegalStateException
        Timber.e(e, "EncryptedDataStore: runtime error during init — encrypted storage unavailable")
        null
    }

    /**
     * Réinitialise un store corrompu : supprime le fichier chiffré et l'alias MasterKey,
     * puis recrée un store vide. **Toutes les données sont perdues** (y compris la config
     * WireGuard et SETUP_COMPLETED) — l'appelant doit renvoyer l'utilisateur vers le Setup.
     *
     * @return true si un store fonctionnel a pu être recréé, false sinon.
     */
    suspend fun resetCorruptedStore(): Boolean = withContext(Dispatchers.IO) {
        synchronized(initLock) {
            // Détacher l'ancienne instance AVANT la suppression du fichier pour qu'aucun
            // lecteur concurrent ne la réutilise (prefs() repasse par le lock).
            cachedPrefs = null
            runCatching { context.deleteSharedPreferences(PREFS_NAME) }
                .onFailure { Timber.e(it, "EncryptedDataStore reset: failed to delete prefs file") }
            runCatching { masterKeyDeleter() }
                .onFailure { Timber.e(it, "EncryptedDataStore reset: failed to delete MasterKey alias") }
            val recreated = createPrefs()
            cachedPrefs = recreated
            Timber.w("EncryptedDataStore reset: store recreated=${recreated != null}")
            recreated != null
        }
    }

    // Clés dont la perte (apply asynchrone + kill app) est critique — utiliser commit = true.
    private val criticalKeys = setOf(
        DataStoreKeys.ACCESS_TOKEN.name,
        DataStoreKeys.WG_PRIVATE_KEY.name,
        DataStoreKeys.WG_CONFIG.name,
        DataStoreKeys.WG_ENDPOINT.name,
        DataStoreKeys.WG_SERVER_PUBKEY.name,
        DataStoreKeys.WG_TUNNEL_IP.name,
        DataStoreKeys.WG_DNS.name,
        DataStoreKeys.SETUP_COMPLETED.name,
    )

    // Clés préservées par clearSession() — identité device, pas session utilisateur.
    private val devicePersistentKeys = setOf(
        DataStoreKeys.WG_PRIVATE_KEY.name,
        DataStoreKeys.WG_CONFIG.name,
        DataStoreKeys.WG_ENDPOINT.name,
        DataStoreKeys.WG_SERVER_PUBKEY.name,
        DataStoreKeys.WG_TUNNEL_IP.name,
        DataStoreKeys.WG_DNS.name,
        DataStoreKeys.SETUP_COMPLETED.name,
        DataStoreKeys.DEFAULT_QUOTE_SYMBOL.name,
    )

    /**
     * Lit une valeur String de manière sécurisée.
     * Retourne null en cas de corruption (IOException ou GeneralSecurityException)
     * ou si le stockage chiffré est indisponible.
     * Si null est retourné suite à une exception auth : logout forcé vers LoginScreen.
     */
    suspend fun readString(key: Preferences.Key<String>): String? = withContext(Dispatchers.IO) {
        val prefs = prefs() ?: return@withContext null
        try {
            prefs.getString(key.name, null)
        } catch (e: IOException) {
            Timber.e(e, "EncryptedDataStore read error — file corrupted")
            null
        } catch (e: GeneralSecurityException) {
            Timber.e(e, "EncryptedDataStore Keystore invalidated (reboot/biometric reset)")
            null
        } catch (e: SecurityException) {
            // security-crypto alpha enveloppe les échecs de déchiffrement en SecurityException
            Timber.e(e, "EncryptedDataStore decrypt failed (SecurityException)")
            null
        }
    }

    /**
     * Lecture avec distinction des 3 cas : valeur presente, absente, ou corrompue (R1 fix).
     *
     * Contrairement a [readString] qui retourne null dans tous les cas d'echec,
     * cette methode permet a l'appelant de distinguer "jamais ecrit" de "Keystore invalide"
     * et d'afficher un message adapte a l'utilisateur.
     *
     * Backward-compatible : le code existant continue d'utiliser [readString].
     * Seuls les chemins critiques (AuthInterceptor, SessionManager) migrent vers cette methode.
     *
     * @return [SecureReadResult.Found] si la valeur existe, [SecureReadResult.NotFound] si absente,
     *         [SecureReadResult.Corrupted] si le Keystore est invalide ou le fichier corrompu.
     */
    suspend fun readStringSafe(
        key: Preferences.Key<String>,
    ): SecureReadResult<String> = withContext(Dispatchers.IO) {
        val prefs = prefs()
        if (prefs == null) {
            // Le store n'a pas pu etre initialise (MasterKey creation failure)
            return@withContext SecureReadResult.Corrupted(
                IllegalStateException("EncryptedDataStore unavailable — MasterKey creation failed")
            )
        }
        try {
            val value = prefs.getString(key.name, null)
            if (value != null) {
                SecureReadResult.Found(value)
            } else {
                SecureReadResult.NotFound
            }
        } catch (e: IOException) {
            Timber.e(e, "EncryptedDataStore readStringSafe — file corrupted")
            SecureReadResult.Corrupted(e)
        } catch (e: GeneralSecurityException) {
            Timber.e(e, "EncryptedDataStore readStringSafe — Keystore invalidated")
            SecureReadResult.Corrupted(e)
        } catch (e: SecurityException) {
            Timber.e(e, "EncryptedDataStore readStringSafe — decrypt failed (SecurityException)")
            SecureReadResult.Corrupted(e)
        }
    }

    /**
     * Équivalent de [readStringSafe] pour un Boolean : distingue présent / absent / corrompu.
     * Utile aux lecteurs qui doivent réagir à une corruption plutôt que de la confondre avec
     * "jamais écrit" (ex. widgets, état de setup).
     */
    suspend fun readBooleanSafe(
        key: Preferences.Key<Boolean>,
    ): SecureReadResult<Boolean> = withContext(Dispatchers.IO) {
        val prefs = prefs()
        if (prefs == null) {
            return@withContext SecureReadResult.Corrupted(
                IllegalStateException("EncryptedDataStore unavailable — MasterKey creation failed")
            )
        }
        try {
            if (prefs.contains(key.name)) {
                SecureReadResult.Found(prefs.getBoolean(key.name, false))
            } else {
                SecureReadResult.NotFound
            }
        } catch (e: IOException) {
            Timber.e(e, "EncryptedDataStore readBooleanSafe — file corrupted")
            SecureReadResult.Corrupted(e)
        } catch (e: GeneralSecurityException) {
            Timber.e(e, "EncryptedDataStore readBooleanSafe — Keystore invalidated")
            SecureReadResult.Corrupted(e)
        } catch (e: SecurityException) {
            Timber.e(e, "EncryptedDataStore readBooleanSafe — decrypt failed (SecurityException)")
            SecureReadResult.Corrupted(e)
        }
    }

    suspend fun readLong(key: Preferences.Key<Long>): Long? = withContext(Dispatchers.IO) {
        val prefs = prefs() ?: return@withContext null
        try {
            val v = prefs.getLong(key.name, Long.MIN_VALUE)
            if (v == Long.MIN_VALUE) null else v
        } catch (e: Exception) {
            Timber.e(e, "EncryptedDataStore read error")
            null
        }
    }

    suspend fun readInt(key: Preferences.Key<Int>): Int? = withContext(Dispatchers.IO) {
        val prefs = prefs() ?: return@withContext null
        try {
            val v = prefs.getInt(key.name, Int.MIN_VALUE)
            if (v == Int.MIN_VALUE) null else v
        } catch (e: Exception) {
            Timber.e(e, "EncryptedDataStore read error")
            null
        }
    }

    suspend fun readBoolean(key: Preferences.Key<Boolean>): Boolean? = withContext(Dispatchers.IO) {
        val prefs = prefs() ?: return@withContext null
        try {
            if (!prefs.contains(key.name)) null
            else prefs.getBoolean(key.name, false)
        } catch (e: Exception) {
            Timber.e(e, "EncryptedDataStore read error")
            null
        }
    }

    suspend fun writeString(key: Preferences.Key<String>, value: String) = withContext(Dispatchers.IO) {
        val prefs = prefs() ?: return@withContext
        val commit = key.name in criticalKeys
        prefs.edit(commit = commit) { putString(key.name, value) }
    }

    /** Écriture avec une clé String brute (pour les clés dynamiques, ex: "device_wg_pubkey_{id}"). */
    suspend fun writeString(key: String, value: String) = withContext(Dispatchers.IO) {
        val prefs = prefs() ?: return@withContext
        prefs.edit { putString(key, value) }
    }

    /** Lit une valeur String avec une clé String brute. */
    suspend fun readString(key: String): String? = withContext(Dispatchers.IO) {
        val prefs = prefs() ?: return@withContext null
        try {
            prefs.getString(key, null)
        } catch (e: IOException) {
            Timber.e(e, "EncryptedDataStore read error — file corrupted")
            null
        } catch (e: GeneralSecurityException) {
            Timber.e(e, "EncryptedDataStore Keystore invalidated (reboot/biometric reset)")
            null
        } catch (e: SecurityException) {
            Timber.e(e, "EncryptedDataStore decrypt failed (SecurityException)")
            null
        }
    }

    /**
     * Persiste le local_token associé à un device (pour la roue de secours LAN).
     * Le token n'est jamais loggé — [REDACTED].
     * Clé : "local_token_{deviceId}"
     * Commit synchrone : token critique utilisé pour le chiffrement LAN.
     */
    suspend fun writeLocalToken(deviceId: String, token: String) = withContext(Dispatchers.IO) {
        val prefs = prefs() ?: return@withContext
        prefs.edit(commit = true) { putString("local_token_$deviceId", token) }
    }

    /**
     * Lit le local_token associé à un device.
     * Retourne null si absent ou en cas d'erreur Keystore.
     */
    suspend fun readLocalToken(deviceId: String): String? = withContext(Dispatchers.IO) {
        val prefs = prefs() ?: return@withContext null
        try {
            prefs.getString("local_token_$deviceId", null)
        } catch (e: IOException) {
            Timber.e(e, "EncryptedDataStore readLocalToken error — file corrupted")
            null
        } catch (e: GeneralSecurityException) {
            Timber.e(e, "EncryptedDataStore readLocalToken — Keystore invalidated")
            null
        } catch (e: SecurityException) {
            Timber.e(e, "EncryptedDataStore readLocalToken — decrypt failed (SecurityException)")
            null
        }
    }

    suspend fun writeLong(key: Preferences.Key<Long>, value: Long) = withContext(Dispatchers.IO) {
        val prefs = prefs() ?: return@withContext
        prefs.edit { putLong(key.name, value) }
    }

    suspend fun writeInt(key: Preferences.Key<Int>, value: Int) = withContext(Dispatchers.IO) {
        val prefs = prefs() ?: return@withContext
        prefs.edit { putInt(key.name, value) }
    }

    suspend fun writeBoolean(key: Preferences.Key<Boolean>, value: Boolean) = withContext(Dispatchers.IO) {
        val prefs = prefs() ?: return@withContext
        val commit = key.name in criticalKeys
        prefs.edit(commit = commit) { putBoolean(key.name, value) }
    }

    suspend fun remove(key: Preferences.Key<*>) = withContext(Dispatchers.IO) {
        val prefs = prefs() ?: return@withContext
        prefs.edit { remove(key.name) }
    }

    /** Efface toutes les données (reset device complet — pas utilisé par le logout normal). */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        val prefs = prefs() ?: return@withContext
        prefs.edit { clear() }
    }

    /**
     * Efface uniquement les données de session (tokens, cookies, user identity).
     * Préserve les clés device-level (WG_*, SETUP_COMPLETED, DEFAULT_QUOTE_SYMBOL,
     * local_token_*) — un logout ne doit pas forcer un re-scan du QR d'onboarding.
     */
    suspend fun clearSession() = withContext(Dispatchers.IO) {
        val prefs = prefs() ?: return@withContext
        val toRemove = try {
            prefs.all.keys.filter { key ->
                key !in devicePersistentKeys && !key.startsWith("local_token_")
            }
        } catch (e: Exception) {
            Timber.e(e, "clearSession: enumeration failed — falling back to clearAll")
            prefs.edit(commit = true) { clear() }
            return@withContext
        }
        prefs.edit(commit = true) {
            toRemove.forEach { remove(it) }
        }
    }

    /**
     * Sauvegarde un cookie (pour EncryptedCookieJar).
     * Commit synchrone : le refresh_token est critique — une perte entraîne un logout forcé.
     */
    suspend fun saveCookie(name: String, value: String) = withContext(Dispatchers.IO) {
        val prefs = prefs() ?: return@withContext
        prefs.edit(commit = true) { putString("cookie_$name", value) }
    }

    /** Charge tous les cookies sauvegardés */
    suspend fun loadCookies(): List<String> = withContext(Dispatchers.IO) {
        val prefs = prefs() ?: return@withContext emptyList()
        try {
            prefs.all
                .filter { (key, _) -> key.startsWith("cookie_") }
                .values
                .filterIsInstance<String>()
        } catch (e: Exception) {
            Timber.e(e, "EncryptedDataStore loadCookies error")
            emptyList()
        }
    }
}
