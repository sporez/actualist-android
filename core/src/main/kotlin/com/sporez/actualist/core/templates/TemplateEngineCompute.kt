package com.sporez.actualist.core.templates

import com.sporez.actualist.core.templates.TemplateEngine.Category
import com.sporez.actualist.core.templates.TemplateEngine.ComputePlan
import com.sporez.actualist.core.templates.TemplateEngine.LimitState
import com.sporez.actualist.core.templates.TemplateEngine.MonthSources
import com.sporez.actualist.core.templates.TemplateEngine.Write

/** The write path: priorities clamp to the funds available. */
fun TemplateEngine.computeWrites(
    categories: Map<String, Category>,
    orderedCategoryIds: List<String>,
    monthValue: Int,
    availableBudget: Long,
    monthSources: MonthSources = MonthSources(),
    currentMonthValue: Int? = null,
): List<Write> = computePlan(
    categories, orderedCategoryIds, monthValue, availableBudget, monthSources, currentMonthValue, skipAvailableClamp = false,
).writes

/**
 * Apply math plus per-entry contribution. `skipAvailableClamp` is the editor dry-run flag only;
 * the write path must not set it.
 */
fun TemplateEngine.computePlan(
    categories: Map<String, Category>,
    orderedCategoryIds: List<String>,
    monthValue: Int,
    availableBudget: Long,
    monthSources: MonthSources = MonthSources(),
    currentMonthValue: Int? = null,
    skipAvailableClamp: Boolean,
): ComputePlan {
    if (categories.isEmpty()) {
        return ComputePlan(emptyList(), availableBudget, emptyMap(), emptyMap(), 0, emptyMap(), 0)
    }
    TemplateCalendar.validatedMonth(monthValue)
    val currentMonth = TemplateCalendar.validatedMonth(currentMonthValue ?: monthValue)
    for (category in categories.values) {
        validatePercentageSources(category.entries, monthSources)
        validateByScheduleAndSpend(category.entries, monthValue, monthSources.activeScheduleNames, monthSources.activeScheduleIds)
    }

    val categoryIds = orderedCategoryIds.filter { categories.containsKey(it) }
    val limitStates = LinkedHashMap<String, LimitState>()
    for (categoryId in categoryIds) {
        val state = limitState(categories.getValue(categoryId), monthValue)
        if (state != null) limitStates[categoryId] = state
    }
    var remainingAvailable = availableBudget
    for (categoryId in categoryIds) {
        val state = limitStates[categoryId] ?: continue
        remainingAvailable = TemplateMath.add(remainingAvailable, state.releasedExcess())
    }

    val budgetedByCategory = HashMap<String, Long>()
    for (id in categories.keys) budgetedByCategory[id] = 0L
    val fullAmountByCategory = HashMap<String, Long>()
    val limitMetByCategory = HashMap<String, Boolean>()
    val contributions = HashMap<String, MutableList<Long>>()
    for ((id, category) in categories) contributions[id] = MutableList(category.entries.size) { 0L }
    val evaluatedDemandByCategory = HashMap<String, Long>()
    for (categoryId in categoryIds) {
        val state = limitStates[categoryId]
        if (state == null || !state.isInitiallyMet) continue
        val initial = state.initialBudgetedAmount()
        budgetedByCategory[categoryId] = initial
        fullAmountByCategory[categoryId] = initial
        evaluatedDemandByCategory[categoryId] = initial
        limitMetByCategory[categoryId] = true
    }
    val clampShortfallByCategory = HashMap<String, Long>()
    val priorities = categories.values
        .flatMap { category ->
            category.entries.mapNotNull { entry -> if (TemplateEngine.participatesInPriority(entry)) entry.priority else null }
        }
        .toSortedSet()

    for (priority in priorities) {
        val availableFunds = remainingAvailable
        for (categoryId in categoryIds) {
            val category = categories[categoryId] ?: continue
            val indexedEntries = category.entries.withIndex().filter {
                TemplateEngine.participatesInPriority(it.value) && it.value.priority == priority
            }
            if (indexedEntries.isEmpty() || limitMetByCategory[categoryId] == true) continue

            var amount = 0L
            val rawByIndex = HashMap<Int, Long>()
            var ranBy = false
            var ranSchedule = false
            val byIndexes = mutableListOf<Int>()
            val scheduleIndexes = mutableListOf<Int>()
            for (indexed in indexedEntries) {
                val index = indexed.index
                val entry = indexed.value
                when (entry.type) {
                    "by" -> {
                        byIndexes.add(index)
                        if (ranBy) {
                            rawByIndex[index] = 0L
                            continue
                        }
                        ranBy = true
                        val batch = computeByAmount(
                            indexedEntries.map { it.value }.filter { it.type == "by" },
                            monthValue,
                            category.fromLastMonth,
                        )
                        amount = TemplateMath.add(amount, batch)
                        rawByIndex[index] = batch
                    }
                    "schedule" -> {
                        scheduleIndexes.add(index)
                        if (ranSchedule) {
                            rawByIndex[index] = 0L
                            continue
                        }
                        ranSchedule = true
                        val batch = computeScheduleAmount(
                            indexedEntries.map { it.value }.filter { it.type == "schedule" },
                            monthValue,
                            category,
                            amount,
                        )
                        amount = TemplateMath.add(amount, batch)
                        rawByIndex[index] = batch
                    }
                    else -> {
                        val part = computeEntryAmount(
                            entry,
                            monthValue,
                            currentMonth,
                            category,
                            limitStates[categoryId],
                            availableFunds,
                            monthSources,
                        )
                        amount = TemplateMath.add(amount, part)
                        rawByIndex[index] = part
                    }
                }
            }

            if (byIndexes.size > 1) {
                redistributeRaw(
                    byIndexes,
                    rawByIndex,
                    byIndexes.map { index ->
                        try {
                            resolvedByTarget(category.entries[index], monthValue).amount
                        } catch (error: UnsupportedTemplateException) {
                            0L
                        }
                    },
                )
            }
            if (scheduleIndexes.size > 1) {
                redistributeRaw(
                    scheduleIndexes,
                    rawByIndex,
                    scheduleIndexes.map { index ->
                        val key = category.entries[index].scheduleLookupKey
                        val resolved = if (key != null) category.resolvedSchedules[key] else null
                        if (resolved == null) 0L else maxOf(0L, resolved.monthlyRepeatingTarget)
                    },
                )
            }

            val limitState = limitStates[categoryId]
            if (limitState != null) {
                val alreadyBudgeted = budgetedByCategory[categoryId] ?: 0L
                val availableBeforeLimit = maxOf(
                    0L,
                    TemplateMath.subtract(TemplateMath.subtract(limitState.limitAmount, limitState.fromLastMonth), alreadyBudgeted),
                )
                if (amount > availableBeforeLimit) {
                    amount = availableBeforeLimit
                    limitMetByCategory[categoryId] = true
                }
            }

            if (currency.hideFraction) amount = removeFractionLikeActual(amount)

            fullAmountByCategory[categoryId] = TemplateMath.add(fullAmountByCategory[categoryId] ?: 0L, amount)

            // Capture the signed target before the available-funds clamp.
            evaluatedDemandByCategory[categoryId] = TemplateMath.add(evaluatedDemandByCategory[categoryId] ?: 0L, amount)

            // Actual's "do not overbudget when using a priority" clamp gates on the resulting
            // availability, not on whether the amount is positive. The editor dry run skips it.
            if (priority > 0 && !category.isIncome && remainingAvailable < amount && !skipAvailableClamp) {
                val requested = amount
                val clamped = maxOf(0L, remainingAvailable)
                if (requested > clamped) {
                    clampShortfallByCategory[categoryId] = TemplateMath.add(
                        clampShortfallByCategory[categoryId] ?: 0L,
                        TemplateMath.subtract(requested, clamped),
                    )
                }
                amount = clamped
            }

            val shares = scaledShares(rawByIndex, indexedEntries.map { it.index }, amount)
            val categoryContributions = contributions.getValue(categoryId)
            for ((index, share) in shares) {
                categoryContributions[index] = TemplateMath.add(categoryContributions[index], share)
            }

            budgetedByCategory[categoryId] = TemplateMath.add(budgetedByCategory[categoryId] ?: 0L, amount)
            remainingAvailable = if (category.isIncome) {
                TemplateMath.add(remainingAvailable, amount)
            } else {
                TemplateMath.subtract(remainingAvailable, amount)
            }
        }
    }

    remainingAvailable = distributeRemainder(
        categories,
        categoryIds,
        limitStates,
        budgetedByCategory,
        limitMetByCategory,
        remainingAvailable,
        contributions,
        evaluatedDemandByCategory,
    )

    val writes = finalizeWrites(categories, categoryIds, budgetedByCategory, fullAmountByCategory)
    var evaluatedDemand = 0L
    for (value in evaluatedDemandByCategory.values) evaluatedDemand = TemplateMath.add(evaluatedDemand, value)
    var clampShortfall = 0L
    for (value in clampShortfallByCategory.values) clampShortfall = TemplateMath.add(clampShortfall, value)
    return ComputePlan(
        writes = writes,
        leftover = remainingAvailable,
        contributions = contributions,
        evaluatedDemandByCategory = evaluatedDemandByCategory,
        evaluatedDemand = evaluatedDemand,
        clampShortfallByCategory = clampShortfallByCategory,
        clampShortfall = clampShortfall,
    )
}

