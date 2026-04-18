package com.tradingplatform.app.domain.model

/**
 * Valeurs par défaut applicatives partagées entre les composants.
 *
 * [DEFAULT_QUOTE_SYMBOL] est le fallback final quand aucune préférence utilisateur
 * n'est enregistrée ET que la watchlist est vide. La résolution complète se fait via
 * `GetDefaultQuoteSymbolUseCase` (domain/usecase/market) :
 *   1. `DataStoreKeys.DEFAULT_QUOTE_SYMBOL` si l'utilisateur a explicitement choisi
 *   2. premier symbole de la watchlist sinon
 *   3. [DEFAULT_QUOTE_SYMBOL] en dernier recours
 *
 * Ne pas lire cette constante directement depuis un ViewModel ou un Worker — passer
 * par `GetDefaultQuoteSymbolUseCase`. L'UI de configuration vit dans ProfileScreen.
 */
object AppDefaults {
    const val DEFAULT_QUOTE_SYMBOL = "AAPL"
}
