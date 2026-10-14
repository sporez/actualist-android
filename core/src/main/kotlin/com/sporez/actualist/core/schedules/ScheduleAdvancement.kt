package com.sporez.actualist.core.schedules

import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.Clock
import com.sporez.actualist.core.rules.ScheduleRecurrence
import java.util.UUID

/** One schedule whose automatic post was deterministically refused. */
data class ScheduleAutoPostRefusal(
    val scheduleId: String,
    val scheduleName: String?,
    /** The occurrence day that was refused; a later run or edit moves past it. */
    val occurrenceDayId: String,
    val refusal: SchedulePostingRefusal,
)

data class ScheduleAdvancementResult(
    val receipts: List<SchedulePostingReceipt>,
    val scheduleMutated: Boolean,
    val refusals: List<ScheduleAutoPostRefusal> = emptyList(),
    /** The day marker already matched, so nothing ran and refusals are unknown. */
    val skippedForToday: Boolean = false,
)

/**
 * The local "last schedule run" day marker. Actual keeps `lastScheduleRun` in the budget's
 * `metadata.json`; the app supplies a file-backed implementation. Without a marker every call runs.
 */
interface ScheduleRunMarker {
    fun lastRunDay(): String?
    fun markRun(dayId: String)
}

/**
 * Posts due or missed automatic schedules and advances paid occurrences. Port of Actualist
 * `BudgetDatabase+ScheduleAdvancement`. A typed refusal skips only that schedule and the day is
 * still marked done; any other error stops the run with the marker unset, so the next sync retries.
 */
