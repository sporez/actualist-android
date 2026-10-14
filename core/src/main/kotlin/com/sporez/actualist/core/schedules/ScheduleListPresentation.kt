package com.sporez.actualist.core.schedules

import com.sporez.actualist.core.money.Money
import com.sporez.actualist.core.rules.ScheduleRecurrence
import java.text.Normalizer

/** The status groups of the schedules list, in display order. Port of Actualist `ScheduleListSectionKind`. */
enum class ScheduleListSection(val title: String) {
    MISSED("Missed"),
    DUE("Due"),
    UPCOMING("Upcoming"),
    PAID("Paid"),
    LATER("Later"),
    COMPLETED("Completed"),
}

/** One rendered group: a section and its schedules in list order. */
data class ScheduleListGroup(val section: ScheduleListSection, val items: List<ScheduleListItem>)

/**
 * Grouping, searching and wording for the schedules list. Port of Actualist `SchedulePresentation`
 * and the filtering in `SchedulesViewModel`.
 */
object ScheduleListPresentation {
    fun section(status: ScheduleStatus): ScheduleListSection = when (status) {
        ScheduleStatus.MISSED -> ScheduleListSection.MISSED
        ScheduleStatus.DUE -> ScheduleListSection.DUE
        ScheduleStatus.UPCOMING -> ScheduleListSection.UPCOMING
        ScheduleStatus.PAID -> ScheduleListSection.PAID
        ScheduleStatus.SCHEDULED -> ScheduleListSection.LATER
        ScheduleStatus.COMPLETED -> ScheduleListSection.COMPLETED
    }

    fun statusLabel(status: ScheduleStatus): String = when (status) {
        ScheduleStatus.COMPLETED -> "Completed"
        ScheduleStatus.PAID -> "Paid"
        ScheduleStatus.DUE -> "Due today"
        ScheduleStatus.UPCOMING -> "Upcoming"
        ScheduleStatus.MISSED -> "Missed"
        ScheduleStatus.SCHEDULED -> "Scheduled"
    }

    fun completedCount(items: List<ScheduleListItem>): Int = items.count { it.status == ScheduleStatus.COMPLETED }

    /** The text a search query is matched against: name, payee, account, amount, next date and status. */
    fun searchableText(item: ScheduleListItem): String {
        val amounts = listOfNotNull(item.amountMinorUnits, item.amountLower, item.amountUpper).distinct()
            .joinToString(" ") { Money.format(kotlin.math.abs(it), symbol = "", grouping = false) }
        return listOfNotNull(item.name, item.payeeName, item.accountName, amounts, item.nextDate, statusLabel(item.status))
            .joinToString(" ")
    }

    fun matches(item: ScheduleListItem, query: String): Boolean {
        val needle = fold(query.trim())
        return needle.isEmpty() || fold(searchableText(item)).contains(needle)
    }

    /**
     * The non-empty groups for [query]. Completed schedules appear only when [showCompleted];
     * sections keep [ScheduleListSection] order and rows keep the order of [items].
     */
    fun groups(items: List<ScheduleListItem>, query: String, showCompleted: Boolean): List<ScheduleListGroup> {
        val visible = items.filter { (showCompleted || it.status != ScheduleStatus.COMPLETED) && matches(it, query) }
        return ScheduleListSection.values().mapNotNull { section ->
            val rows = visible.filter { section(it.status) == section }
            if (rows.isEmpty()) null else ScheduleListGroup(section, rows)
        }
    }

    /** Up to [count] occurrences from [firstDate] on, for the detail view. A one-time schedule has just its date. */
    fun upcomingDates(recurrence: ScheduleRecurrence?, firstDate: String?, count: Int = 5): List<String> {
        if (firstDate == null) return emptyList()
        val dates = mutableListOf(firstDate)
        if (recurrence == null) return dates
        while (dates.size < count) {
            val next = try {
                recurrence.nextDateAfter(dates.last())
            } catch (e: Exception) {
                null
            }
            if (next == null || next == dates.last()) break
            dates.add(next)
        }
        return dates
    }

    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
}
