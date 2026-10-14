package com.sporez.actualist.core.budget

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.demo.DemoBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CategoryManagementTest {
    private val month = "2026-10"

    private fun demo(block: (BudgetDatabase, CategoryWrites) -> Unit) {
        DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, LocalDate.of(2026, 10, 15), TestClock()).use { database ->
            block(database, CategoryWrites(database))
        }
    }

    private fun order(database: BudgetDatabase, group: String) =
        BudgetReads(database).fetchBudgetMonth(month).categoryGroups.first { it.id == group }.categories.map { it.id }

    private fun groupOrder(database: BudgetDatabase) = BudgetReads(database).fetchBudgetMonth(month).categoryGroups.map { it.id }

    private fun count(database: BudgetDatabase, sql: String, vararg args: Any?) = database.sql.queryLong(sql, *args) ?: 0L

    @Test
    fun renamesAGroupAndRejectsDuplicates() = demo { database, writes ->
        writes.renameGroup("lifestyle", "Fun & Life")
        assertEquals("Fun & Life", BudgetReads(database).fetchBudgetMonth(month).categoryGroups.first { it.id == "lifestyle" }.name)
        assertThrows(LocalFirstException.InvalidLocalWrite::class.java) { writes.renameGroup("lifestyle", "essentials") }
        assertThrows(LocalFirstException.InvalidLocalWrite::class.java) { writes.renameGroup("lifestyle", "  ") }
        assertTrue(writes.renameGroup("lifestyle", "Fun & Life").isEmpty())
    }

    @Test
    fun hidesAndUnhides() = demo { database, writes ->
        writes.setCategoryHidden("dining", true)
        assertTrue(BudgetReads(database).fetchBudgetMonth(month).category("dining")!!.hidden)
        writes.setCategoryHidden("dining", false)
        assertFalse(BudgetReads(database).fetchBudgetMonth(month).category("dining")!!.hidden)
    }

    @Test
    fun movesACategoryUpAndDownWithinItsGroup() = demo { database, writes ->
        val before = order(database, "lifestyle")
        assertEquals(listOf("dining", "entertainment", "subscriptions", "shopping", "personal_care"), before)
        writes.moveCategoryBy("dining", 1)
        assertEquals(listOf("entertainment", "dining", "subscriptions", "shopping", "personal_care"), order(database, "lifestyle"))
        writes.moveCategoryBy("dining", -1)
        assertEquals(before, order(database, "lifestyle"))
        // The edges do nothing.
        assertTrue(writes.moveCategoryBy("dining", -1).isEmpty())
        assertTrue(writes.moveCategoryBy("personal_care", 1).isEmpty())
        writes.moveCategoryBy("personal_care", -1)
        assertEquals(listOf("dining", "entertainment", "subscriptions", "personal_care", "shopping"), order(database, "lifestyle"))
    }

    @Test
    fun movesACategoryToAnotherGroupBeforeATarget() = demo { database, writes ->
        writes.moveCategory("dining", "essentials", "utilities")
        assertEquals(listOf("rent", "groceries", "dining", "utilities", "transportation", "insurance"), order(database, "essentials"))
        assertFalse("dining" in order(database, "lifestyle"))
        writes.moveCategory("shopping", "savings", null)
        assertEquals("shopping", order(database, "savings").last())
        // Income and expense groups do not mix; a duplicate name in the target group is refused.
        assertThrows(LocalFirstException.InvalidLocalWrite::class.java) { writes.moveCategory("dining", "income", null) }
        writes.createCategory("Dining", "lifestyle")
        assertThrows(LocalFirstException.InvalidLocalWrite::class.java) { writes.moveCategory("dining", "lifestyle", null) }
    }

    @Test
    fun reordersGroupsWithTightSortOrders() = demo { database, writes ->
        assertEquals(listOf("essentials", "lifestyle", "savings", "income"), groupOrder(database))
        writes.moveGroupBy("lifestyle", -1)
        assertEquals(listOf("lifestyle", "essentials", "savings", "income"), groupOrder(database))
        writes.moveGroupBy("lifestyle", 1)
        assertEquals(listOf("essentials", "lifestyle", "savings", "income"), groupOrder(database))
        writes.moveGroup("income", "essentials")
        assertEquals(listOf("income", "essentials", "lifestyle", "savings"), groupOrder(database))
        assertTrue(writes.moveGroupBy("income", -1).isEmpty())
    }

    @Test
    fun deletesAnUnusedCategoryWithoutATransfer() = demo { database, writes ->
        val (id, _) = writes.createCategory("Garden", "lifestyle")
        assertFalse(writes.categoryNeedsTransfer(id))
        writes.deleteCategory(id, null)
        assertNull(BudgetReads(database).fetchBudgetMonth(month).category(id))
        assertEquals(1L, count(database, "SELECT tombstone FROM categories WHERE id = ?", id))
    }

    @Test
    fun aCategoryWithActivityNeedsATransferTarget() = demo { database, writes ->
        assertTrue(writes.categoryNeedsTransfer("groceries"))
        assertThrows(LocalFirstException.InvalidLocalWrite::class.java) { writes.deleteCategory("groceries", null) }
        assertThrows(LocalFirstException.InvalidLocalWrite::class.java) { writes.deleteCategory("groceries", "groceries") }
        assertThrows(LocalFirstException.InvalidLocalWrite::class.java) { writes.deleteCategory("groceries", "paycheck") }
        assertThrows(LocalFirstException.InvalidLocalWrite::class.java) { writes.deleteCategory("paycheck", null) }
        assertEquals(0L, count(database, "SELECT COUNT(*) FROM categories WHERE id = 'groceries' AND tombstone = 1"))
    }

    @Test
    fun deletingWithATransferMovesBudgetAndActivity() = demo { database, writes ->
        val reads = BudgetReads(database)
        val groceries = (0..6).map { Months.shifted(month, -it) }.associateWith { reads.fetchBudgetMonth(it).category("groceries")!! }
        val dining = (0..6).map { Months.shifted(month, -it) }.associateWith { reads.fetchBudgetMonth(it).category("dining")!! }
        assertTrue(groceries.values.any { it.budgeted != 0L })
        assertTrue(groceries.values.any { it.spent != 0L })

        val messages = writes.deleteCategory("groceries", "dining")
        assertTrue(messages.any { it.dataset == "categories" && it.row == "groceries" && it.column == "tombstone" })
        assertTrue(messages.any { it.dataset == "category_mapping" && it.row == "groceries" && it.serializedValue == "S:dining" })

        assertNull(reads.fetchBudgetMonth(month).category("groceries"))
        for ((m, original) in dining) {
            val after = reads.fetchBudgetMonth(m).category("dining")!!
            assertEquals("budgeted $m", original.budgeted + groceries.getValue(m).budgeted, after.budgeted)
            assertEquals("spent $m", original.spent + groceries.getValue(m).spent, after.spent)
        }
        assertEquals("dining", database.sql.queryOne("SELECT transferId FROM category_mapping WHERE id = 'groceries'")!!.string("transferId"))
        assertTrue(writes.categoryNeedsTransfer("dining"))
    }

    @Test
    fun forwardsEarlierTransfersToTheNewDestination() = demo { database, writes ->
        writes.deleteCategory("shopping", "dining")
        writes.deleteCategory("dining", "entertainment")
        for (id in listOf("shopping", "dining")) {
            assertEquals("entertainment", database.sql.queryOne("SELECT transferId FROM category_mapping WHERE id = ?", id)!!.string("transferId"))
        }
        assertEquals("entertainment", database.sql.queryOne("SELECT transferId FROM category_mapping WHERE id = 'entertainment'")!!.string("transferId"))
    }

    @Test
    fun deletesAGroupWithATransferTombstoningItsCategories() = demo { database, writes ->
        val reads = BudgetReads(database)
        val savings = reads.fetchBudgetMonth(month).categoryGroups.first { it.id == "savings" }.categories.map { it.id }
        assertTrue(writes.groupNeedsTransfer("savings"))
        assertThrows(LocalFirstException.InvalidLocalWrite::class.java) { writes.deleteGroup("savings", null) }
        // Cannot transfer into the group being deleted.
        assertThrows(LocalFirstException.InvalidLocalWrite::class.java) { writes.deleteGroup("savings", "vacation") }
        val before = reads.fetchBudgetMonth(month).category("rent")!!.budgeted
        writes.deleteGroup("savings", "rent")
        val after = reads.fetchBudgetMonth(month)
        assertFalse(after.categoryGroups.any { it.id == "savings" })
        assertTrue(savings.none { after.category(it) != null })
        assertTrue(after.category("rent")!!.budgeted > before)
        assertEquals(1L, count(database, "SELECT tombstone FROM category_groups WHERE id = 'savings'"))
        for (id in savings) {
            assertEquals(1L, count(database, "SELECT tombstone FROM categories WHERE id = ?", id))
            assertEquals("rent", database.sql.queryOne("SELECT transferId FROM category_mapping WHERE id = ?", id)!!.string("transferId"))
        }
    }

    @Test
    fun deletesAnEmptyGroupWithoutATransfer() = demo { database, writes ->
        val (group, _) = writes.createGroup("Scratch")
        assertFalse(writes.groupNeedsTransfer(group))
        writes.deleteGroup(group, null)
        assertFalse(groupOrder(database).contains(group))
    }
}
