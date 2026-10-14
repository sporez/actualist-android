package com.sporez.actualist.core.rules

import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.db.CellDraft
import com.sporez.actualist.core.demo.DemoBudget
import com.sporez.actualist.core.sync.SyncValue
import com.sporez.actualist.core.transactions.TransactionDraft
import com.sporez.actualist.core.transactions.TransactionEdit
import com.sporez.actualist.core.transactions.TransactionWrites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CategoryLearningTest {
    private val day = LocalDate.of(2026, 10, 15)

    @Test
    fun probableCategoryFollowsActual() {
        assertEquals("a", CategoryLearning.probableCategory(listOf("a", "b", "a", "a", "b")))
        assertEquals("b", CategoryLearning.probableCategory(listOf("a", "b", "b", "b", "a")))
        // Ties keep the newest; fewer than three never wins.
        assertNull(CategoryLearning.probableCategory(listOf("a", "a", "b", "b", "c")))
        // An uncategorized newest row: Actual's comparison never displaces it.
        assertNull(CategoryLearning.probableCategory(listOf(null, "a", "a", "a", "a")))
    }

    @Test
    fun threeOfTheLatestFiveCreateThenRetargetARule() {
        val clock = TestClock()
        DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, day, clock).use { db ->
            var n = 0
            val writes = TransactionWrites(db, clock) { "learn-${++n}" }
            val ids = (1..3).map { i ->
                writes.create(TransactionDraft(accountId = "checking", date = day.minusDays(i.toLong()), amount = -1_000, payeeName = "Synthetic Bakery", categoryId = "groceries")).transactionId
            }
            val payee = db.sql.queryString("SELECT description FROM transactions WHERE id = ?", ids[0])!!
            val learning = CategoryLearning(db) { "learned-rule" }
            learning.learn(listOf(ids[0]), day)
            val rule = RuleEngine(db).fetchRules().single { it.id == "learned-rule" }.draft!!
            assertEquals(listOf(RuleCondition("payee", "is", RuleValue.Text(payee), "id")), rule.conditions)
            assertEquals(RuleValue.Text("groceries"), rule.actions.single().value)

            // Three newer rows in another category: the same rule is pointed at it.
            val newer = (0..2).map { i ->
                writes.create(TransactionDraft(accountId = "checking", date = day.plusDays(i.toLong()), amount = -1_000, payeeId = payee, categoryId = "dining")).transactionId
            }
            learning.learn(newer, day)
            assertEquals(RuleValue.Text("dining"), RuleEngine(db).fetchRules().single { it.id == "learned-rule" }.draft!!.actions.single().value)

            // Learning off for the payee, or for the budget: nothing changes.
            db.commitLocal(listOf(CellDraft("payees", payee, "learn_categories", SyncValue.bool(false))))
            writes.update(newer[0], TransactionEdit(categoryId = "groceries"))
            assertTrue(learning.drafts(newer, day).isEmpty())
            assertTrue(payee in learning.payeesNotLearning())
            learning.setPayeeLearns(payee, true)
            learning.setEnabled(false)
            assertTrue(!learning.enabled())
            assertTrue(learning.drafts(newer, day).isEmpty())
            learning.setEnabled(true)
            assertTrue(learning.drafts(newer, day).isNotEmpty())
        }
    }
}
