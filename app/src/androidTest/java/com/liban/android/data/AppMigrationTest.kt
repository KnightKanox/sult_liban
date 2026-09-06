package com.liban.android.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppMigrationTest {
    @Test
    fun migrationOneToTwoPreservesDecisionAndAddsPriceSource() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "migration-1-2.db"
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
            db.execSQL("CREATE TABLE user_profile (id INTEGER NOT NULL PRIMARY KEY, monthlyBudgetCents INTEGER NOT NULL, currentSpentCents INTEGER NOT NULL, categoryBudgetJson TEXT NOT NULL)")
            db.execSQL("CREATE TABLE saving_goal (id INTEGER NOT NULL PRIMARY KEY, name TEXT NOT NULL, targetAmountCents INTEGER NOT NULL, currentAmountCents INTEGER NOT NULL, deadlineEpochDay INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE transactions (id TEXT NOT NULL PRIMARY KEY, decisionId TEXT, name TEXT NOT NULL, category TEXT NOT NULL, amountCents INTEGER NOT NULL, currency TEXT NOT NULL, source TEXT NOT NULL, timestamp INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE decision_history (decisionId TEXT NOT NULL PRIMARY KEY, productName TEXT NOT NULL, category TEXT NOT NULL, priceCents INTEGER NOT NULL, sceneJson TEXT NOT NULL, resultJson TEXT NOT NULL, riskLevel TEXT NOT NULL, recommendation TEXT NOT NULL, sourceMode TEXT NOT NULL, userAction TEXT, delayHours INTEGER, createdAt INTEGER NOT NULL, feedbackAt INTEGER)")
            db.execSQL("CREATE TABLE api_diagnostics (provider TEXT NOT NULL PRIMARY KEY, configured INTEGER NOT NULL, lastSuccessAt INTEGER, lastLatencyMs INTEGER, lastHttpStatus INTEGER, lastError TEXT)")
            db.execSQL("INSERT INTO decision_history VALUES ('old','旧记录','other',10000,'{}','{}','LOW','BUY','LIVE',NULL,NULL,1,NULL)")
            db.version = 1
        }
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()
        assertEquals("UNAVAILABLE", database.appDao().getDecision("old")?.priceSource)
        database.close()
        context.deleteDatabase(name)
    }
}
