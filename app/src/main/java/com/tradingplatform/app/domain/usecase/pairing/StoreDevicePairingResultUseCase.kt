package com.tradingplatform.app.domain.usecase.pairing

import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import timber.log.Timber
import javax.inject.Inject

class StoreDevicePairingResultUseCase @Inject constructor(
    private val dataStore: EncryptedDataStore,
) {
    /**
     * Persiste les données de pairing d'un device après confirmation réussie.
     *
     * - local_token : utilisé pour le chiffrement LAN futur (roue de secours)
     * - wgPubkey    : clé publique Curve25519 du device (chiffrement SealedBox)
     * - localIp     : IP LAN du device (accès maintenance locale)
     *
     * Le localToken n'est jamais loggé — [REDACTED].
     */
    suspend operator fun invoke(
        deviceId: String,
        localToken: String,
        wgPubkey: String,
        localIp: String,
    ): Result<Unit> = runCatching {
        Timber.d("StoreDevicePairingResult: deviceId=$deviceId token=[REDACTED] ip=$localIp")
        dataStore.writeLocalToken(deviceId, localToken)
        dataStore.writeString("device_wg_pubkey_$deviceId", wgPubkey)
        dataStore.writeString("device_local_ip_$deviceId", localIp)
    }
}
