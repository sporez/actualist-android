package com.sporez.actualist.core.schedules

import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.Clock
import com.sporez.actualist.core.rules.ScheduleEnding
import com.sporez.actualist.core.rules.ScheduleFrequency
import com.sporez.actualist.core.rules.SchedulePattern
import com.sporez.actualist.core.rules.ScheduleRecurrence
import com.sporez.actualist.core.rules.WeekendAdjustment

/** One row of the schedules list. Amounts are integer minor units; dates are `YYYY-MM-DD`. */
data class ScheduleListItem(
    val id: String,
    val name: String,
    val payeeName: String?,
    val accountId: String?,
    val accountName: String?,
    val amount: ScheduleAmount,
    /** The amount to show, or the midpoint of a range; null when the amount cannot be read. */
    val amountMinorUnits: Long?,
    val amountLower: Long?,
    val amountUpper: Long?,
    val isApproximate: Boolean,
    val nextDate: String?,
    val status: ScheduleStatus,
    val recurrenceDescription: String,
    val isRecurring: Boolean,
    val postsTransaction: Boolean,
    val completed: Boolean,
    val isSupported: Boolean,
    val canPost: Boolean,
    val canSkip: Boolean,
    val canComplete: Boolean,
    val canDelete: Boolean,
    val canEdit: Boolean,
)

/** A schedule's detail for an editor: the list item plus its editable definition. */
data class ScheduleEditorModel(
    val item: ScheduleListItem,
    val dateRule: ScheduleDateRule,
    val customUpcomingLength: String?,
    val unsupportedMessages: List<String>,
)

/** English descriptions of a schedule's date rule. */
object ScheduleDescriptions {
    fun describe(rule: ScheduleDateRule): String = when (rule) {
        is ScheduleDateRule.OneTime -> "Once on ${rule.dayId}"
        is ScheduleDateRule.Recurring -> describe(rule.recurrenceValue)
        is ScheduleDateRule.Unavailable -> "Unsupported schedule"
    }

    fun describe(recurrence: ScheduleRecurrence): String {
        val interval = recurrence.interval
        val unit = when (recurrence.frequency) {
            ScheduleFrequency.DAILY -> "day"
            ScheduleFrequency.WEEKLY -> "week"
            ScheduleFrequency.MONTHLY -> "month"
            ScheduleFrequency.YEARLY -> "year"
        }
        val builder = StringBuilder()
        builder.append(if (interval == 1L) "Every $unit" else "Every $interval ${unit}s")
        if (recurrence.patterns.isNotEmpty()) {
            builder.append(" on ").append(recurrence.patterns.joinToString(", ") { patternText(it) })
        }
        if (recurrence.skipWeekend) {
            builder.append(
                if (recurrence.weekendAdjustment == WeekendAdjustment.BEFORE) ", before the weekend" else ", after the weekend",
            )
        }
        when (val limit = recurrence.ending) {
            is ScheduleEnding.Never -> Unit
            is ScheduleEnding.AfterOccurrences -> builder.append(", ").append(limit.count).append(if (limit.count == 1L) " time" else " times")
            is ScheduleEnding.OnDate -> builder.append(", until ").append(limit.dayId)
        }
        return builder.toString()
    }

    private fun patternText(pattern: SchedulePattern): String = when (pattern) {
        is SchedulePattern.DayOfMonth -> if (pattern.day == -1) "the last day" else "the ${ordinal(pattern.day)}"
        is SchedulePattern.Weekday -> {
            val name = when (pattern.weekday.raw) {
                "SU" -> "Sunday"
                "MO" -> "Monday"
                "TU" -> "Tuesday"
                "WE" -> "Wednesday"
                "TH" -> "Thursday"
                "FR" -> "Friday"
                else -> "Saturday"
            }
            if (pattern.ordinal == -1) "the last $name" else "the ${ordinal(pattern.ordinal)} $name"
        }
    }

    private fun ordinal(value: Int): String {
        if (value < 0) return "${ordinal(-value)} from the end"
        val suffix = if (value % 100 in 11..13) {
            "th"
        } else {
            when (value % 10) {
                1 -> "st"
                2 -> "nd"
                3 -> "rd"
                else -> "th"
            }
        }
        return "$value$suffix"
    }
}

/**
 * The schedules feature the UI talks to: list schedules, post, skip, complete, delete, create,
 * edit, and the automatic daily advancement. [today] values are `YYYY-MM-DD` days in the user's
 * zone, supplied by the caller (this layer never reads the machine zone).
 */
