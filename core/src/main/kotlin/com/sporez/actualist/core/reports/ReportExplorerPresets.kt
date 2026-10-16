package com.sporez.actualist.core.reports

/** The report, range and interval the Explorer opens with for a Reports card (Actualist's explorerMetric, explorerDefaultPreset and explorerDefaultInterval). */
data class ExplorerPreset(val metric: ReportMetric, val range: RangePreset, val interval: ReportInterval)

object ReportExplorerPresets {
    /** The preset for a [ReportCardOrder] card id; null for an unknown id. */
    fun forCard(cardId: String): ExplorerPreset? = when (cardId) {
        "net-worth" -> ExplorerPreset(ReportMetric.NET_WORTH, RangePreset.SIX_MONTHS, ReportInterval.MONTH)
        "cash-flow" -> ExplorerPreset(ReportMetric.CASH_FLOW, RangePreset.MONTH_TO_DATE, ReportInterval.MONTH)
        "month-comparison" -> ExplorerPreset(ReportMetric.SPENDING, RangePreset.MONTH_TO_DATE, ReportInterval.DAY)
        "spending" -> ExplorerPreset(ReportMetric.SPENDING, RangePreset.MONTH_TO_DATE, ReportInterval.DAY)
        "budget-comparison" -> ExplorerPreset(ReportMetric.BUDGET_OVERVIEW, RangePreset.MONTH_TO_DATE, ReportInterval.DAY)
        "three-month-average" -> ExplorerPreset(ReportMetric.SPENDING_AVERAGE, RangePreset.MONTH_TO_DATE, ReportInterval.DAY)
        "transaction-calendar" -> ExplorerPreset(ReportMetric.CASH_FLOW, RangePreset.MONTH_TO_DATE, ReportInterval.DAY)
        else -> null
    }

    /**
     * The filters a card's details open with, so they show the same numbers the card does. The Calendar card
     * follows Actual's calendar widget: every account, with transfers as its card conditions choose
     * ([calendarTransfers]); every other card starts from its report's own defaults.
     */
    fun defaultFilters(cardId: String, calendarTransfers: ReportTransferFilter = ReportTransferFilter.ALL): SeriesFilters {
        val filters = SeriesFilters.defaults(forCard(cardId)?.metric ?: ReportMetric.CASH_FLOW)
        return if (cardId == "transaction-calendar") filters.copy(includeOffBudget = true, transfers = calendarTransfers) else filters
    }
}

/** Shows the matching transactions a page at a time. */
object ExplorerPaging {
    const val PAGE_SIZE = 50

    /** How many rows to show after one more page, never past [total]. */
    fun next(shown: Int, total: Int): Int = (shown + PAGE_SIZE).coerceAtMost(total.coerceAtLeast(0))

    fun hasMore(shown: Int, total: Int): Boolean = shown < total
}
