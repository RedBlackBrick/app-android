package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.api.KillSwitchActiveDto
import com.tradingplatform.app.data.api.PortfolioKillSwitchActivateRequestDto
import com.tradingplatform.app.data.api.Risk360KillSwitchDto
import com.tradingplatform.app.data.api.Risk360SummaryDto
import com.tradingplatform.app.data.api.RiskApi
import com.tradingplatform.app.data.api.RiskViolationDto
import com.tradingplatform.app.data.api.RiskWriteApi
import com.tradingplatform.app.domain.exception.HttpStatusException
import com.tradingplatform.app.domain.model.CircuitBreakerState
import com.tradingplatform.app.domain.model.PortfolioCircuitBreakerStatus
import com.tradingplatform.app.domain.model.RiskStatus
import com.tradingplatform.app.domain.model.WriteOutcome
import com.tradingplatform.app.domain.repository.RiskRepository
import com.tradingplatform.app.domain.util.runCatchingCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RiskRepositoryImpl @Inject constructor(
    private val api: RiskApi,
    private val writeApi: RiskWriteApi,
) : RiskRepository {

    override suspend fun getPortfolioCircuitBreakerStatus(
        portfolioId: String,
    ): Result<PortfolioCircuitBreakerStatus> = runCatchingCancellable {
        val response = api.getPortfolioCircuitBreakerStatus(portfolioId)
        if (!response.isSuccessful) {
            error("Get portfolio circuit-breaker status failed: HTTP ${response.code()}")
        }
        val dto = response.body() ?: error("Empty circuit-breaker status response")
        PortfolioCircuitBreakerStatus(
            portfolioId = dto.portfolioId,
            enabled = dto.enabled,
            state = CircuitBreakerState.fromWire(dto.state),
            count = dto.count,
            threshold = dto.threshold,
            windowSeconds = dto.windowSeconds,
            ttlSeconds = dto.ttlSeconds,
            redisUnavailable = dto.redisUnavailable,
        )
    }

    override suspend fun getRiskStatus(portfolioId: String): Result<RiskStatus> = coroutineScope {
        // Trois lectures indépendantes en parallèle. Chacune est enveloppée : un échec ne fait pas
        // tomber les deux autres (seule une annulation de coroutine se propage).
        val killSwitch = async { runCatchingCancellable { fetchKillSwitchActive() } }
        val summary = async { runCatchingCancellable { fetchRisk360(portfolioId) } }
        val violations = async { runCatchingCancellable { fetchUnresolvedViolations(portfolioId) } }
        buildRiskStatus(portfolioId, killSwitch.await(), summary.await(), violations.await())
    }

    override suspend fun activatePortfolioKillSwitch(
        portfolioId: String,
        reason: String,
    ): Result<WriteOutcome> {
        if (reason.isBlank()) {
            return Result.failure(IllegalArgumentException("Kill switch reason must not be blank"))
        }
        // ACTIVATION SEULE : la levée (DELETE) n'existe volontairement pas dans l'app. Un seul appel,
        // jamais rejoué (client `@Named("write")`) ; sémantique des issues : OrdersStrategiesWriteSupport.
        return OrdersStrategiesWriteSupport.execute(
            endpoint = KILL_SWITCH_ENDPOINT,
            conflictMessage = KILL_SWITCH_CONFLICT_MESSAGE,
        ) { writeApi.activatePortfolioKillSwitch(portfolioId, PortfolioKillSwitchActivateRequestDto(reason)) }
    }

    // ── Lectures ─────────────────────────────────────────────────────────────

    private suspend fun fetchKillSwitchActive(): KillSwitchActiveDto {
        val response = api.getKillSwitchActive()
        if (!response.isSuccessful) throw HttpStatusException(response.code(), KILL_SWITCH_ACTIVE_ENDPOINT)
        return response.body() ?: error("Empty kill-switch response")
    }

    private suspend fun fetchRisk360(portfolioId: String): Risk360SummaryDto {
        val response = api.getRisk360Summary(portfolioId)
        if (!response.isSuccessful) throw HttpStatusException(response.code(), RISK_360_ENDPOINT)
        return response.body() ?: error("Empty risk-360 response")
    }

    private suspend fun fetchUnresolvedViolations(portfolioId: String): List<RiskViolationDto> {
        val response = api.getViolations(portfolioId, isResolved = false, limit = VIOLATIONS_LIMIT)
        if (!response.isSuccessful) throw HttpStatusException(response.code(), VIOLATIONS_ENDPOINT)
        return response.body() ?: error("Empty violations response")
    }

    // ── Agrégation ───────────────────────────────────────────────────────────

    private fun buildRiskStatus(
        portfolioId: String,
        killSwitch: Result<KillSwitchActiveDto>,
        summary: Result<Risk360SummaryDto>,
        violations: Result<List<RiskViolationDto>>,
    ): Result<RiskStatus> {
        val killSwitchDto = killSwitch.getOrNull()
        val summaryDto = summary.getOrNull()
        val violationDtos = violations.getOrNull()

        if (killSwitchDto == null && summaryDto == null && violationDtos == null) {
            return Result.failure(
                killSwitch.exceptionOrNull() ?: IllegalStateException("Risk status unavailable"),
            )
        }

        // Kill switch du portefeuille OU global. Source principale : `kill-switch/active` ; à défaut,
        // les kill switches listés par risk-360 (scopes `global` et `portfolio:<id>` uniquement).
        val portfolioScope = "portfolio:$portfolioId"
        val relevantSwitch: Risk360KillSwitchDto? = summaryDto?.killSwitchesActive?.let { switches ->
            switches.firstOrNull { it.scope.equals(portfolioScope, ignoreCase = true) }
                ?: switches.firstOrNull { it.scope.equals(SCOPE_GLOBAL, ignoreCase = true) }
        }
        val killSwitchActive = if (killSwitchDto != null) {
            killSwitchDto.globalActive ||
                killSwitchDto.portfolioActive.any { (id, active) -> active && id.equals(portfolioId, ignoreCase = true) }
        } else {
            relevantSwitch != null
        }

        return Result.success(
            RiskStatus(
                killSwitchActive = killSwitchActive,
                killSwitchReason = if (killSwitchActive) relevantSwitch?.reason?.takeIf { it.isNotBlank() } else null,
                unresolvedViolations = violationDtos?.count { !it.isResolved } ?: 0,
                dailyLossUsagePct = summaryDto?.let { dailyLossUsage(it) },
                // Le backend l'exprime en POURCENTAGE (-12.4) ; le domaine en FRACTION (-0.124).
                drawdownCurrentPct = summaryDto?.drawdownCurrentPct?.div(PERCENT),
                isPartial = killSwitchDto == null || summaryDto == null || violationDtos == null,
            ),
        )
    }

    /**
     * Perte du jour / limite, en FRACTION. Calculée depuis risk-360 (`max(0, -daily_pnl)` sur
     * `daily_loss_limit`) et NON depuis `daily-loss-status.usage_pct`, compteur « notionnel à
     * risque » quasi inerte quand la porte de risque utilise le P&L réel (contrat §2.5 / §9.7).
     */
    private fun dailyLossUsage(summary: Risk360SummaryDto): Double? {
        val pnl = summary.dailyPnl?.toBigDecimalOrNull() ?: return null
        val limit = summary.dailyLossLimit?.toBigDecimalOrNull() ?: return null
        if (limit.signum() <= 0) return null
        val loss = pnl.negate().max(BigDecimal.ZERO)
        return loss.divide(limit, USAGE_SCALE, RoundingMode.HALF_EVEN).toDouble()
    }

    private companion object {
        const val PERCENT = 100.0
        const val USAGE_SCALE = 6
        const val VIOLATIONS_LIMIT = 100
        const val SCOPE_GLOBAL = "global"
        const val KILL_SWITCH_ACTIVE_ENDPOINT = "v1/risk/kill-switch/active"
        const val RISK_360_ENDPOINT = "v1/risk/portfolios/{portfolio_id}/risk-360-summary"
        const val VIOLATIONS_ENDPOINT = "v1/risk/violations"
        const val KILL_SWITCH_ENDPOINT = "v1/risk/portfolios/{portfolio_id}/kill-switch"
        const val KILL_SWITCH_CONFLICT_MESSAGE = "Conflit lors de l'activation du kill switch — relisez l'état"
    }
}
