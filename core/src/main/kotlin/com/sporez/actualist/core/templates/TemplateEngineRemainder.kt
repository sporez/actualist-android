package com.sporez.actualist.core.templates

import com.sporez.actualist.core.templates.TemplateEngine.Category
import com.sporez.actualist.core.templates.TemplateEngine.LimitState
import com.sporez.actualist.core.templates.TemplateEngine.Write

private fun remainderWeight(category: Category): Double {
    var total = 0.0
    for (entry in category.entries) {
        val weight = entry.weight
        if (entry.type == "remainder" && weight != null) total += weight
    }
    return total
}

private fun TemplateEngine.remainderSmallestUnit(): Long {
    if (!currency.hideFraction || currency.decimalPlaces <= 0) return 1L
    var scale = 1L
    repeat(currency.decimalPlaces) { scale *= 10 }
    return scale
}

/**
 * Spends what is left on `remainder` templates by weight, in passes until nothing more can be
 * placed. Returns the funds still unspent; the map arguments are updated in place.
 */
internal fun TemplateEngine.distributeRemainder(
    categories: Map<String, Category>,
    orderedCategoryIds: List<String>,
    limitStates: Map<String, LimitState>,
    budgetedByCategory: MutableMap<String, Long>,
    limitMetByCategory: MutableMap<String, Boolean>,
    remainingAvailableIn: Long,
    contributions: MutableMap<String, MutableList<Long>>,
    evaluatedDemandByCategory: MutableMap<String, Long>,
): Long {
    var remainingAvailable = remainingAvailableIn

    fun remainderCategoryIds(): List<String> = orderedCategoryIds.filter { categoryId ->
        val category = categories[categoryId]
        category != null && remainderWeight(category) > 0 && limitMetByCategory[categoryId] != true
    }

    var active = remainderCategoryIds()
    while (remainingAvailable > 0 && active.isNotEmpty()) {
        var totalWeight = 0.0
        for (categoryId in active) {
            val category = categories[categoryId] ?: continue
            totalWeight += remainderWeight(category)
        }
        if (totalWeight <= 0) break
        val perWeight = remainingAvailable.toDouble() / totalWeight
        val beforePass = remainingAvailable
        for (categoryId in active) {
            val category = categories[categoryId] ?: continue
            val limitState = limitStates[categoryId]
            val allocated = runRemainder(
                category,
                limitState,
                budgetedByCategory[categoryId] ?: 0L,
                remainingAvailable,
                perWeight,
            )
            if (limitState != null) {
                val nextBudgeted = TemplateMath.add(budgetedByCategory[categoryId] ?: 0L, allocated)
                val carried = TemplateMath.add(nextBudgeted, limitState.fromLastMonth)
                if (carried >= limitState.limitAmount) limitMetByCategory[categoryId] = true
            }
            budgetedByCategory[categoryId] = TemplateMath.add(budgetedByCategory[categoryId] ?: 0L, allocated)
            evaluatedDemandByCategory[categoryId] = TemplateMath.add(evaluatedDemandByCategory[categoryId] ?: 0L, allocated)
            remainingAvailable = TemplateMath.subtract(remainingAvailable, allocated)
            attributeRemainder(allocated, category, categoryId, contributions)
        }
        if (remainingAvailable == beforePass) break
        active = remainderCategoryIds()
    }
    return remainingAvailable
}

private fun TemplateEngine.runRemainder(
    category: Category,
    limitState: LimitState?,
    alreadyBudgeted: Long,
    budgetAvailable: Long,
    perWeight: Double,
): Long {
    val weight = remainderWeight(category)
    if (weight <= 0) return 0L

    var toBudget = TemplateMath.actualRound(weight * perWeight)
    val smallest: Long
    if (currency.hideFraction) {
        toBudget = removeFractionLikeActual(toBudget)
        smallest = remainderSmallestUnit()
    } else {
        smallest = 1L
    }

    if (toBudget > budgetAvailable) {
        toBudget = budgetAvailable
    } else if (TemplateMath.subtract(budgetAvailable, toBudget) <= smallest) {
        toBudget = budgetAvailable
    }

    if (limitState != null) {
        val room = TemplateMath.subtract(
            limitState.limitAmount,
            TemplateMath.add(alreadyBudgeted, limitState.fromLastMonth),
        )
        if (toBudget >= room) toBudget = room
    }
    return toBudget
}

private fun attributeRemainder(
    allocated: Long,
    category: Category,
    categoryId: String,
    contributions: MutableMap<String, MutableList<Long>>,
) {
    if (allocated <= 0L) return
    val indexes = category.entries.indices.filter { category.entries[it].type == "remainder" }
    if (indexes.isEmpty()) return
    val list = contributions.getOrPut(categoryId) { MutableList(category.entries.size) { 0L } }
    val weights = indexes.map { category.entries[it].weight ?: 0.0 }
    val weightSum = weights.sum()
    var remaining = allocated
    for ((offset, index) in indexes.withIndex()) {
        val isLast = offset == indexes.size - 1
        val share = if (isLast) {
            remaining
        } else if (weightSum > 0) {
            maxOf(0L, minOf(remaining, TemplateMath.actualRound(allocated.toDouble() * (weights[offset] / weightSum))))
        } else {
            0L
        }
        list[index] = list[index] + share
        remaining -= share
    }
}

/** Goal directives write `goal` / `long_goal`; template entries write the full demand as `goal`. */
internal fun TemplateEngine.finalizeWrites(
    categories: Map<String, Category>,
    orderedCategoryIds: List<String>,
    budgetedByCategory: Map<String, Long>,
    fullAmountByCategory: Map<String, Long>,
): List<Write> = orderedCategoryIds.mapNotNull { categoryId ->
    val category = categories[categoryId] ?: return@mapNotNull null
    var amount = budgetedByCategory[categoryId] ?: 0L
    val goalEntry = category.entries.firstOrNull { it.isGoal }
    if (goalEntry != null) {
        if (category.isGoalOnly) amount = category.previouslyBudgeted
        val goalAmount = goalEntry.amount ?: throw UnsupportedTemplateException("goal is missing amount")
        Write(categoryId, amount, amountToMinorUnits(goalAmount), 1L)
    } else {
        Write(categoryId, amount, fullAmountByCategory[categoryId], null)
    }
}
