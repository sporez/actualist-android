package com.sporez.actualist.core.templates

import com.sporez.actualist.core.templates.TemplateEngine.Bounds

private fun unsupported(reason: String): Nothing = throw UnsupportedTemplateException(reason)

internal fun TemplateEngine.validateDirective(entry: TemplateEntry) {
    val directive = entry.directive?.trim()
    if (directive.isNullOrEmpty()) unsupported("template is missing a directive")
    when {
        directive == "template" && entry.type == "error" -> unsupported("error type requires directive error")
        directive == "template" -> Unit
        directive == "goal" && entry.type == "goal" -> Unit
        directive == "error" && entry.type == "error" -> Unit
        directive == "goal" -> unsupported("goal directive requires type goal")
        directive == "error" -> unsupported("error directive requires type error")
        else -> unsupported("unsupported template directive")
    }
}

internal fun TemplateEngine.validate(entry: TemplateEntry) {
    validatePriority(entry)
    validateSignedAmount(entry.monthly, "monthly amount")
    validateSignedAmount(entry.amount, "amount")
    validateSignedAmount(entry.adjustment, "adjustment")
    validatePercentage(entry.percentage)
    validatePercentage(entry.percent)
    validateNumMonths(entry.numMonths)
    validateInterval(entry.period?.amount, "period interval")
    validateLookBack(entry.lookBack)
    validateRepeatInterval(entry.repeatInterval)
    validateWeight(entry.weight)

    when (entry.type) {
        "simple" -> {
            if (entry.monthly == null && entry.limit == null) unsupported("simple without monthly amount")
            validateLimit(entry.limit)
        }
        "periodic" -> {
            val periodAmount = entry.period?.amount
            val period = entry.period?.period
            if (entry.amount == null || periodAmount == null || periodAmount !in Bounds.periodInterval ||
                period == null || period !in listOf("day", "week", "month", "year")
            ) {
                unsupported("periodic")
            }
            val starting = entry.starting
            if (!starting.isNullOrEmpty() && TemplateCalendar.validatedDate(starting) == null) unsupported("periodic start date")
            validateLimit(entry.limit)
        }
        "copy" -> {
            // Actual's parser accepts `copy from N months ago` with an optional attached limit and
            // persists it, but runCopy() and checkLimit() ignore it. Accept the copy; do not validate
            // or apply the ignored property.
            val lookBack = entry.lookBack
            if (lookBack == null || lookBack !in Bounds.lookBack) unsupported("copy")
        }
        "by" -> {
            val repeat = entry.repeatInterval
            if (entry.amount == null || !TemplateCalendar.validMonth(entry.month) || entry.limit != null ||
                (repeat != null && repeat !in Bounds.repeatInterval)
            ) {
                unsupported("invalid by template")
            }
        }
        "limit" -> {
            if (entry.standaloneLimit == null) unsupported("up-to limit is missing its amount or period")
            validateLimit(entry.standaloneLimit)
        }
        "refill" -> {
            if (entry.limit != null) unsupported("invalid refill template")
        }
        "remainder" -> {
            if (entry.weight == null) unsupported("remainder is missing weight")
            validateLimit(entry.limit)
        }
        "average" -> {
            val numMonths = entry.numMonths
            if (numMonths == null || numMonths !in Bounds.numMonths) unsupported("average")
        }
        "percentage" -> {
            val percent = entry.percentageAmount
            val source = entry.sourceCategory?.trim()
            if (percent == null || !Bounds.percentageOk(percent) || source.isNullOrEmpty()) unsupported("percentage")
        }
        "spend" -> {
            val repeat = entry.repeatInterval
            if (entry.amount == null || !TemplateCalendar.validMonth(entry.month) || !TemplateCalendar.validMonth(entry.fromMonth) ||
                entry.limit != null || (repeat != null && repeat !in Bounds.repeatInterval)
            ) {
                unsupported("invalid spend template")
            }
        }
        "schedule" -> {
            if (entry.limit != null) unsupported("schedule")
            if (entry.scheduleLookupKey == null) unsupported(entry.missingScheduleReason)
        }
        "goal" -> {
            if (entry.amount == null) unsupported("goal is missing amount")
        }
        else -> unsupported(entry.type)
    }
}

