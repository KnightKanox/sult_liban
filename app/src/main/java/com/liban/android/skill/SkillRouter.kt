package com.liban.android.skill

import com.liban.android.data.UserProfileEntity
import com.liban.android.model.SceneContext
import com.liban.android.model.SkillId

class SkillRouter {
    private val durableCategories = setOf("electronics", "appliance", "furniture", "digital")

    fun route(scene: SceneContext, profile: UserProfileEntity): Set<SkillId> {
        scene.validSkills().takeIf { it.isNotEmpty() }?.let { return it }

        if (scene.product.category.lowercase() in durableCategories) return SkillId.entries.toSet()
        if (scene.signals.discount || scene.signals.limitedTime || scene.signals.scarcity) {
            return setOf(SkillId.BUDGET, SkillId.HISTORY, SkillId.IMPULSE)
        }
        val remaining = (profile.monthlyBudgetCents - profile.currentSpentCents).coerceAtLeast(1)
        if (scene.price.currentCents >= 200_000 || scene.price.currentCents >= remaining * 0.30) {
            return setOf(SkillId.BUDGET, SkillId.GOAL, SkillId.HISTORY, SkillId.PRICE)
        }
        return setOf(SkillId.BUDGET, SkillId.HISTORY)
    }
}

