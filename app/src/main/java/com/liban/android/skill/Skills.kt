package com.liban.android.skill

import com.liban.android.data.SavingGoalEntity
import com.liban.android.data.UserProfileEntity
import com.liban.android.model.*
import java.time.LocalDate
import kotlin.math.ceil

interface DecisionSkill<I, O> {
    val id: SkillId
    suspend fun execute(input: I): SkillExecution<O>
}

data class BudgetInput(val priceCents: Long, val profile: UserProfileEntity)

class BudgetSkill : DecisionSkill<BudgetInput, BudgetResult> {
    override val id = SkillId.BUDGET

    override suspend fun execute(input: BudgetInput): SkillExecution<BudgetResult> {
        val remaining = (input.profile.monthlyBudgetCents - input.profile.currentSpentCents).coerceAtLeast(1)
        val ratio = input.priceCents.toDouble() / remaining
        val risk = when {
            ratio >= 0.50 -> RiskLevel.HIGH
            ratio >= 0.25 -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
        return SkillExecution(Availability.AVAILABLE, BudgetResult(ratio, risk))
    }
}

data class GoalInput(val priceCents: Long, val goal: SavingGoalEntity?)

class GoalSkill : DecisionSkill<GoalInput, GoalResult> {
    override val id = SkillId.GOAL

    override suspend fun execute(input: GoalInput): SkillExecution<GoalResult> {
        val goal = input.goal ?: return SkillExecution(Availability.UNAVAILABLE, message = "尚未设置储蓄目标")
        val remainingGoal = (goal.targetAmountCents - goal.currentAmountCents).coerceAtLeast(1)
        val daysLeft = (goal.deadlineEpochDay - LocalDate.now().toEpochDay()).coerceAtLeast(1)
        val dailySaving = remainingGoal.toDouble() / daysLeft
        val delayDays = ceil(input.priceCents / dailySaving).toInt().coerceAtLeast(0)
        val pressure = when {
            delayDays >= 14 -> RiskLevel.HIGH
            delayDays >= 7 -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
        return SkillExecution(Availability.AVAILABLE, GoalResult(pressure, delayDays))
    }
}

class HistorySkill : DecisionSkill<Int, HistoryResult> {
    override val id = SkillId.HISTORY

    override suspend fun execute(input: Int): SkillExecution<HistoryResult> {
        val risk = when {
            input >= 3 -> RiskLevel.HIGH
            input >= 1 -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
        return SkillExecution(Availability.AVAILABLE, HistoryResult(input, risk))
    }
}

class ImpulseSkill : DecisionSkill<SceneSignals, ImpulseResult> {
    override val id = SkillId.IMPULSE

    override suspend fun execute(input: SceneSignals): SkillExecution<ImpulseResult> {
        val matched = buildList {
            if (input.limitedTime) add("limited_time")
            if (input.scarcity) add("scarcity")
            if (input.discount) add("discount")
            addAll(input.labels)
        }.distinct()
        val score = ((if (input.limitedTime) 0.4 else 0.0) +
            (if (input.scarcity) 0.4 else 0.0) +
            (if (input.discount) 0.2 else 0.0)).coerceIn(0.0, 1.0)
        val risk = when {
            score >= 0.7 -> RiskLevel.HIGH
            score >= 0.3 -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
        return SkillExecution(Availability.AVAILABLE, ImpulseResult(score, risk, matched))
    }
}

