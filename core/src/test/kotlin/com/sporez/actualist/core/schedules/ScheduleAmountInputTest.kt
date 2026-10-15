package com.sporez.actualist.core.schedules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleAmountInputTest {
    @Test
    fun exactAndAboutAmountsTakeTheSign() {
        assertEquals(ScheduleAmountDraft.Exact(-2500), ScheduleAmountInput.draft(ScheduleAmountMode.EXACT, true, "25.00", ""))
        assertEquals(ScheduleAmountDraft.Approximate(2500), ScheduleAmountInput.draft(ScheduleAmountMode.ABOUT, false, "25", ""))
        assertNull(ScheduleAmountInput.draft(ScheduleAmountMode.EXACT, true, "0", ""))
        assertNull(ScheduleAmountInput.draft(ScheduleAmountMode.EXACT, true, "abc", ""))
    }

    @Test
    fun aRangeKeepsTheLowerAmountFirst() {
        assertEquals(ScheduleAmountDraft.Range(-4000, -2000), ScheduleAmountInput.draft(ScheduleAmountMode.RANGE, true, "40.00", "20.00"))
        assertEquals(ScheduleAmountDraft.Range(2000, 4000), ScheduleAmountInput.draft(ScheduleAmountMode.RANGE, false, "20", "40"))
        assertNull(ScheduleAmountInput.draft(ScheduleAmountMode.RANGE, true, "20", ""))
        assertNull(ScheduleAmountInput.draft(ScheduleAmountMode.RANGE, true, "20", "20"))
    }

    @Test
    fun existingAmountsFillTheFields() {
        val range = ScheduleAmount.Range(-4000, -2000, -3000)
        assertEquals(ScheduleAmountMode.RANGE, ScheduleAmountInput.mode(range))
        assertEquals("20.00" to "40.00", ScheduleAmountInput.texts(range))
        assertTrue(ScheduleAmountInput.isOutflow(range))
        assertEquals("12.50" to "", ScheduleAmountInput.texts(ScheduleAmount.Approximate(1250)))
        assertFalse(ScheduleAmountInput.isEditable(ScheduleAmount.Range(-100, 100, 0)))
        assertTrue(ScheduleAmountInput.isEditable(range))
        assertNull(ScheduleAmountInput.mode(ScheduleAmount.Unavailable))
    }
}
