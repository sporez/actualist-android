package com.sporez.actualist.core.splits

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.demo.DemoBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SplitFamiliesTest {
    private val clock = TestClock()
    private val day = LocalDate.of(2026, 10, 14)

    private fun demo() = DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, LocalDate.of(2026, 10, 15), clock)

    private fun families(database: BudgetDatabase): SplitFamilies {
        var counter = 0
        return SplitFamilies(database, clock) { "split-id-${++counter}" }
    }

    private fun draft(vararg splits: SplitChildDraft, amount: Long = -10_000, payee: String? = "grocer") = SplitFamilyDraft(
        accountId = "checking",
        date = day,
        amount = amount,
        payeeId = payee,
        notes = "weekly",
        splits = splits.toList(),
    )

    private fun live(database: BudgetDatabase, parentId: String): List<Map<String, Any?>> =
        database.sql.query(
            "SELECT id, amount, category, description, isParent, isChild, parent_id, sort_order, error, tombstone FROM transactions " +
                "WHERE (id = ? OR parent_id = ?) AND (tombstone IS NULL OR tombstone = 0) ORDER BY isParent DESC, sort_order DESC",
            parentId,
            parentId,
        ).map {
            mapOf(
                "id" to it.string("id"),
                "amount" to it.long("amount"),
                "category" to it.string("category"),
                "payee" to it.string("description"),
                "isParent" to it.bool("isParent"),
                "isChild" to it.bool("isChild"),
                "error" to it.string("error"),
                "sort" to it.double("sort_order"),
            )
        }

    @Test
    fun createsAFamilyWithAParentWithoutPayeeOrCategory() {
        demo().use { database ->
            val result = families(database).create(
                draft(SplitChildDraft(categoryId = "groceries", amount = -6_000), SplitChildDraft(categoryId = "dining", amount = -4_000)),
            )
            val rows = live(database, result.transactionId)
            assertEquals(3, rows.size)
            val parent = rows.first { it["isParent"] == true }
            assertEquals(-10_000L, parent["amount"])
            assertNull(parent["payee"])
            assertNull(parent["category"])
            assertNull(parent["error"])
            val children = rows.filter { it["isChild"] == true }
            assertEquals(setOf("groceries", "dining"), children.map { it["category"] }.toSet())
            // Children inherit the parent's payee and get descending sort orders.
            assertTrue(children.all { it["payee"] == "grocer" })
            assertEquals(listOf(-1.0, -2.0), children.map { it["sort"] })
            assertTrue(result.affectedAccountIds.contains("checking"))
        }
    }

    @Test
    fun editingRecalculatesTheErrorAndRemovingTheLastChildCollapsesTheFamily() {
        demo().use { database ->
            val splits = families(database)
            val created = splits.create(
                draft(SplitChildDraft(categoryId = "groceries", amount = -6_000), SplitChildDraft(categoryId = "dining", amount = -4_000)),
            )
            val parentId = created.transactionId
            val children = live(database, parentId).filter { it["isChild"] == true }
            val diningId = children.first { it["category"] == "dining" }["id"] as String
            val groceriesId = children.first { it["category"] == "groceries" }["id"] as String

            // Child amounts no longer add up: the parent carries the error cell.
            splits.update(
                parentId,
                draft(
                    SplitChildDraft(id = groceriesId, categoryId = "groceries", amount = -6_000),
                    SplitChildDraft(id = diningId, categoryId = "dining", amount = -3_000),
                    payee = null,
                ),
            )
            val afterEdit = live(database, parentId)
            assertEquals(
                "{\"type\":\"SplitTransactionError\",\"version\":1,\"difference\":-1000}",
                afterEdit.first { it["isParent"] == true }["error"],
            )

            // Dropping a child, then adding one, keeps the family consistent.
            splits.update(
                parentId,
                draft(
                    SplitChildDraft(id = groceriesId, categoryId = "groceries", amount = -7_000),
                    SplitChildDraft(categoryId = "shopping", amount = -3_000),
                    payee = null,
                ),
            )
            val afterReplace = live(database, parentId)
            assertNull(afterReplace.first { it["isParent"] == true }["error"])
            assertEquals(setOf("groceries", "shopping"), afterReplace.filter { it["isChild"] == true }.map { it["category"] }.toSet())
            assertEquals(1L, database.sql.queryLong("SELECT COUNT(*) FROM transactions WHERE id = ? AND tombstone = 1", diningId))

            // Deleting one of two children leaves a one-child family; deleting that one collapses the parent.
            val remaining = afterReplace.filter { it["isChild"] == true }.map { it["id"] as String }
            splits.delete(remaining[0])
            val oneLeft = live(database, parentId)
            assertEquals(2, oneLeft.size)
            splits.delete(remaining[1])
            val collapsed = live(database, parentId)
            assertEquals(1, collapsed.size)
            assertEquals(false, collapsed[0]["isParent"])
            assertNull(collapsed[0]["error"])
        }
    }

    @Test
    fun aSimpleRowBecomesASplitAndBackAgain() {
        demo().use { database ->
            val splits = families(database)
            database.commitLocal(
                listOf(
                    com.sporez.actualist.core.db.CellDraft("transactions", "plain-1", "acct", com.sporez.actualist.core.sync.SyncValue.text("checking")),
                    com.sporez.actualist.core.db.CellDraft("transactions", "plain-1", "date", com.sporez.actualist.core.sync.SyncValue.int(20261014)),
                    com.sporez.actualist.core.db.CellDraft("transactions", "plain-1", "amount", com.sporez.actualist.core.sync.SyncValue.int(-3_000)),
                    com.sporez.actualist.core.db.CellDraft("transactions", "plain-1", "category", com.sporez.actualist.core.sync.SyncValue.text("dining")),
                ),
            )
            splits.update(
                "plain-1",
                SplitFamilyDraft(
                    accountId = "checking", date = day, amount = -3_000, payeeId = "grocer", categoryId = "dining",
                    splits = listOf(SplitChildDraft(categoryId = "dining", amount = -1_000), SplitChildDraft(categoryId = "groceries", amount = -2_000)),
                ),
            )
            val asSplit = live(database, "plain-1")
            assertEquals(3, asSplit.size)
            assertTrue(asSplit.first { it["id"] == "plain-1" }["isParent"] == true)
            assertNull(asSplit.first { it["id"] == "plain-1" }["category"])

            // No splits: the parent turns back into a simple row and every child is removed.
            splits.update("plain-1", SplitFamilyDraft(accountId = "checking", date = day, amount = -3_000, payeeId = "grocer", categoryId = "dining"))
            val simple = live(database, "plain-1")
            assertEquals(1, simple.size)
            assertEquals(false, simple[0]["isParent"])
            assertEquals("dining", simple[0]["category"])
        }
    }

    @Test
    fun deletingAParentTombstonesTheWholeFamilyAndBadInputIsRefused() {
        demo().use { database ->
            val splits = families(database)
            val created = splits.create(
                draft(SplitChildDraft(categoryId = "groceries", amount = -6_000), SplitChildDraft(categoryId = "dining", amount = -4_000)),
            )
            assertThrows(LocalFirstException.InvalidLocalWrite::class.java) {
                splits.create(draft(SplitChildDraft(id = "dup", categoryId = "groceries", amount = -1), SplitChildDraft(id = "dup", categoryId = "dining", amount = -9_999)))
            }
            assertThrows(LocalFirstException.InvalidLocalWrite::class.java) {
                splits.create(draft(SplitChildDraft(categoryId = "no-such-category", amount = -10_000)))
            }
            splits.delete(created.transactionId)
            assertTrue(live(database, created.transactionId).isEmpty())
            assertThrows(LocalFirstException.InvalidLocalWrite::class.java) { splits.delete(created.transactionId) }
        }
    }
}
