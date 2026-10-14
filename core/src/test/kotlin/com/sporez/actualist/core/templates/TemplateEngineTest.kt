package com.sporez.actualist.core.templates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

class TemplateEngineTest {
    private val engine = TemplateEngine(TemplateCurrency.USD)

    private fun plan(json: String, available: Long = 100_000, fromLastMonth: Long = 0, monthValue: Int = 202610): TemplateEngine.ComputePlan {
        val entries = engine.decodeSupportedEntries(json)!!
        return engine.computePlan(
            categories = mapOf("cat" to TemplateEngine.Category(entries, fromLastMonth, emptyMap())),
            orderedCategoryIds = listOf("cat"),
            monthValue = monthValue,
            availableBudget = available,
            skipAvailableClamp = false,
        )
    }

    @Test
    fun actualRoundTiesTowardPositiveInfinity() {
        assertEquals(-2L, TemplateMath.actualRound(-2.5))
        assertEquals(3L, TemplateMath.actualRound(2.5))
        assertEquals(0L, TemplateMath.actualRound(0.49999))
        assertEquals(-4L, TemplateMath.actualRound(-3.5000001))
    }

    @Test
    fun simpleTemplateBudgetsItsMonthlyAmount() {
        val result = plan("""[{"type":"simple","monthly":300,"priority":0,"directive":"template"}]""")
        assertEquals(listOf(30_000L), result.writes.map { it.amount })
        assertEquals(30_000L, result.writes.single().goal)
        assertEquals(70_000L, result.leftover)
    }

    @Test
    fun priorityTemplatesClampToAvailableFunds() {
        val result = plan("""[{"type":"simple","monthly":300,"priority":1,"directive":"template"}]""", available = 10_000)
        assertEquals(10_000L, result.writes.single().amount)
        assertEquals(30_000L, result.writes.single().goal)
        assertEquals(20_000L, result.clampShortfall)
    }

    @Test
    fun periodicWeeklyCountsEveryOccurrenceInTheMonth() {
        val json = """[{"type":"periodic","amount":10,"period":{"amount":1,"period":"week"},"starting":"2026-09-01","priority":0,"directive":"template"}]"""
        // Tuesdays in September 2026: the 1st, 8th, 15th, 22nd and 29th.
        assertEquals(5_000L, plan(json, monthValue = 202609).writes.single().amount)
    }

    @Test
    fun templateWithoutDirectiveIsRefused() {
        try {
            engine.decodeSupportedEntries("""[{"type":"simple","monthly":300,"priority":0}]""")
            fail("expected a refusal")
        } catch (error: UnsupportedTemplateException) {
            assertTrue(error.reason.contains("directive"))
        }
    }

    @Test
    fun errorEntriesAreNotActionable() {
        assertNull(engine.decodeSupportedEntries("""[{"type":"error","directive":"error"}]"""))
    }

    @Test
    fun calendarFindsMonthDistanceAndShiftsAcrossYears() {
        assertEquals(3, TemplateCalendar.monthDistance(202611, 202702))
        assertEquals(202701, TemplateCalendar.shiftedMonth(202612, 1))
        assertEquals(202512, TemplateCalendar.shiftedMonth(202601, -1))
        assertEquals(LocalDate.of(2024, 2, 29), TemplateCalendar.validatedDate("2024-02-29"))
        assertNull(TemplateCalendar.validatedDate("2025-02-29"))
    }

    @Test
    fun noteParserReadsTemplateDirectivesInOrder() {
        val directives = TemplateNoteParser.directives("Groceries\n#template 300\n#template schedule Rent full\n#goal 5000 by 2027-01")
        assertEquals(listOf("simple", "schedule", "goal"), directives.map { it.type })
        assertEquals(300.0, directives[0].monthly!!, 0.0)
        assertEquals("Rent", directives[1].scheduleName)
        assertTrue(directives[1].full)
        assertEquals("2027-01", directives[2].month)
    }

    @Test
    fun noteGuardFlagsAChangedAmountAndAcceptsAMatchingOne() {
        val directives = TemplateNoteParser.directives("#template 300")
        val matching = TemplateEntryDecoder.decode("""[{"type":"simple","monthly":300,"priority":0,"directive":"template"}]""")
        val changed = TemplateEntryDecoder.decode("""[{"type":"simple","monthly":250,"priority":0,"directive":"template"}]""")
        assertNull(TemplateNoteParser.stalenessReason(directives, matching))
        assertNotNull(TemplateNoteParser.stalenessReason(directives, changed))
        assertTrue(TemplateNoteGuard.isNoteManaged("notes", noteHasDirectives = false))
        assertTrue(!TemplateNoteGuard.isNoteManaged("ui", noteHasDirectives = true))
    }
}