private fun redistributeRaw(indexes: List<Int>, rawByIndex: MutableMap<Int, Long>, weights: List<Long>) {
    var batch = 0L
    for (index in indexes) batch += rawByIndex[index] ?: 0L
    val shares = distributeTotal(batch, weights)
    for ((offset, index) in indexes.withIndex()) rawByIndex[index] = shares[offset]
}

internal fun scaledShares(rawByIndex: Map<Int, Long>, orderedIndexes: List<Int>, total: Long): Map<Int, Long> {
    val scaledTotal = maxOf(0L, total)
    var rawTotal = 0L
    for (value in rawByIndex.values) rawTotal += value
    if (scaledTotal <= 0L || rawTotal == 0L) {
        return orderedIndexes.associateWith { 0L }
    }
    val scale = scaledTotal.toDouble() / rawTotal.toDouble()
    var remaining = scaledTotal
    val result = HashMap<Int, Long>()
    for ((offset, index) in orderedIndexes.withIndex()) {
        val isLast = offset == orderedIndexes.size - 1
        val raw = rawByIndex[index] ?: 0L
        val share = if (isLast) {
            remaining
        } else {
            maxOf(0L, minOf(remaining, TemplateMath.actualRound(raw.toDouble() * scale)))
        }
        result[index] = share
        remaining -= share
    }
    return result
}

internal fun distributeTotal(total: Long, weights: List<Long>): List<Long> {
    if (total <= 0L || weights.isEmpty()) return List(weights.size) { 0L }
    var weightSum = 0L
    for (weight in weights) weightSum += weight
    var remaining = total
    val result = MutableList(weights.size) { 0L }
    for (index in weights.indices) {
        if (index == weights.size - 1) {
            result[index] = remaining
            break
        }
        val share = if (weightSum > 0L) {
            maxOf(
                0L,
                minOf(remaining, TemplateMath.actualRound(total.toDouble() * weights[index].toDouble() / weightSum.toDouble())),
            )
        } else {
            0L
        }
        result[index] = share
        remaining -= share
    }
    return result
}
