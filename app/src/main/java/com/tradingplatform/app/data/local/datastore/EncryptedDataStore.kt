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

class EncryptedDataStore(
    private val context: Context,
) {
    private val sharedPreferences: SharedPreferences? by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "trading_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: GeneralSecurityException) {
            Timber.e(e, "EncryptedDataStore: MasterKey creation failed — encrypted storage unavailable")
            null
        } catch (e: IOException) {
            Timber.e(e, "EncryptedDataStore: IO error during init")
            null
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
        val prefs = sharedPreferences ?: return@withContext null
        try {
            prefs.getString(key.name, null)
        } catch (e: IOException) {
            Timber.e(e, "EncryptedDataStore read error — file corrupted")
            null
        } catch (e: GeneralSecurityException) {
            Timber.e(e, "EncryptedDataStore Keystore invalidated (reboot/biometric reset)")
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
        val prefs = sharedPreferences
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
        }
    }

    suspend fun readLong(key: Preferences.Key<Long>): Long? = withContext(Dispatchers.IO) {
        val prefs = sharedPreferences ?: return@withContext null
        try {
            val v = prefs.getLong(key.name, Long.MIN_VALUE)
            if (v == Long.MIN_VALUE) null else v
        } catch (e: Exception) {
            Timber.e(e, "EncryptedDataStore read error")
            null
        }
    }

    suspend fun readInt(key: Preferences.Key<Int>): Int? = withContext(Dispatchers.IO) {
        val prefs = sharedPreferences ?: return@withContext null
        try {
            val v = prefs.getInt(key.name, Int.MIN_VALUE)
            if (v == Int.MIN_VALUE) null else v
        } catch (e: Exception) {
            Timber.e(e, "EncryptedDataStore read error")
            null
        }
    }

    suspend fun readBoolean(key: Preferences.Key<Boolean>): Boolean? = withContext(Dispatchers.IO) {
        val prefs = sharedPreferences ?: return@withContext null
        try {
            if (!prefs.contains(key.name)) null
            else prefs.getBoolean(key.name, false)
        } catch (e: Exception) {
            Timber.e(e, "EncryptedDataStore read error")
            null
        }
    }

    suspend fun writeString(key: Preferences.Key<String>, value: String) = withContext(Dispatchers.IO) {
        val prefs = sharedPreferences ?: return@withContext
        val commit = key.name in criticalKeys
        prefs.edit(commit = commit) { putString(key.name, value) }
    }

    /** Écriture avec une clé String brute (pour les clés dynamiques, ex: "device_wg_pubkey_{id}"). */
    suspend fun writeString(key: String, value: String) = withContext(Dispatchers.IO) {
        val prefs = sharedPreferences ?: return@withContext
        prefs.edit { putString(key, value) }
    }

    /** Lit une valeur String avec une clé String brute. */
    suspend fun readString(key: String): String? = withContext(Dispatchers.IO) {
        val prefs = sharedPreferences ?: return@withContext null
        try {
            prefs.getString(key, null)
        } catch (e: IOException) {
            Timber.e(e, "EncryptedDataStore read error — file corrupted")
            null
        } catch (e: GeneralSecurityException) {
            Timber.e(e, "EncryptedDataStore Keystore invalidated (reboot/biometric reset)")
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
        val prefs = sharedPreferences ?: return@withContext
        prefs.edit(commit = true) { putString("local_token_$deviceId", token) }
    }

    /**
     * Lit le local_token associé à un device.
     * Retourne null si absent ou en cas d'erreur Keystore.
     */
    suspend fun readLocalToken(deviceId: String): String? = withContext(Dispatchers.IO) {
        val prefs = sharedPreferences ?: return@withContext null
        try {
            prefs.getString("local_token_$deviceId", null)
        } catch (e: IOException) {
            Timber.e(e, "EncryptedDataStore readLocalToken error — file corrupted")
            null
        } catch (e: GeneralSecurityException) {
            Timber.e(e, "EncryptedDataStore readLocalToken — Keystore invalidated")
            null
        }
    }

    suspend fun writeLong(key: Preferences.Key<Long>, value: Long) = withContext(Dispatchers.IO) {
        val prefs = sharedPreferences ?: return@withContext
        prefs.edit { putLong(key.name, value) }
    }

    suspend fun writeInt(key: Preferences.Key<Int>, value: Int) = withContext(Dispatchers.IO) {
        val prefs = sharedPreferences ?: return@withContext
        prefs.edit { putInt(key.name, value) }
    }

    suspend fun writeBoolean(key: Preferences.Key<Boolean>, value: Boolean) = withContext(Dispatchers.IO) {
        val prefs = sharedPreferences ?: return@withContext
        val commit = key.name in criticalKeys
        prefs.edit(commit = commit) { putBoolean(key.name, value) }
    }

    suspend fun remove(key: Preferences.Key<*>) = withContext(Dispatchers.IO) {
        val prefs = sharedPreferences ?: return@withContext
        prefs.edit { remove(key.name) }
    }

    /** Efface toutes les données (reset device complet — pas utilisé par le logout normal). */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        val prefs = sharedPreferences ?: return@withContext
        prefs.edit { clear() }
    }

    /**
     * Efface uniquement les données de session (tokens, cookies, user identity).
     * Préserve les clés device-level (WG_*, SETUP_COMPLETED, DEFAULT_QUOTE_SYMBOL,
     * local_token_*) — un logout ne doit pas forcer un re-scan du QR d'onboarding.
     */
    suspend fun clearSession() = withContext(Dispatchers.IO) {
        val prefs = sharedPreferences ?: return@withContext
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
        val prefs = sharedPreferences ?: return@withContext
        prefs.edit(commit = true) { putString("cookie_$name", value) }
    }

    /** Charge tous les cookies sauvegardés */
    suspend fun loadCookies(): List<String> = withContext(Dispatchers.IO) {
        val prefs = sharedPreferences ?: return@withContext emptyList()
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
