package com.tradingplatform.app.data.local.datastore

import android.content.Context
import android.content.SharedPreferences
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
    // Cookies : clé dynamique "cookie_${name}"
}

class EncryptedDataStore(
    private val context: Context,
) {
    private val sharedPreferences: SharedPreferences by lazy {
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
            Timber.e(e, "EncryptedDataStore: MasterKey creation failed")
            throw e
        }
    }

    /**
     * Lit une valeur String de manière sécurisée.
     * Retourne null en cas de corruption (IOException ou GeneralSecurityException).
     * Si null est retourné suite à une exception auth : logout forcé vers LoginScreen.
     */
    suspend fun readString(key: Preferences.Key<String>): String? = withContext(Dispatchers.IO) {
        try {
            sharedPreferences.getString(key.name, null)
        } catch (e: IOException) {
            Timber.e(e, "EncryptedDataStore read error — file corrupted")
            null
        } catch (e: GeneralSecurityException) {
            Timber.e(e, "EncryptedDataStore Keystore invalidated (reboot/biometric reset)")
            null
        }
    }

    suspend fun readLong(key: Preferences.Key<Long>): Long? = withContext(Dispatchers.IO) {
        try {
            val v = sharedPreferences.getLong(key.name, Long.MIN_VALUE)
            if (v == Long.MIN_VALUE) null else v
        } catch (e: Exception) {
            Timber.e(e, "EncryptedDataStore read error")
            null
        }
    }

    suspend fun readInt(key: Preferences.Key<Int>): Int? = withContext(Dispatchers.IO) {
        try {
            val v = sharedPreferences.getInt(key.name, Int.MIN_VALUE)
            if (v == Int.MIN_VALUE) null else v
        } catch (e: Exception) {
            Timber.e(e, "EncryptedDataStore read error")
            null
        }
    }

    suspend fun readBoolean(key: Preferences.Key<Boolean>): Boolean? = withContext(Dispatchers.IO) {
        try {
            if (!sharedPreferences.contains(key.name)) null
            else sharedPreferences.getBoolean(key.name, false)
        } catch (e: Exception) {
            Timber.e(e, "EncryptedDataStore read error")
            null
        }
    }

    suspend fun writeString(key: Preferences.Key<String>, value: String) = withContext(Dispatchers.IO) {
        sharedPreferences.edit().putString(key.name, value).apply()
    }

    /** Écriture avec une clé String brute (pour les clés dynamiques, ex: "device_wg_pubkey_{id}"). */
    suspend fun writeString(key: String, value: String) = withContext(Dispatchers.IO) {
        sharedPreferences.edit().putString(key, value).apply()
    }

    /** Lit une valeur String avec une clé String brute. */
    suspend fun readString(key: String): String? = withContext(Dispatchers.IO) {
        try {
            sharedPreferences.getString(key, null)
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
     */
    suspend fun writeLocalToken(deviceId: String, token: String) = withContext(Dispatchers.IO) {
        sharedPreferences.edit().putString("local_token_$deviceId", token).apply()
    }

    /**
     * Lit le local_token associé à un device.
     * Retourne null si absent ou en cas d'erreur Keystore.
     */
    suspend fun readLocalToken(deviceId: String): String? = withContext(Dispatchers.IO) {
        try {
            sharedPreferences.getString("local_token_$deviceId", null)
        } catch (e: IOException) {
            Timber.e(e, "EncryptedDataStore readLocalToken error — file corrupted")
            null
        } catch (e: GeneralSecurityException) {
            Timber.e(e, "EncryptedDataStore readLocalToken — Keystore invalidated")
            null
        }
    }

    suspend fun writeLong(key: Preferences.Key<Long>, value: Long) = withContext(Dispatchers.IO) {
        sharedPreferences.edit().putLong(key.name, value).apply()
    }

    suspend fun writeInt(key: Preferences.Key<Int>, value: Int) = withContext(Dispatchers.IO) {
        sharedPreferences.edit().putInt(key.name, value).apply()
    }

    suspend fun writeBoolean(key: Preferences.Key<Boolean>, value: Boolean) = withContext(Dispatchers.IO) {
        sharedPreferences.edit().putBoolean(key.name, value).apply()
    }

    suspend fun remove(key: Preferences.Key<*>) = withContext(Dispatchers.IO) {
        sharedPreferences.edit().remove(key.name).apply()
    }

    /** Efface toutes les données (logout) */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        sharedPreferences.edit().clear().apply()
    }

    /** Sauvegarde un cookie (pour EncryptedCookieJar) */
    suspend fun saveCookie(name: String, value: String) = withContext(Dispatchers.IO) {
        sharedPreferences.edit().putString("cookie_$name", value).apply()
    }

    /** Charge tous les cookies sauvegardés */
    suspend fun loadCookies(): List<String> = withContext(Dispatchers.IO) {
        try {
            sharedPreferences.all
                .filter { (key, _) -> key.startsWith("cookie_") }
                .values
                .filterIsInstance<String>()
        } catch (e: Exception) {
            Timber.e(e, "EncryptedDataStore loadCookies error")
            emptyList()
        }
    }
}
