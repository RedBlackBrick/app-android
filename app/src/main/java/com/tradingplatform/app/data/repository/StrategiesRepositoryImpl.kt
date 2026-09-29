package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.api.PortfolioStrategyActiveUpdateDto
import com.tradingplatform.app.data.api.StrategiesApi
import com.tradingplatform.app.data.api.StrategiesWriteApi
import com.tradingplatform.app.data.api.StrategyListItemDto
import com.tradingplatform.app.data.api.StrategyListResponseDto
import com.tradingplatform.app.domain.model.PortfolioStrategyEntry
import com.tradingplatform.app.domain.model.PortfolioStrategyLink
import com.tradingplatform.app.domain.model.StrategyName
import com.tradingplatform.app.domain.model.WriteOutcome
import com.tradingplatform.app.domain.repository.StrategiesRepository
import com.tradingplatform.app.domain.util.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StrategiesRepositoryImpl @Inject constructor(
    private val api: StrategiesApi,
    private val writeApi: StrategiesWriteApi,
) : StrategiesRepository {

    override suspend fun listPortfolioStrategies(
        portfolioId: String,
    ): Result<List<PortfolioStrategyLink>> = runCatchingCancellable {
        requestLinks(portfolioId)
    }

    override suspend fun listPortfolioStrategyEntries(
        portfolioId: String,
    ): Result<List<PortfolioStrategyEntry>> = runCatchingCancellable {
        val links = requestLinks(portfolioId)
        if (links.isEmpty()) {
            emptyList<PortfolioStrategyEntry>()
        } else {
            val names = fetchStrategyNames(links.map { it.strategyId }.toSet())
                .associate { it.id to it.name }
            links.map { link ->
                PortfolioStrategyEntry(
                    strategyId = link.strategyId,
                    name = names[link.strategyId],
                    isActive = link.isActive,
                )
            }
        }
    }

    override suspend fun setPortfolioStrategyActive(
        portfolioId: String,
        strategyId: String,
        active: Boolean,
    ): Result<WriteOutcome> {
        if (portfolioId.isBlank() || strategyId.isBlank()) {
            return Result.failure(IllegalArgumentException("Portfolio and strategy ids are required"))
        }
        return OrdersStrategiesWriteSupport.execute(
            endpoint = SET_ACTIVE_ENDPOINT,
            conflictMessage = "Conflit : l'état de cette stratégie a changé, actualisez avant de réessayer",
        ) {
            writeApi.setActive(portfolioId, strategyId, PortfolioStrategyActiveUpdateDto(isActive = active))
        }
    }

    private suspend fun requestLinks(portfolioId: String): List<PortfolioStrategyLink> {
        val response = api.listPortfolioStrategies(portfolioId)
        if (!response.isSuccessful) {
            error("List portfolio strategies failed: HTTP ${response.code()}")
        }
        return response.body()?.map {
            PortfolioStrategyLink(
                portfolioId = it.portfolioId,
                strategyId = it.strategyId,
                isActive = it.isActive,
            )
        } ?: emptyList()
    }

    /**
     * Parcourt le catalogue `GET /v1/strategies` (paginé, `limit` maximal demandé) et fusionne les
     * pages jusqu'à avoir trouvé toutes les stratégies de [wanted], jusqu'à épuisement du catalogue
     * (`offset >= total`), ou jusqu'à [MAX_CATALOG_SCAN] stratégies parcourues (borne de sécurité).
     *
     * Dégradation : une page en échec (réseau, HTTP non-2xx, JSON illisible) interrompt le parcours
     * et renvoie les noms déjà obtenus — les entrées sans nom auront `name = null`. Seule
     * l'annulation de coroutine se propage.
     */
    private suspend fun fetchStrategyNames(wanted: Set<String>): List<StrategyName> {
        val remaining = wanted.toMutableSet()
        val found = mutableListOf<StrategyName>()
        var offset = 0
        while (remaining.isNotEmpty() && offset < MAX_CATALOG_SCAN) {
            val limit = minOf(CATALOG_PAGE_LIMIT, MAX_CATALOG_SCAN - offset)
            val page = runCatchingCancellable { requestCatalogPage(limit = limit, offset = offset) }
                .getOrNull() ?: break
            if (page.items.isEmpty()) break
            for (item in page.items) {
                val strategyName = toStrategyName(item) ?: continue
                found += strategyName
                remaining.remove(strategyName.id)
            }
            offset += page.items.size
            if (offset >= page.total) break
        }
        return found
    }

    private suspend fun requestCatalogPage(limit: Int, offset: Int): StrategyListResponseDto {
        val response = api.listStrategies(limit = limit, offset = offset)
        if (!response.isSuccessful) {
            error("List strategies failed: HTTP ${response.code()}")
        }
        return response.body() ?: error("List strategies returned an empty body")
    }

    private fun toStrategyName(dto: StrategyListItemDto): StrategyName? {
        val name = dto.name?.takeIf { it.isNotBlank() } ?: return null
        if (dto.id.isBlank()) return null
        return StrategyName(id = dto.id, name = name)
    }

    private companion object {
        // `limit` maximal accepté par GET /v1/strategies (1..200, défaut 50).
        const val CATALOG_PAGE_LIMIT = 200

        // Borne de sécurité : nombre maximal de stratégies parcourues dans le catalogue.
        const val MAX_CATALOG_SCAN = 500

        // Gabarit (jamais les identifiants réels) : utilisé dans HttpStatusException et les logs.
        const val SET_ACTIVE_ENDPOINT = "v1/portfolios/{portfolio_id}/strategies/{strategy_id}"
    }
}
