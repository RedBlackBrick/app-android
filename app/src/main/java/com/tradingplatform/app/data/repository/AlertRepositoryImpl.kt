package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.local.db.CacheTtl
import com.tradingplatform.app.data.local.db.dao.AlertDao
import com.tradingplatform.app.data.model.toDomain
import com.tradingplatform.app.domain.model.Alert
import com.tradingplatform.app.domain.model.AlertType
import com.tradingplatform.app.domain.repository.AlertRepository
import com.tradingplatform.app.domain.util.runCatchingCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlertRepositoryImpl @Inject constructor(
    private val alertDao: AlertDao,
) : AlertRepository {

    /**
     * Flow des alertes depuis Room — source unique (FCM → Room).
     * Pas d'appel réseau — historique local uniquement, fonctionne offline.
     */
    override fun getAlerts(): Flow<List<Alert>> =
        alertDao.getAllFlow().map { entities -> entities.map { it.toDomain() } }

    /**
     * Flow des alertes filtrées par types depuis Room.
     * Utilise une requête SQL IN pour filtrer côté base de données.
     */
    override fun getAlertsByTypes(types: Set<AlertType>): Flow<List<Alert>> =
        alertDao.getByTypesFlow(types.map { it.name }).map { entities -> entities.map { it.toDomain() } }

    override suspend fun markRead(alertId: Long): Result<Unit> = runCatchingCancellable {
        alertDao.markRead(alertId)
    }

    /**
     * Purge les alertes expirées — appelé par WidgetUpdateWorker APRÈS une sync réussie.
     * Applique les deux règles : 30 jours max ET 500 entrées max.
     * Les deux DELETE sont atomiques via [AlertDao.purgeExpired] (@Transaction).
     */
    override suspend fun purgeExpired(): Result<Unit> = runCatchingCancellable {
        // Rétention : 30 jours OU 500 entrées max (CacheTtl / CLAUDE.md §2 Politique de rétention)
        val cutoff = System.currentTimeMillis() - CacheTtl.ALERTS_RETENTION_MS
        alertDao.purgeExpired(cutoff)
    }
}
