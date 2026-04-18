package com.tradingplatform.app.domain.usecase.market

import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.domain.model.AppDefaults
import com.tradingplatform.app.domain.repository.WatchlistRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Résout le symbole par défaut à afficher sur le Dashboard et à pré-synchroniser
 * côté widgets.
 *
 * Priorité de résolution :
 *   1. [DataStoreKeys.DEFAULT_QUOTE_SYMBOL] — préférence utilisateur explicite
 *      (configurable via les écrans de Settings)
 *   2. Premier symbole de la watchlist — le symbole que l'utilisateur a déjà choisi
 *      de suivre est un fallback raisonnable avant de retomber sur la constante
 *   3. [AppDefaults.DEFAULT_QUOTE_SYMBOL] — valeur hardcodée ("AAPL"), utilisée
 *      uniquement si rien d'autre n'est disponible (premier lancement, watchlist vide)
 *
 * Remplace les lectures directes de [AppDefaults.DEFAULT_QUOTE_SYMBOL] dans le Dashboard
 * et le Worker widgets (résout le TODO d'[AppDefaults]).
 */
class GetDefaultQuoteSymbolUseCase @Inject constructor(
    private val encryptedDataStore: EncryptedDataStore,
    private val watchlistRepository: WatchlistRepository,
) {
    suspend operator fun invoke(): String {
        encryptedDataStore.readString(DataStoreKeys.DEFAULT_QUOTE_SYMBOL)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        watchlistRepository.getWatchlist().first().firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        return AppDefaults.DEFAULT_QUOTE_SYMBOL
    }
}