class ScheduleAdvancement(
    private val database: BudgetDatabase,
    private val clock: Clock = Clock.SYSTEM,
    private val posting: SchedulePosting = SchedulePosting(database, clock),
    private val writes: ScheduleWrites = ScheduleWrites(database, clock),
) {
    private val reads = ScheduleReads(database)

    private enum class Step { NEXT_SCHEDULE, STOP_RUN }

    private enum class RecurringAdvance { DATE_CHANGED, DATE_UNCHANGED, STOP_RUN }

    private class RunState {
        val receipts = mutableListOf<SchedulePostingReceipt>()
        val refusals = mutableListOf<ScheduleAutoPostRefusal>()
        var scheduleMutated = false

        fun result() = ScheduleAdvancementResult(receipts.toList(), scheduleMutated, refusals.toList())
    }

    fun advanceSchedules(
        today: String,
        nowMillis: Long = clock.nowMillis(),
        marker: ScheduleRunMarker? = null,
    ): ScheduleAdvancementResult {
        if (marker != null && marker.lastRunDay() == today) {
            return ScheduleAdvancementResult(emptyList(), false, skippedForToday = true)
        }
        val state = RunState()
        val ordered = inAdvancementOrder(reads.fetchSchedules(today).detailsById.values.toList())
        for (detail in ordered) {
            val step = advanceSchedule(detail.id, today, nowMillis, state)
            if (step == Step.STOP_RUN) return state.result()
        }
        // A marker write failure must not hide committed posts. Leaving the marker unset makes
        // the next successful sync retry.
        try {
            marker?.markRun(today)
        } catch (e: Exception) {
            return state.result()
        }
        return state.result()
    }

    private fun advanceSchedule(scheduleId: String, today: String, nowMillis: Long, state: RunState): Step {
        for (iteration in 0 until MAXIMUM_OCCURRENCES_PER_SCHEDULE) {
            val detail = reads.fetchScheduleDetail(scheduleId, today) ?: return Step.NEXT_SCHEDULE
            val action = ScheduleOccurrencePlanner.action(
                postsTransaction = detail.postsTransaction,
                isRecurring = detail.dateRule.recurrence != null,
                nextDate = detail.effectiveNextDate ?: "",
                status = detail.status,
                accountAvailable = detail.account.availability == ScheduleReferenceAvailability.AVAILABLE,
                today = today,
            )
            when (action) {
                ScheduleAdvancementAction.STOP -> return Step.NEXT_SCHEDULE
                ScheduleAdvancementAction.POST_SCHEDULED_DATE -> {
                    val statusBefore = detail.status
                    val isRecurring = detail.dateRule.recurrence != null
                    try {
                        state.receipts.add(postScheduledOccurrence(detail, today, nowMillis))
                    } catch (e: SchedulePostingRefusalException) {
                        state.refusals.add(
                            ScheduleAutoPostRefusal(scheduleId, detail.name, detail.effectiveNextDate ?: "", e.refusal),
                        )
                        return Step.NEXT_SCHEDULE
                    } catch (e: ScheduleMutationCommandException) {
                        // Review preconditions (shared rule, missing schema, changed review) are
                        // typed and deterministic per schedule.
                        state.refusals.add(
                            ScheduleAutoPostRefusal(
                                scheduleId,
                                detail.name,
                                detail.effectiveNextDate ?: "",
                                SchedulePostingRefusal.UNSUPPORTED_OCCURRENCE,
                            ),
                        )
                        return Step.NEXT_SCHEDULE
                    } catch (e: Exception) {
                        return Step.STOP_RUN
                    }
                    // A due post, and any one-time post, ends this schedule. Do not complete a
                    // one-time schedule in the same run as its post.
                    if (statusBefore == ScheduleStatus.DUE || !isRecurring) return Step.NEXT_SCHEDULE
                    val moved = advanceRecurringOccurrence(scheduleId, today, nowMillis, false, state)
                    if (moved == RecurringAdvance.STOP_RUN) return Step.STOP_RUN
                    if (moved == RecurringAdvance.DATE_UNCHANGED) return Step.NEXT_SCHEDULE
                }
                ScheduleAdvancementAction.ADVANCE_RECURRING -> {
                    val moved = advanceRecurringOccurrence(scheduleId, today, nowMillis, false, state)
                    if (moved != RecurringAdvance.DATE_CHANGED) {
                        return if (moved == RecurringAdvance.STOP_RUN) Step.STOP_RUN else Step.NEXT_SCHEDULE
                    }
                }
                ScheduleAdvancementAction.RECOMPUTE_RECURRING -> {
                    val moved = advanceRecurringOccurrence(scheduleId, today, nowMillis, true, state)
                    return if (moved == RecurringAdvance.STOP_RUN) Step.STOP_RUN else Step.NEXT_SCHEDULE
                }
                ScheduleAdvancementAction.COMPLETE_ONE_TIME -> {
                    try {
                        val result = writes.completeSchedule(reads.scheduleMutationReview(scheduleId), nowMillis)
                        if (result.kind != ScheduleMutationKind.UNCHANGED) state.scheduleMutated = true
                    } catch (e: Exception) {
                        return Step.NEXT_SCHEDULE
                    }
                    return Step.NEXT_SCHEDULE
                }
            }
        }
        return Step.NEXT_SCHEDULE
    }

    private fun postScheduledOccurrence(detail: ScheduleDetail, today: String, nowMillis: Long): SchedulePostingReceipt {
        val accountId = detail.account.id
        val amount = detail.amount.postingAmount
        val postedDayId = detail.effectiveNextDate
        val transactionDate = postedDayId?.let { ScheduleRecurrence.parseDay(it) }
        if (accountId == null || amount == null || postedDayId == null || transactionDate == null) {
            throw SchedulePostingRefusalException(SchedulePostingRefusal.UNSUPPORTED_OCCURRENCE)
        }
        val draft = SchedulePostDraft(
            accountId = accountId,
            date = transactionDate,
            amount = amount,
            payeeId = detail.payee.postingPayeeId,
            payeeName = detail.payee.name ?: "",
            scheduleId = detail.id,
        )
        return posting.postScheduleOccurrence(
            review = reads.scheduleMutationReview(detail.id),
            draft = draft,
            transactionId = UUID.randomUUID().toString(),
            postedDayId = postedDayId,
            today = today,
            nowMillis = nowMillis,
        )
    }

    /**
     * Moves a recurring next date to the next date after the current one, like a user skip, or
     * (`fromToday`) to the first date from today.
     */
    private fun advanceRecurringOccurrence(
        scheduleId: String,
        today: String,
        nowMillis: Long,
        fromToday: Boolean,
        state: RunState,
    ): RecurringAdvance {
        val detail = reads.fetchScheduleDetail(scheduleId, today)
        val currentDayId = detail?.effectiveNextDate
        if (detail == null || currentDayId == null || detail.dateRule.recurrence == null) return RecurringAdvance.DATE_UNCHANGED
        val review = try {
            reads.scheduleMutationReview(scheduleId)
        } catch (e: Exception) {
            return RecurringAdvance.DATE_UNCHANGED
        }
        val committed = try {
            database.commitLocal(nowMillis) {
                val current = reads.validateReview(review)
                val recurrence = current.projection.dateRule.recurrence
                val effective = current.effectiveNextDate
                val next = current.review.uniqueNextDate
                val advanced = if (recurrence == null || effective == null || next == null) {
                    null
                } else if (fromToday) {
                    ScheduleRuleMutation.initialNextDate(current.projection.dateRule, today)
                } else {
                    recurrence.nextDateAfter(effective)
                }
                if (advanced == null || next == null || advanced == effective) {
                    emptyList<com.sporez.actualist.core.db.CellDraft>() to false
                } else {
                    // Same local next-date cells the skip writer enqueues.
                    writes.nextDateDrafts(next.id, advanced, false, next.baseTimestamp, nowMillis) to true
                }
            }
        } catch (e: Exception) {
            return RecurringAdvance.DATE_UNCHANGED
        }
        if (committed.messages.isNotEmpty()) state.scheduleMutated = true
        if (!committed.outcome) return RecurringAdvance.DATE_UNCHANGED
        val updated = reads.fetchScheduleDetail(scheduleId, today)?.effectiveNextDate
        if (updated == null || updated == currentDayId) return RecurringAdvance.DATE_UNCHANGED
        return RecurringAdvance.DATE_CHANGED
    }

    private fun inAdvancementOrder(details: List<ScheduleDetail>): List<ScheduleDetail> = details.sortedWith(
        Comparator { lhs, rhs ->
            val left = lhs.effectiveNextDate
            val right = rhs.effectiveNextDate
            when {
                left != null && right != null && left != right -> left.compareTo(right)
                left != null && right == null -> -1
                left == null && right != null -> 1
                else -> lhs.id.compareTo(rhs.id)
            }
        },
    )

    companion object {
        private const val MAXIMUM_OCCURRENCES_PER_SCHEDULE = 366
    }
}
