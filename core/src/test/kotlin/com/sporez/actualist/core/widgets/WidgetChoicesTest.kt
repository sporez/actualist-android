package com.sporez.actualist.core.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetChoicesTest {
    @Test
    fun encodeAndDecodeRoundTripInOrderAndDropBlanksAndDuplicates() {
        assertEquals(listOf("b", "a"), WidgetChoices.decode(WidgetChoices.encode(listOf("b", "a"))))
        assertEquals(listOf("a", "b"), WidgetChoices.decode("a\n\nb\na"))
        assertNull(WidgetChoices.decode(null))
        assertNull(WidgetChoices.decode(""))
    }

    @Test
    fun pickKeepsChosenOrderDropsStaleAndFallsBack() {
        val available = listOf("x", "y", "z")
        assertEquals(listOf("z", "x"), WidgetChoices.pick(listOf("z", "gone", "x"), available, 5, listOf("y"), { it }))
        assertEquals(listOf("z"), WidgetChoices.pick(listOf("z", "x"), available, 1, listOf("y"), { it }))
        assertEquals(listOf("y", "x"), WidgetChoices.pick(listOf("gone"), available, 5, listOf("y", "x"), { it }))
        assertEquals(listOf("y", "x"), WidgetChoices.pick(null, available, 5, listOf("y", "x"), { it }))
    }

    @Test
    fun quickActionsAreToppedUpWithDefaultsToFour() {
        val known = setOf("a", "b", "c", "d", "e")
        assertEquals(listOf("e", "a", "b", "c"), WidgetChoices.quickActions(listOf("e", "nope"), known, listOf("a", "b", "c", "d")))
        assertEquals(listOf("a", "b", "c", "d"), WidgetChoices.quickActions(null, known, listOf("a", "b", "c", "d")))
        assertEquals(4, WidgetChoices.quickActions(listOf("a", "b", "c", "d", "e"), known, listOf("a")).size)
    }

    @Test
    fun toggleAppendsRemovesAndStopsAtTheLimit() {
        assertEquals(listOf("a", "b"), WidgetChoices.toggle(listOf("a"), "b", 2))
        assertEquals(listOf("a", "b"), WidgetChoices.toggle(listOf("a", "b"), "c", 2))
        assertEquals(listOf("b"), WidgetChoices.toggle(listOf("a", "b"), "a", 2))
    }

    @Test
    fun sizeClassFollowsTheCellSize() {
        assertEquals(WidgetSizeClass.SMALL, WidgetSizeClass.of(110, 110))
        assertEquals(WidgetSizeClass.MEDIUM, WidgetSizeClass.of(250, 110))
        assertEquals(WidgetSizeClass.LARGE, WidgetSizeClass.of(250, 250))
        assertEquals(3, WidgetSizeClass.MEDIUM.categoryRows)
    }

    @Test
    fun sparklineScalesBetweenLowestAndHighest() {
        assertEquals("▁▄█", Sparkline.render(listOf(0L, 50L, 100L)))
        assertEquals("▁█", Sparkline.render(listOf(-10L, 10L)))
        assertEquals("▄▄▄", Sparkline.render(listOf(5L, 5L, 5L)))
        assertEquals("", Sparkline.render(emptyList()))
    }
}
