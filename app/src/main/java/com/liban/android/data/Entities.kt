package com.liban.android.data

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey

@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val id: Int = 1,
    val monthlyBudgetCents: Long,
    val currentSpentCents: Long,
    val categoryBudgetJson: String = "{}",
)

@Entity(tableName = "saving_goal")
data class SavingGoalEntity(
    @PrimaryKey val id: Int = 1,
    val name: String,
    val targetAmountCents: Long,
    val currentAmountCents: Long,
    val deadlineEpochDay: Long,
)

@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey val id: String,
    val decisionId: String?,
    val name: String,
    val category: String,
    val amountCents: Long,
    val currency: String,
    val source: String,
    val timestamp: Long,
)

@Entity(tableName = "decision_history")
data class DecisionHistoryEntity(
    @PrimaryKey val decisionId: String,
    val productName: String,
    val category: String,
    val priceCents: Long,
    val sceneJson: String,
    val resultJson: String,
    val riskLevel: String,
    val recommendation: String,
    val sourceMode: String,
    @ColumnInfo(defaultValue = "'UNAVAILABLE'")
    val priceSource: String = "UNAVAILABLE",
    val userAction: String? = null,
    val delayHours: Int? = null,
    val createdAt: Long,
    val feedbackAt: Long? = null,
)

@Entity(tableName = "price_cache")
data class PriceCacheEntity(
    @PrimaryKey val cacheKey: String,
    val productName: String,
    val pagePriceCents: Long,
    val comparisonJson: String,
    val priceSource: String,
    val createdAt: Long,
    val expiresAt: Long,
)

@Entity(tableName = "api_diagnostics")
data class ApiDiagnosticEntity(
    @PrimaryKey val provider: String,
    val configured: Boolean,
    val lastSuccessAt: Long? = null,
    val lastLatencyMs: Long? = null,
    val lastHttpStatus: Int? = null,
    val lastError: String? = null,
)
