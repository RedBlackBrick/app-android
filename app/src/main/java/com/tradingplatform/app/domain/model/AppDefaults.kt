package com.tradingplatform.app.domain.model

/**
 * Valeurs par défaut applicatives partagées entre les composants.
 * Le symbole par défaut est utilisé pour le polling de cours sur le Dashboard
 * et pour le sync initial des quotes dans WidgetUpdateWorker.
 *
 * TODO: Rendre configurable via les Settings utilisateur (clé EncryptedDataStore "default_quote_symbol")
 */
object AppDefaults {
    const val DEFAULT_QUOTE_SYMBOL = "AAPL"
}
