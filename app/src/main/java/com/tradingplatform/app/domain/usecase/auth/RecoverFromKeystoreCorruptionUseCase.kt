package com.tradingplatform.app.domain.usecase.auth

import com.tradingplatform.app.data.api.interceptor.CsrfInterceptor
import com.tradingplatform.app.data.api.interceptor.EncryptedCookieJar
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.data.local.db.AppDatabase
import com.tradingplatform.app.data.session.TokenHolder
import com.tradingplatform.app.data.websocket.PrivateWsClient
import com.tradingplatform.app.domain.util.runCatchingCancellable
import com.tradingplatform.app.security.BiometricLockManager
import com.tradingplatform.app.vpn.WireGuardManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * Récupération après corruption du stockage chiffré (audit #17).
 *
 * Teardown complet de l'état local puis reset de [EncryptedDataStore] :
 * 1. ferme le WebSocket privé et le tunnel WireGuard (leur config est perdue) ;
 * 2. vide les caches mémoire (access token, refresh cookie, token CSRF) ;
 * 3. vide Room (données de la session précédente) ;
 * 4. supprime le fichier chiffré + l'alias MasterKey et recrée un store vide ;
 * 5. lève le verrou biométrique (plus aucune donnée à protéger, et l'état persisté est perdu).
 *
 * **Rien ne survit** : clés WireGuard, SETUP_COMPLETED, tokens, cookies, local_token_*.
 * L'appelant doit renvoyer l'utilisateur vers l'écran Setup (rescan du QR).
 *
 * Note d'architecture : comme [LogoutUseCase] (qui vide déjà Room) et
 * `ProvisionMobileVpnUseCase` (qui pilote [WireGuardManager]), ce use case touche
 * directement des composants data/infra — il n'existe pas d'interface domaine pour ce
 * teardown transverse, et en créer une uniquement pour ce chemin de secours serait du
 * sur-découpage.
 *
 * @return true si un store chiffré fonctionnel a été recréé ; false si le stockage reste
 *         indisponible (l'UI doit alors l'indiquer et permettre de réessayer).
 */
class RecoverFromKeystoreCorruptionUseCase @Inject constructor(
    private val privateWsClient: PrivateWsClient,
    private val wireGuardManager: WireGuardManager,
    private val tokenHolder: TokenHolder,
    private val cookieJar: EncryptedCookieJar,
    private val csrfInterceptor: CsrfInterceptor,
    private val appDatabase: AppDatabase,
    private val dataStore: EncryptedDataStore,
    private val biometricLockManager: BiometricLockManager,
) {
    suspend operator fun invoke(): Boolean {
        Timber.w("RecoverFromKeystoreCorruption: tearing down local state")
        runCatchingCancellable { privateWsClient.disconnect() }
            .onFailure { Timber.w(it, "RecoverFromKeystoreCorruption: WS disconnect failed") }
        runCatchingCancellable { wireGuardManager.disconnect() }
            .onFailure { Timber.w(it, "RecoverFromKeystoreCorruption: VPN disconnect failed") }

        // Caches mémoire vidés AVANT le reset disque — aucun intercepteur ne doit réutiliser
        // un token de la session corrompue.
        tokenHolder.clear()
        cookieJar.clear()
        csrfInterceptor.clearToken()

        // Room.clearAllTables() est bloquant — IO obligatoire (même pattern que LogoutUseCase).
        withContext(Dispatchers.IO) {
            try {
                appDatabase.clearAllTables()
            } catch (e: Exception) {
                Timber.e(e, "RecoverFromKeystoreCorruption: clearAllTables failed")
            }
        }

        val recovered = dataStore.resetCorruptedStore()
        if (recovered) {
            biometricLockManager.unlock()
        }
        Timber.w("RecoverFromKeystoreCorruption: recovered=$recovered")
        return recovered
    }
}
