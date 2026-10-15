package com.sporez.actualist.core.reports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReportExplorerPresetsTest {
    @Test
    fun everyReportCardHasAPreset() {
        for (id in ReportCardOrder.defaults) assertEquals(id, id.takeIf { ReportExplorerPresets.forCard(it) != null })
        assertNull(ReportExplorerPresets.forCard("nope"))
    }

    @Test
    fun presetsFollowActualist() {
        assertEquals(ExplorerPreset(ReportMetric.NET_WORTH, RangePreset.SIX_MONTHS, ReportInterval.MONTH), ReportExplorerPresets.forCard("net-worth"))
        assertEquals(ExplorerPreset(ReportMetric.CASH_FLOW, RangePreset.MONTH_TO_DATE, ReportInterval.MONTH), ReportExplorerPresets.forCard("cash-flow"))
        assertEquals(ReportMetric.SPENDING_AVERAGE, ReportExplorerPresets.forCard("three-month-average")?.metric)
        assertEquals(ReportMetric.CASH_FLOW, ReportExplorerPresets.forCard("transaction-calendar")?.metric)
    }

    @Test
    fun pagingGrowsByPageAndStopsAtTheTotal() {
        assertEquals(50, ExplorerPaging.next(0, 500))
        assertEquals(120, ExplorerPaging.next(100, 120))
        assertEquals(0, ExplorerPaging.next(0, 0))
        assertEquals(true, ExplorerPaging.hasMore(50, 51))
        assertEquals(false, ExplorerPaging.hasMore(51, 51))
    }
}
