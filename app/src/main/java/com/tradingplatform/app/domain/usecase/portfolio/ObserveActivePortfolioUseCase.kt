package com.tradingplatform.app.domain.usecase.portfolio

import com.tradingplatform.app.domain.repository.PortfolioSelectionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import javax.inject.Inject

/**
 * Id du portefeuille actif, en flux : émet dès qu'il est connu (initialisé depuis le stockage
 * chiffré au démarrage) puis à chaque changement de sélection. N'émet jamais `null`.
 *
 * Les ViewModels portfolio-scopés (Positions, Ordres, Historique, Performance…) collectent ce flux
 * et rechargent leurs données à chaque nouvel id, au lieu de lire `PORTFOLIO_ID` une seule fois.
 */
class ObserveActivePortfolioUseCase @Inject constructor(
    private val repo: PortfolioSelectionRepository,
) {
    operator fun invoke(): Flow<String> =
        repo.activePortfolioId.filterNotNull().distinctUntilChanged()
}
