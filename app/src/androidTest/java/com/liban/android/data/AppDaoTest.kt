package com.liban.android.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDaoTest {
    private val databaseName = "liban-instrumented-test.db"
    private lateinit var database: AppDatabase
    private lateinit var dao: AppDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(databaseName)
        database = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            databaseName,
        ).build()
        dao = database.appDao()
    }

    @After
    fun tearDown() {
        database.close()
        ApplicationProvider.getApplicationContext<android.content.Context>().deleteDatabase(databaseName)
    }

    @Test
    fun purchaseFeedbackIsAtomicAndIdempotent() = runBlocking {
        dao.upsertProfile(UserProfileEntity(monthlyBudgetCents = 100_000, currentSpentCents = 10_000))
        dao.insertDecision(
            DecisionHistoryEntity(
                decisionId = "d1",
                productName = "测试",
                category = "daily",
                priceCents = 20_000,
                sceneJson = "{}",
                resultJson = "{}",
                riskLevel = "LOW",
                recommendation = "BUY",
                sourceMode = "LIVE",
                createdAt = 1,
            )
        )

        assertTrue(dao.recordPurchase("d1", 2))
        assertFalse(dao.recordPurchase("d1", 3))
        assertEquals(30_000, dao.getProfile()?.currentSpentCents)
        assertEquals(1, dao.observeTransactions().first().size)
        assertEquals("PURCHASE", dao.getDecision("d1")?.userAction)
    }

    @Test
    fun purchaseSurvivesDatabaseReopen() = runBlocking {
        dao.upsertProfile(UserProfileEntity(monthlyBudgetCents = 100_000, currentSpentCents = 0))
        dao.insertDecision(
            DecisionHistoryEntity(
                decisionId = "persistent",
                productName = "重启测试",
                category = "daily",
                priceCents = 12_300,
                sceneJson = "{}",
                resultJson = "{}",
                riskLevel = "LOW",
                recommendation = "BUY",
                sourceMode = "LIVE",
                createdAt = 1,
            )
        )
        assertTrue(dao.recordPurchase("persistent", 2))
        database.close()

        database = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
            databaseName,
        ).build()
        dao = database.appDao()

        assertEquals(12_300, dao.getProfile()?.currentSpentCents)
        assertEquals("PURCHASE", dao.getDecision("persistent")?.userAction)
        assertEquals(1, dao.observeTransactions().first().size)
    }
}
