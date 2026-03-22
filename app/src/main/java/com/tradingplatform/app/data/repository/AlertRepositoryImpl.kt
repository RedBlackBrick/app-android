package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.local.db.dao.AlertDao
import com.tradingplatform.app.data.model.toDomain
import com.tradingplatform.app.data.model.toEntity
import com.tradingplatform.app.domain.model.Alert
import com.tradingplatform.app.domain.repository.AlertRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlertRepositoryImpl @Inject constructor(
    private val alertDao: AlertDao,
) : AlertRepository {

    // Rétention alertes : 30 jours OU 500 entrées max (CLAUDE.md §2 Politique de rétention)
    private val ALERT_RETENTION_MS = TimeUnit.DAYS.toMillis(30)

    /**
     * Flow des alertes depuis Room — source unique (FCM → Room).
     * Pas d'appel réseau — historique local uniquement, fonctionne offline.
     */
    override fun getAlerts(): Flow<List<Alert>> =
        alertDao.getAllFlow().map { entities -> entities.map { it.toDomain() } }

    override suspend fun markRead(alertId: Long): Result<Unit> = runCatching {
        alertDao.markRead(alertId)
    }

    /**
     * Insère une alerte reçue par FCM dans Room.
     * Appelé depuis TradingFirebaseMessagingService.
     */
    override suspend fun insertAlert(alert: Alert): Result<Unit> = runCatching {
        alertDao.insert(alert.toEntity())
    }

    /**
     * Purge les alertes expirées — appelé par WidgetUpdateWorker APRÈS une sync réussie.
     * Applique les deux règles : 30 jours max ET 500 entrées max.
     * Les deux DELETE sont atomiques via [AlertDao.purgeExpired] (@Transaction).
     */
    override suspend fun purgeExpired(): Result<Unit> = runCatching {
        val cutoff = System.currentTimeMillis() - ALERT_RETENTION_MS
        alertDao.purgeExpired(cutoff)
    }
}
