package com.liban.android.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        UserProfileEntity::class,
        SavingGoalEntity::class,
        TransactionEntity::class,
        DecisionHistoryEntity::class,
        ApiDiagnosticEntity::class,
        PriceCacheEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao

    companion object {
        fun create(context: Context): AppDatabase = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "liban.db",
        ).addMigrations(MIGRATION_1_2).build()

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE decision_history ADD COLUMN priceSource TEXT NOT NULL DEFAULT 'UNAVAILABLE'"
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS price_cache (
                        cacheKey TEXT NOT NULL PRIMARY KEY,
                        productName TEXT NOT NULL,
                        pagePriceCents INTEGER NOT NULL,
                        comparisonJson TEXT NOT NULL,
                        priceSource TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        expiresAt INTEGER NOT NULL
                    )""".trimIndent()
                )
            }
        }
    }
}
