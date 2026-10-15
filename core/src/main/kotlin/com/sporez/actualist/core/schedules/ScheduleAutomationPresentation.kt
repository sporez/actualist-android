package com.sporez.actualist.core.schedules

import com.sporez.actualist.core.rules.ScheduleRecurrence

/** The choices for a schedule's own "upcoming window" (Actualist `ScheduleManagementSheet`). A null value is the budget default. */
object ScheduleUpcomingWindow {
    val options: List<Pair<String?, String>> = listOf(
        null to "Budget default",
        "7" to "7 days",
        "14" to "14 days",
        "30" to "30 days",
        "currentMonth" to "Current month",
        "oneMonth" to "One month",
    )

    fun label(value: String?): String = options.firstOrNull { it.first == value }?.second ?: "$value days"
}

/** One "Not Posted Automatically" row: the schedule, and why its automatic post was refused. */
data class ScheduleRefusalRow(val scheduleId: String, val title: String, val message: String)

object ScheduleAutoPostRefusals {
    /**
     * Refusals that still apply: the schedule still exists and its next date is still the refused
     * occurrence (Actualist `SchedulesViewModel.autoPostRefusalRows`).
     */
    fun rows(refusals: List<ScheduleAutoPostRefusal>, items: List<ScheduleListItem>): List<ScheduleRefusalRow> =
        refusals.mapNotNull { refusal ->
            val item = items.firstOrNull { it.id == refusal.scheduleId } ?: return@mapNotNull null
            if (item.nextDate != refusal.occurrenceDayId) return@mapNotNull null
            ScheduleRefusalRow(item.id, item.name, refusal.refusal.message)
        }
}

/** What the Skip, Complete and Delete review steps show before anything is written. */
object ScheduleReviewPresentation {
    /** The date a skip moves a recurring schedule to, or null when there is none (one-time, or the rule has ended). */
    fun nextDateAfterSkip(recurrence: ScheduleRecurrence?, nextDate: String?): String? {
        if (recurrence == null || nextDate == null) return null
        return try {
            recurrence.nextDateAfter(nextDate)?.takeIf { it != nextDate }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Whether a post reviewed against [reviewed] is still the occurrence on screen once the budget has
     * synced. Another device may have posted it (the date moved on or it can no longer be posted).
     */
    fun stillPostable(reviewed: ScheduleListItem, current: ScheduleListItem?): Boolean =
        current != null && current.canPost && current.nextDate == reviewed.nextDate
}
