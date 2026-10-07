package com.tradingplatform.app.domain.usecase.vigie

import com.tradingplatform.app.domain.model.HomeVigie
import javax.inject.Inject

/**
 * Carte Vigie de l'Accueil : n'appelle `v1/vigie` QUE si le réglage de compte `showVigie` est vrai.
 *
 * - réglage faux (ou absent) → `success(Hidden)`, AUCUN appel Vigie ;
 * - réglage illisible (réseau, VPN) → `failure` : l'appelant ne montre rien de nouveau (une carte
 *   déjà affichée garde sa dernière valeur) — on ne devine pas le réglage ;
 * - réglage vrai → `success(Shown(valeur en cache ou fraîche))`, ou `failure` sans aucun cache.
 */
class GetHomeVigieUseCase @Inject constructor(
    private val getShowVigie: GetShowVigieUseCase,
    private val getVigie: GetVigieUseCase,
) {
    suspend operator fun invoke(forceRefresh: Boolean = false): Result<HomeVigie> {
        val show = getShowVigie().getOrElse { return Result.failure(it) }
        if (!show) return Result.success(HomeVigie.Hidden)
        return getVigie(forceRefresh).map { HomeVigie.Shown(it) }
    }
}
