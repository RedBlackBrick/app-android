package com.tradingplatform.app.di

import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.data.local.db.dao.AlertDao
import com.tradingplatform.app.data.local.db.dao.DeviceDao
import com.tradingplatform.app.data.local.db.dao.PnlDao
import com.tradingplatform.app.data.local.db.dao.PositionDao
import com.tradingplatform.app.data.local.db.dao.QuoteDao
import com.tradingplatform.app.domain.usecase.alerts.GetAlertsUseCase
import com.tradingplatform.app.domain.usecase.device.GetDevicesUseCase
import com.tradingplatform.app.domain.usecase.market.GetQuoteUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPortfolioNavUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPositionsUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPnlUseCase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * EntryPoint pour les widgets Glance.
 *
 * Les GlanceAppWidget ne supportent pas l'injection Hilt standard (@AndroidEntryPoint
 * n'est pas disponible pour Glance). Utiliser EntryPointAccessors.fromApplication()
 * dans chaque widget pour obtenir les dépendances nécessaires.
 *
 * Usage dans chaque GlanceAppWidget :
 * ```kotlin
 * override suspend fun provideGlance(context: Context, id: GlanceId) {
 *     val ep = EntryPointAccessors
 *         .fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
 *     val portfolioId = ep.encryptedDataStore().readInt(DataStoreKeys.PORTFOLIO_ID) ?: 0
 *     val pnl = ep.pnlDao().getLatestByPeriod("day")
 * }
 * ```
 *
 * Les widgets lisent le cache Room directement via les DAOs — ils ne font PAS d'appels
 * réseau. Le WidgetUpdateWorker met à jour Room en arrière-plan.
 *
 * Note : vérifier is_admin (depuis EncryptedDataStore) avant d'appeler deviceDao()
 * ou getDevicesUseCase() — le SystemStatusWidget ne doit pas être affiché pour les
 * comptes non-admin.
 *
 * @see com.tradingplatform.app.widget.WidgetUpdateWorker — injecté via @HiltWorker (pattern différent)
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    // UseCases — utilisés par WidgetUpdateWorker pour les appels réseau dans doWork()
    fun getPositionsUseCase(): GetPositionsUseCase
    fun getPnlUseCase(): GetPnlUseCase
    fun getPortfolioNavUseCase(): GetPortfolioNavUseCase
    fun getAlertsUseCase(): GetAlertsUseCase
    fun getDevicesUseCase(): GetDevicesUseCase  // vérifier is_admin avant d'appeler
    fun getQuoteUseCase(): GetQuoteUseCase

    // DAOs — pour les GlanceAppWidgets (lecture cache Room uniquement, pas d'appel réseau)
    fun positionDao(): PositionDao
    fun pnlDao(): PnlDao
    fun alertDao(): AlertDao
    fun deviceDao(): DeviceDao
    fun quoteDao(): QuoteDao

    // DataStore — pour lire portfolioId, is_admin depuis les widgets
    fun encryptedDataStore(): EncryptedDataStore
}
