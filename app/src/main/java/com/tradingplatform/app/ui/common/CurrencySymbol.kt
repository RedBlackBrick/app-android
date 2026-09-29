package com.tradingplatform.app.ui.common

import androidx.compose.runtime.compositionLocalOf

/**
 * Symbole monétaire du portefeuille ACTIF (« € », « $ », « £ »…), fourni une fois pour toute l'app
 * par `AppNavGraph` à partir du portefeuille sélectionné. Valeur par défaut des composants de
 * montant (`MoneyText`, `PnlText`, `AnimatedPnlText`) : un compte multi-portefeuilles peut mêler
 * EUR et USD, un « € » codé en dur affichait des dollars comme des euros.
 *
 * Ne concerne PAS les cours de marché (`AnimatedPriceText`, écran Marchés) : la devise d'un
 * cours est celle de la place de cotation, pas celle du portefeuille.
 */
val LocalCurrencySymbol = compositionLocalOf { "€" }
