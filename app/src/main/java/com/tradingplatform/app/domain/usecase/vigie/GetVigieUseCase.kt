package com.tradingplatform.app.domain.usecase.vigie

import com.tradingplatform.app.domain.model.Cached
import com.tradingplatform.app.domain.model.Vigie
import com.tradingplatform.app.domain.repository.VigieRepository
import javax.inject.Inject

/**
 * Vigie « Refuge » (score, familles, indicateurs, régime, refuge, courbe 90 j) avec son vrai
 * `syncedAt`. Sans réglage de compte : utilisée par l'écran détail et le widget, qui ne dépendent
 * PAS de `showVigie` (la carte Accueil passe par [GetHomeVigieUseCase]).
 *
 * @param forceRefresh `true` (pull-to-refresh / « Réessayer ») ignore la fraîcheur du cache.
 */
class GetVigieUseCase @Inject constructor(
    private val repository: VigieRepository,
) {
    suspend operator fun invoke(forceRefresh: Boolean = false): Result<Cached<Vigie>> =
        repository.getVigie(forceRefresh)
}
