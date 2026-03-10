package com.tradingplatform.app.di

import android.content.Context
import androidx.room.Room
import com.tradingplatform.app.data.local.db.AppDatabase
import com.tradingplatform.app.data.local.db.dao.AlertDao
import com.tradingplatform.app.data.local.db.dao.DeviceDao
import com.tradingplatform.app.data.local.db.dao.PnlDao
import com.tradingplatform.app.data.local.db.dao.PositionDao
import com.tradingplatform.app.data.local.db.dao.QuoteDao
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
        .apply {
            if (BuildConfig.DEBUG) {
                fallbackToDestructiveMigration(dropAllTables = true)
            }
            // En release, pas de fallback → crash explicite si migration manquante
            // Ajouter addMigrations(MIGRATION_X_Y) avant chaque bump de version schema
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
}
