package com.liban.android.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    @Query("SELECT * FROM user_profile WHERE id = 1")
    fun observeProfile(): Flow<UserProfileEntity?>

    @Query("SELECT * FROM user_profile WHERE id = 1")
    suspend fun getProfile(): UserProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: UserProfileEntity)

    @Query("UPDATE user_profile SET currentSpentCents = currentSpentCents + :amountCents WHERE id = 1")
    suspend fun addSpent(amountCents: Long)

    @Query("SELECT * FROM saving_goal WHERE id = 1")
    fun observeGoal(): Flow<SavingGoalEntity?>

    @Query("SELECT * FROM saving_goal WHERE id = 1")
    suspend fun getGoal(): SavingGoalEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGoal(goal: SavingGoalEntity)

    @Query("SELECT * FROM transactions WHERE category = :category AND timestamp >= :since")
    suspend fun transactionsSince(category: String, since: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC LIMIT :limit")
    fun observeTransactions(limit: Int = 50): Flow<List<TransactionEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTransaction(transaction: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDecision(decision: DecisionHistoryEntity): Long

    @Query("SELECT * FROM decision_history ORDER BY createdAt DESC LIMIT :limit")
    fun observeDecisions(limit: Int = 50): Flow<List<DecisionHistoryEntity>>

    @Query("SELECT * FROM decision_history WHERE decisionId = :decisionId")
    suspend fun getDecision(decisionId: String): DecisionHistoryEntity?

    @Query(
        """UPDATE decision_history SET userAction = :action, delayHours = :delayHours,
            feedbackAt = :feedbackAt WHERE decisionId = :decisionId AND userAction IS NULL"""
    )
    suspend fun markFeedbackIfAbsent(
        decisionId: String,
        action: String,
        delayHours: Int?,
        feedbackAt: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDiagnostic(diagnostic: ApiDiagnosticEntity)

    @Query("SELECT * FROM api_diagnostics WHERE provider = :provider")
    suspend fun getDiagnostic(provider: String): ApiDiagnosticEntity?

    @Query("SELECT * FROM api_diagnostics ORDER BY provider")
    fun observeDiagnostics(): Flow<List<ApiDiagnosticEntity>>

    @Transaction
    suspend fun recordPurchase(decisionId: String, now: Long): Boolean {
        val decision = getDecision(decisionId) ?: return false
        if (markFeedbackIfAbsent(decisionId, "PURCHASE", null, now) == 0) return false
        insertTransaction(
            TransactionEntity(
                id = "purchase-$decisionId",
                decisionId = decisionId,
                name = decision.productName,
                category = decision.category,
                amountCents = decision.priceCents,
                currency = "CNY",
                source = "AGENT_FEEDBACK",
                timestamp = now,
            )
        )
        addSpent(decision.priceCents)
        return true
    }

    @Transaction
    suspend fun recordNonPurchase(
        decisionId: String,
        action: String,
        delayHours: Int?,
        now: Long,
    ): Boolean = markFeedbackIfAbsent(decisionId, action, delayHours, now) == 1
}
