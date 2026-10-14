package com.sporez.actualist.core.budget

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.demo.DemoBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CategoryWritesTest {
    @Test
    fun shoveMatchesActualistsSortOrderRules() {
        assertEquals(SortOrder.Result(16_384.0, emptyList()), SortOrder.shove(emptyList(), null))
        val items = listOf(SortOrder.Item("a", 16_384.0), SortOrder.Item("b", 32_768.0))
        assertEquals(8_192.0, SortOrder.shove(items, "a").sortOrder, 0.0)
        assertEquals(24_576.0, SortOrder.shove(items, "b").sortOrder, 0.0)
        assertEquals(49_152.0, SortOrder.shove(items, null).sortOrder, 0.0)
        // No room left before the target: the target and later rows are renumbered.
        val tight = listOf(SortOrder.Item("a", 1.0), SortOrder.Item("b", 2.0))
        val result = SortOrder.shove(tight, "a")
        assertEquals(0.5, result.sortOrder, 0.0)
        assertEquals(listOf(SortOrder.Item("a", 16_385.0), SortOrder.Item("b", 32_769.0)), result.updates)
    }

    @Test
    fun groupsAndCategoriesAreCreatedRenamedAndHidden() {
        val clock = TestClock()
        DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, LocalDate.of(2026, 10, 15), clock).use { database ->
            var n = 0
            val writes = CategoryWrites(database) { "new-${++n}" }
            val (groupId, _) = writes.createGroup("Synthetic Projects")
            val (categoryId, messages) = writes.createCategory("Garden", groupId)
            assertTrue(messages.any { it.dataset == "category_mapping" && it.serializedValue == "S:$categoryId" })
            val month = BudgetReads(database).fetchBudgetMonth("2026-10")
            val group = month.categoryGroups.first { it.id == groupId }
            assertEquals(listOf("Garden"), group.categories.map { it.name })
            // Inserted at the top of an existing group.
            val (pets, _) = writes.createCategory("Pets", "lifestyle")
            assertEquals(pets, BudgetReads(database).fetchBudgetMonth("2026-10").categoryGroups.first { it.id == "lifestyle" }.categories.first().id)
            assertThrows(LocalFirstException.InvalidLocalWrite::class.java) { writes.createCategory("dining", "lifestyle") }
            assertThrows(LocalFirstException.InvalidLocalWrite::class.java) { writes.createGroup("essentials") }
            writes.renameCategory(categoryId, "Garden & Yard")
            writes.setCategoryHidden(categoryId, true)
            val renamed = BudgetReads(database).fetchBudgetMonth("2026-10").category(categoryId)!!
            assertEquals("Garden & Yard", renamed.name)
            assertTrue(renamed.hidden)
            // The new category takes budget money like any other.
            BudgetWrites(database).assign("2026-10", categoryId, 5_000)
            assertEquals(5_000L, BudgetReads(database).fetchBudgetMonth("2026-10").category(categoryId)!!.budgeted)
        }
    }
}
