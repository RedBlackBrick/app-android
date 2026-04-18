package com.tradingplatform.app.domain.usecase.market

import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import javax.inject.Inject

/**
 * Persiste le symbole par défaut choisi par l'utilisateur dans [EncryptedDataStore].
 *
 * Symbole vide ou blank → clear de la préférence (retour au fallback watchlist/AppDefaults).
 * La validation stricte (symbole autorisé côté backend) est laissée à l'appelant —
 * l'UI de settings doit refuser un input invalide avant d'appeler ce UseCase.
 */
class SetDefaultQuoteSymbolUseCase @Inject constructor(
    private val encryptedDataStore: EncryptedDataStore,
) {
    suspend operator fun invoke(symbol: String): Result<Unit> = runCatching {
        val normalized = symbol.uppercase().trim()
        if (normalized.isEmpty()) {
            encryptedDataStore.remove(DataStoreKeys.DEFAULT_QUOTE_SYMBOL)
        } else {
            encryptedDataStore.writeString(DataStoreKeys.DEFAULT_QUOTE_SYMBOL, normalized)
        }
    }
}