class Schedules(
    private val database: BudgetDatabase,
    private val clock: Clock = Clock.SYSTEM,
) {
    val reads = ScheduleReads(database)
    val writes = ScheduleWrites(database, clock)
    val posting = SchedulePosting(database, clock)
    val advancement = ScheduleAdvancement(database, clock, posting, writes)

    /** Schedules in Actual's order: open schedules by next date, completed ones last. */
    fun list(today: String): List<ScheduleListItem> {
        val loaded = reads.fetchSchedules(today)
        return loaded.schedules.map { listItem(it, loaded.detailsById[it.id]) }
    }

    fun loaded(today: String): LoadedSchedules = reads.fetchSchedules(today)

    fun editor(scheduleId: String, today: String): ScheduleEditorModel? {
        val detail = reads.fetchScheduleDetail(scheduleId, today) ?: return null
        return ScheduleEditorModel(
            item = listItem(detail.summary, detail),
            dateRule = detail.dateRule,
            customUpcomingLength = detail.customUpcomingLength,
            unsupportedMessages = detail.unsupportedReasons.map { it.message }.distinct(),
        )
    }

    /** Whether new schedules can be written to this budget's file. */
    fun supportsAuthoring(today: String): Boolean = reads.fetchSchedules(today).supportsAuthoring

    fun create(command: ScheduleCreateCommand, nowMillis: Long = clock.nowMillis()): ScheduleMutationResult =
        writes.createSchedule(command, nowMillis)

    fun update(
        scheduleId: String,
        fields: ScheduleEditFields,
        today: String,
        nowMillis: Long = clock.nowMillis(),
    ): ScheduleMutationResult = writes.updateSchedule(reads.scheduleMutationReview(scheduleId), fields, today, nowMillis)

    /** Posts the occurrence at the schedule's next date, or at [today] when [postsToday]. */
    fun postNow(
        scheduleId: String,
        today: String,
        postsToday: Boolean = false,
        nowMillis: Long = clock.nowMillis(),
    ): SchedulePostingReceipt = posting.postSchedule(scheduleId, today, postsToday, nowMillis)

    /** Moves a recurring schedule to its next date. A one-time schedule cannot be skipped. */
    fun skipNextDate(scheduleId: String, nowMillis: Long = clock.nowMillis()): ScheduleMutationResult =
        writes.skipNextDate(reads.scheduleMutationReview(scheduleId), nowMillis)

    /** Completes a one-time schedule. Completing an already completed schedule changes nothing. */
    fun complete(scheduleId: String, nowMillis: Long = clock.nowMillis()): ScheduleMutationResult =
        writes.completeSchedule(reads.scheduleMutationReview(scheduleId), nowMillis)

    fun delete(scheduleId: String, nowMillis: Long = clock.nowMillis()): ScheduleMutationResult =
        writes.deleteSchedule(reads.scheduleMutationReview(scheduleId), nowMillis)

    /** The automatic daily run: posts due and missed automatic schedules and advances paid ones. */
    fun advance(today: String, marker: ScheduleRunMarker? = null, nowMillis: Long = clock.nowMillis()): ScheduleAdvancementResult =
        advancement.advanceSchedules(today, nowMillis, marker)

    private fun listItem(summary: ScheduleSummary, detail: ScheduleDetail?): ScheduleListItem {
        val dateRule = detail?.dateRule ?: ScheduleDateRule.Unavailable
        val capabilities = detail?.capabilities
        val amount = summary.amount
        val lower: Long?
        val upper: Long?
        when (amount) {
            is ScheduleAmount.Exact -> {
                lower = amount.amount
                upper = amount.amount
            }
            is ScheduleAmount.Approximate -> {
                lower = amount.amount
                upper = amount.amount
            }
            is ScheduleAmount.Range -> {
                lower = amount.lower
                upper = amount.upper
            }
            is ScheduleAmount.Unavailable -> {
                lower = null
                upper = null
            }
        }
        return ScheduleListItem(
            id = summary.id,
            name = summary.displayName,
            payeeName = summary.payee.name,
            accountId = summary.account.id,
            accountName = summary.account.name,
            amount = amount,
            amountMinorUnits = amount.postingAmount,
            amountLower = lower,
            amountUpper = upper,
            isApproximate = amount is ScheduleAmount.Approximate,
            nextDate = summary.effectiveNextDate,
            status = summary.status,
            recurrenceDescription = ScheduleDescriptions.describe(dateRule),
            isRecurring = dateRule.recurrence != null,
            postsTransaction = summary.postsTransaction,
            completed = summary.status == ScheduleStatus.COMPLETED,
            isSupported = summary.isSupported,
            canPost = capabilities?.canPost == true && summary.status.allowsManualPosting,
            canSkip = capabilities?.canSkip == true && summary.status != ScheduleStatus.COMPLETED,
            canComplete = capabilities?.canComplete == true,
            canDelete = capabilities?.canDelete == true,
            canEdit = capabilities?.canEdit == true,
        )
    }
}
