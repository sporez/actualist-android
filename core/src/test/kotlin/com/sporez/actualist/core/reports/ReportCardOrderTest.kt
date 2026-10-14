package com.sporez.actualist.core.reports

import org.junit.Assert.assertEquals
import org.junit.Test

class ReportCardOrderTest {
    private val defaults = ReportCardOrder.defaults

    @Test
    fun resolveKeepsSavedOrderDropsUnknownAndAppendsMissing() {
        val resolved = ReportCardOrder.resolve(listOf("spending", "bogus", "net-worth", "spending"))
        assertEquals(listOf("spending", "net-worth"), resolved.take(2))
        assertEquals(defaults.toSet(), resolved.toSet())
        assertEquals(defaults.size, resolved.size)
        assertEquals(defaults, ReportCardOrder.resolve(emptyList()))
    }

    @Test
    fun moveStaysInsideTheList() {
        assertEquals(defaults, ReportCardOrder.move(defaults, defaults.first(), -1))
        assertEquals(defaults, ReportCardOrder.move(defaults, defaults.last(), 1))
        assertEquals(defaults, ReportCardOrder.move(defaults, "missing", 1))
        val moved = ReportCardOrder.move(defaults, "cash-flow", -1)
        assertEquals(listOf("cash-flow", "net-worth"), moved.take(2))
        assertEquals(defaults.drop(2), moved.drop(2))
    }

    @Test
    fun encodeAndDecodeRoundTrip() {
        val order = ReportCardOrder.move(defaults, "spending", -2)
        assertEquals(order, ReportCardOrder.decode(ReportCardOrder.encode(order)))
        assertEquals(emptyList<String>(), ReportCardOrder.decode(null))
    }
}
