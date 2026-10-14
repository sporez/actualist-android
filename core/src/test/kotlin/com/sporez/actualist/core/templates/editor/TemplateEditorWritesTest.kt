package com.sporez.actualist.core.templates.editor

import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.demo.DemoBudget
import com.sporez.actualist.core.templates.BudgetTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** The template editor's write path on a real budget, previewed through the ported engine. */
class TemplateEditorWritesTest {
    private val day = LocalDate.of(2026, 10, 15)

    @Test
    fun editsAndCreatesTemplatesThatTheEnginePlans() {
        val clock = TestClock()
        DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, day, clock).use { db ->
            val writes = TemplateEditorWrites(db, clock, ZoneOffset.UTC)
            fun proposed(category: String) = BudgetTemplates(db, clock).preview("2026-11", today = day)
                .categories.single { it.categoryId == category }.proposed

            // The demo's stored simple template opens as a monthly amount.
            val groceries = writes.load("groceries")!!.entries.single() as TemplateDraftEntry.MonthlyFixed
            assertEquals(600.0, groceries.amount, 0.0)
            writes.save("groceries", TemplateEditorDraft(listOf(groceries.copy(amount = 650.0))))
            assertEquals(65_000L, proposed("groceries"))

            // A category without a template gets one, marked as written by the editor.
            assertTrue(writes.load("dining")!!.entries.isEmpty())
            writes.save("dining", TemplateEditorDraft(listOf(TemplateDraftEntry.monthlyFixed(amount = 80.0, today = day))))
            assertEquals("""{"source":"ui"}""", db.sql.queryString("SELECT template_settings FROM categories WHERE id = 'dining'"))
            assertEquals(8_000L, proposed("dining"))

            // Saving toward a total by a month plans a share of what is still missing each month.
            // (Shopping already carries about $1,251 into November, so a $1,200 target would release money.)
            writes.save("shopping", TemplateEditorDraft(listOf(TemplateDraftEntry.dateTarget(amount = 6_000.0, month = "2027-04", repeatInterval = null, annual = false, isSpend = false, today = day))))
            assertEquals(
                """[{"amount":6000,"directive":"template","month":"2027-04","priority":1,"type":"by"}]""",
                db.sql.queryString("SELECT goal_def FROM categories WHERE id = 'shopping'"),
            )
            val planned = BudgetTemplates(db, clock).preview("2026-11", today = day)
            val shopping = planned.categories.single { it.categoryId == "shopping" }
            assertTrue("$shopping", shopping.proposed > 0 && shopping.proposed < 600_000L)

            // A schedule template budgets the schedule's amount for its next date.
            writes.save("entertainment", TemplateEditorDraft(listOf(TemplateDraftEntry.schedule(name = "StreamFlix", scheduleId = "demo-schedule-streaming"))))
            val streaming = BudgetTemplates(db, clock).preview("2026-11", today = day, categoryIds = setOf("entertainment"))
            assertTrue("${streaming.errors} ${streaming.categories}", streaming.isReady && streaming.categories.single { it.categoryId == "entertainment" }.proposed > 0)

            // Saving an unchanged draft writes nothing; an empty draft clears the template.
            val unchanged = writes.load("dining")!!
            assertTrue(writes.save("dining", unchanged).isEmpty())
            writes.save("dining", TemplateEditorDraft(emptyList()))
            assertNull(db.sql.queryString("SELECT goal_def FROM categories WHERE id = 'dining'"))
        }
    }
}