fun TemplateEngine.validateByScheduleAndSpend(
    entries: List<TemplateEntry>,
    monthValue: Int,
    activeScheduleNames: Set<String>,
    activeScheduleIds: Set<String> = emptySet(),
) {
    val scheduleAndBy = entries.filter { it.type == "schedule" || it.type == "by" }
    if (scheduleAndBy.isEmpty()) return

    for (entry in entries) {
        if (entry.type != "schedule") continue
        val scheduleId = entry.presentScheduleId
        val name = entry.trimmedScheduleName
        if (scheduleId != null) {
            if (scheduleId !in activeScheduleIds) unsupported(entry.missingScheduleReason)
        } else if (name != null) {
            if (name !in activeScheduleNames) unsupported(entry.missingScheduleReason)
        } else {
            unsupported(entry.missingScheduleReason)
        }
    }

    val lowest = scheduleAndBy.mapNotNull { it.priority }.minOrNull()
    if (lowest != null) {
        for (entry in scheduleAndBy) {
            if (entry.priority != lowest) {
                unsupported(
                    "Schedule and By templates must be the same priority level. " +
                        "Fix by setting all Schedule and By templates to priority level $lowest",
                )
            }
        }
    }

    for (entry in entries) {
        if (entry.type != "by" && entry.type != "spend") continue
        val month = entry.month ?: continue
        val target = try {
            TemplateCalendar.parseMonth(month)
        } catch (error: UnsupportedTemplateException) {
            continue
        }
        val distance = TemplateCalendar.monthDistance(monthValue, target)
        if (distance < 0 && entry.repeatInterval == null && entry.annual != true) {
            unsupported("Target month has passed, remove or update the target month")
        }
    }
}

fun TemplateEngine.validatePercentageSources(entries: List<TemplateEntry>, monthSources: TemplateEngine.MonthSources) {
    for (entry in entries) {
        if (entry.type == "percentage") resolvePercentageSource(entry, monthSources)
    }
}

internal fun TemplateEngine.validateOneGoal(entries: List<TemplateEntry>) {
    if (entries.count { it.directive == "goal" && it.type == "goal" } > 1) unsupported("Only one #goal is allowed per category")
}

internal fun TemplateEngine.validateOneSpend(entries: List<TemplateEntry>) {
    if (entries.count { it.type == "spend" } > 1) unsupported("Only one spend template is allowed per category")
}

internal fun TemplateEngine.validateInteractions(entries: List<TemplateEntry>) {
    val byPriorities = entries.filter { it.type == "by" }.mapNotNull { it.priority }.toSet()
    if (byPriorities.size > 1) unsupported("all by templates in a category must use the same priority")

    val limits = entries.filter { TemplateEngine.hasEffectiveLimit(it) }
    if (limits.size > 1) unsupported("only one up-to limit is supported per category")

    if (entries.any { it.type == "refill" } && limits.size != 1) unsupported("refill requires exactly one up-to limit")
}

internal fun TemplateEngine.validateLimit(limit: TemplateLimit?) {
    if (limit == null) return
    val amount = limit.amount
    if (amount == null || !amount.isFinite() || !Bounds.nonnegativeAmountOk(amount)) {
        unsupported("up-to limit is missing its amount or period")
    }
    when (limit.period) {
        "monthly" -> {
            val start = limit.start
            if (start != null && TemplateCalendar.validatedDate(start) == null) {
                unsupported("monthly up-to limit has an invalid start date")
            }
        }
        "daily" -> Unit
        "weekly" -> {
            val start = limit.start
            if (start == null || TemplateCalendar.validatedDate(start) == null) {
                unsupported("weekly limit requires a start date (YYYY-MM-DD)")
            }
        }
        else -> unsupported("only daily, weekly, and monthly up-to limits are supported")
    }
}

private fun validatePriority(entry: TemplateEntry) {
    when (entry.type) {
        "remainder", "limit" -> if (entry.priority != null) unsupported("${entry.type} templates must not have a priority")
        "simple", "periodic", "copy", "by", "refill", "average", "percentage", "spend", "schedule" -> {
            val priority = entry.priority
            if (priority == null || priority !in Bounds.priority) unsupported("${entry.type} templates require a numeric priority")
        }
        "goal" -> {
            val priority = entry.priority
            if (priority != null && priority !in Bounds.priority) unsupported("priority is outside the supported range")
        }
        else -> unsupported(entry.type)
    }
}

private fun validateSignedAmount(amount: Double?, field: String) {
    if (amount == null) return
    if (!amount.isFinite() || !Bounds.signedAmountOk(amount)) unsupported("$field is outside the supported range")
}

private fun validatePercentage(percentage: Double?) {
    if (percentage == null) return
    if (!percentage.isFinite() || !Bounds.percentageOk(percentage)) unsupported("percentage is outside the supported range")
}

private fun validateNumMonths(numMonths: Int?) {
    if (numMonths != null && numMonths !in Bounds.numMonths) unsupported("average window is outside the supported range")
}

private fun validateInterval(interval: Int?, field: String) {
    if (interval != null && interval !in Bounds.periodInterval) unsupported("$field is outside the supported range")
}

private fun validateLookBack(lookBack: Int?) {
    if (lookBack != null && lookBack !in Bounds.lookBack) unsupported("look-back window is outside the supported range")
}

private fun validateRepeatInterval(interval: Int?) {
    if (interval != null && interval !in Bounds.repeatInterval) unsupported("repeat interval is outside the supported range")
}

private fun validateWeight(weight: Double?) {
    if (weight == null) return
    if (!weight.isFinite() || !Bounds.weightOk(weight)) unsupported("weight is outside the supported range")
}
