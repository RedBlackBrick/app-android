package com.tradingplatform.app.di

import android.content.Context
import androidx.room.Room
import com.tradingplatform.app.data.local.db.AppDatabase
import com.tradingplatform.app.data.local.db.dao.AlertDao
import com.tradingplatform.app.data.local.db.dao.DeviceDao
import com.tradingplatform.app.data.local.db.dao.PnlDao
import com.tradingplatform.app.data.local.db.dao.PositionDao
import com.tradingplatform.app.data.local.db.dao.QuoteDao
import com.tradingplatform.app.data.local.db.dao.WatchlistDao
import com.tradingplatform.app.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context,
    ): AppDatabase = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        "trading_platform_db"
    )
        // v7 est la baseline du premier release (CLAUDE.md §2) : aucune migration
        // n'existe avant elle, donc rien à déclarer ici via .addMigrations(...).
        .apply {
            if (BuildConfig.DEBUG) {
                fallbackToDestructiveMigration(dropAllTables = true)
            }
            // En release, PAS de fallback par design : la baseline v7 ne cible que
            // les installations fraîches (versionCode 1, personne en v<7). Un futur
            // changement de schéma (v8+) devra ajouter une migration explicite
            // (MIGRATION_7_8) avant d'être livré — sinon crash explicite au boot,
            // ce qui est préférable à une perte silencieuse des alertes locales.
        }
        .build()

    @Provides
    fun providePositionDao(db: AppDatabase): PositionDao = db.positionDao()

    @Provides
    fun providePnlDao(db: AppDatabase): PnlDao = db.pnlDao()

    @Provides
    fun provideAlertDao(db: AppDatabase): AlertDao = db.alertDao()

    @Provides
    fun provideDeviceDao(db: AppDatabase): DeviceDao = db.deviceDao()

    @Provides
    fun provideQuoteDao(db: AppDatabase): QuoteDao = db.quoteDao()

    @Provides
    fun provideWatchlistDao(db: AppDatabase): WatchlistDao = db.watchlistDao()
}
