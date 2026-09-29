package com.tradingplatform.app.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.domain.model.Portfolio
import com.tradingplatform.app.domain.usecase.portfolio.ObserveActivePortfolioUseCase
import com.tradingplatform.app.domain.usecase.portfolio.ObservePortfoliosUseCase
import com.tradingplatform.app.domain.usecase.portfolio.SelectPortfolioUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * État du sélecteur de portefeuille ([PortfolioSwitcher]) : liste des portefeuilles du compte,
 * id du portefeuille actif et action de sélection.
 *
 * Aucune logique métier : la liste vient de [ObservePortfoliosUseCase] (peuplée par le
 * `RefreshPortfoliosUseCase` du login / de l'Accueil — vide tant que personne ne l'a appelé), l'id
 * actif de [ObserveActivePortfolioUseCase], et la sélection est déléguée à [SelectPortfolioUseCase]
 * (qui purge les caches portfolio-scopés puis émet le nouvel id : les écrans abonnés à
 * `ObserveActivePortfolioUseCase` se remettent à zéro et se rechargent).
 */
@HiltViewModel
class PortfolioSwitcherViewModel @Inject constructor(
    observePortfoliosUseCase: ObservePortfoliosUseCase,
    observeActivePortfolioUseCase: ObserveActivePortfolioUseCase,
    private val selectPortfolioUseCase: SelectPortfolioUseCase,
) : ViewModel() {

    /** Portefeuilles du compte ; le sélecteur n'est affiché qu'à partir de 2. */
    val portfolios: StateFlow<List<Portfolio>> = observePortfoliosUseCase()

    /**
     * Id du portefeuille actif ; `null` tant qu'il n'est pas connu. Collecté dès la création du
     * ViewModel (`Eagerly`) : la source est un simple StateFlow, le coût est nul.
     */
    val activeId: StateFlow<String?> = observeActivePortfolioUseCase()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    /**
     * Change le portefeuille actif. Sélectionner l'id déjà actif est un no-op côté use case. Un
     * échec (id absent de la liste connue) est seulement journalisé : la liste et l'id actif
     * restent inchangés, donc le sélecteur continue d'afficher la vérité.
     */
    fun select(portfolioId: String) {
        viewModelScope.launch {
            selectPortfolioUseCase(portfolioId).onFailure { e ->
                Timber.w("Portfolio selection failed (%s)", e.javaClass.simpleName)
            }
        }
    }
}
