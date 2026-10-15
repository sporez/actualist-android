package com.sporez.actualist.core.budget

import org.junit.Assert.assertEquals
import org.junit.Test

class CarryoverHorizonTest {
    @Test
    fun horizonIsTwelveMonthsAfterTheCurrentMonth() {
        assertEquals("2027-10", BudgetWrites.carryoverHorizonMonth("2026-10", "2026-10"))
        assertEquals("2027-10", BudgetWrites.carryoverHorizonMonth("2026-03", "2026-10"))
        assertEquals("2027-01", BudgetWrites.carryoverHorizonMonth("2025-12", "2026-01"))
    }

    @Test
    fun horizonNeverPrecedesTheStartMonth() {
        assertEquals("2030-05", BudgetWrites.carryoverHorizonMonth("2030-05", "2026-10"))
    }
}
