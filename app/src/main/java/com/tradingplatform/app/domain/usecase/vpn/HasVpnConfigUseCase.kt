package com.tradingplatform.app.domain.usecase.vpn

import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import javax.inject.Inject

/**
 * `true` si une configuration WireGuard complète est persistée : les quatre clés que
 * `WireGuardManager.reconnect()` exige (clé privée, endpoint, clé publique du serveur, IP du
 * tunnel). Ces clés survivent au logout (`clearSession()`), donc l'écran de connexion peut
 * proposer « Activer le VPN ». Aucune valeur n'est exposée ni loggée — seulement leur présence.
 *
 * Suit le précédent de [com.tradingplatform.app.domain.usecase.auth.GetPortfolioIdUseCase] :
 * le ViewModel ne touche pas `EncryptedDataStore`.
 */
class HasVpnConfigUseCase @Inject constructor(
    private val dataStore: EncryptedDataStore,
) {
    suspend operator fun invoke(): Boolean =
        !dataStore.readString(DataStoreKeys.WG_PRIVATE_KEY).isNullOrBlank() &&
            !dataStore.readString(DataStoreKeys.WG_ENDPOINT).isNullOrBlank() &&
            !dataStore.readString(DataStoreKeys.WG_SERVER_PUBKEY).isNullOrBlank() &&
            !dataStore.readString(DataStoreKeys.WG_TUNNEL_IP).isNullOrBlank()
}
