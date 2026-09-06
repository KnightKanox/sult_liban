package com.liban.android.skill

import com.liban.android.data.SavingGoalEntity
import com.liban.android.data.UserProfileEntity
import com.liban.android.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class SkillsTest {
    @Test
    fun budgetBoundariesAreStable() = runTest {
        val profile = UserProfileEntity(monthlyBudgetCents = 100_000, currentSpentCents = 0)
        assertEquals(RiskLevel.LOW, BudgetSkill().execute(BudgetInput(24_999, profile)).value?.risk)
        assertEquals(RiskLevel.MEDIUM, BudgetSkill().execute(BudgetInput(25_000, profile)).value?.risk)
        assertEquals(RiskLevel.HIGH, BudgetSkill().execute(BudgetInput(50_000, profile)).value?.risk)
    }

    @Test
    fun historyBoundariesAreStable() = runTest {
        assertEquals(RiskLevel.LOW, HistorySkill().execute(0).value?.risk)
        assertEquals(RiskLevel.MEDIUM, HistorySkill().execute(1).value?.risk)
        assertEquals(RiskLevel.MEDIUM, HistorySkill().execute(2).value?.risk)
        assertEquals(RiskLevel.HIGH, HistorySkill().execute(3).value?.risk)
    }

    @Test
    fun allImpulseSignalsProduceHighRisk() = runTest {
        val result = ImpulseSkill().execute(SceneSignals(discount = true, limitedTime = true, scarcity = true)).value
        assertEquals(1.0, result?.score ?: 0.0, 0.0001)
        assertEquals(RiskLevel.HIGH, result?.risk)
    }

    @Test
    fun missingGoalIsUnavailable() = runTest {
        assertEquals(Availability.UNAVAILABLE, GoalSkill().execute(GoalInput(50_000, null)).availability)
    }

    @Test
    fun goalDelayUsesDailySavingRate() = runTest {
        val goal = SavingGoalEntity(
            name = "测试",
            targetAmountCents = 100_000,
            currentAmountCents = 0,
            deadlineEpochDay = LocalDate.now().plusDays(100).toEpochDay(),
        )
        assertEquals(20, GoalSkill().execute(GoalInput(20_000, goal)).value?.delayDays)
    }
}

