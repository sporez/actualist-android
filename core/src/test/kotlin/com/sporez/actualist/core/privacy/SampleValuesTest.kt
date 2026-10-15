package com.sporez.actualist.core.privacy

import com.sporez.actualist.core.budget.BudgetMonth
import com.sporez.actualist.core.budget.BudgetMonthCategory
import com.sporez.actualist.core.budget.BudgetMonthCategoryGroup
import com.sporez.actualist.core.budget.BudgetTable
import com.sporez.actualist.core.transactions.Account
import com.sporez.actualist.core.transactions.TransactionRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleValuesTest {
    private fun category(id: String, income: Boolean = false) =
        BudgetMonthCategory(id, "Real $id", income, false, if (income) "inc" else "g", 12_345, -6_789, 5_556, false, hasNote = true)

    private fun month(): BudgetMonth {
        val spending = listOf(category("c1"), category("c2"))
        val income = listOf(category("i1", income = true))
        return BudgetMonth(
            "2026-03", BudgetTable.ENVELOPE, 1, 0, 0, 24_690, 1, 0, 0, 0, 0,
            listOf(
                BudgetMonthCategoryGroup("g", "Real group", false, false, 0, 0, 0, spending),
                BudgetMonthCategoryGroup("inc", "Income", true, false, 0, 0, 0, income),
            ),
            null,
        )
    }

    @Test
    fun hashMatchesFnv1aVectors() {
        assertEquals(14_695_981_039_346_656_037UL, SampleValues.stableHash(""))
        assertEquals(12_638_187_200_555_641_996UL, SampleValues.stableHash("a"))
    }

    @Test
    fun amountIsDeterministicSignedAndBounded() {
        val a = SampleValues.amount(-500, "seed", 4, 900)
        assertEquals(a, SampleValues.amount(-9, "seed", 4, 900))
        assertTrue(a < 0)
        assertTrue(-a in 400L..90_099L)
        assertTrue(SampleValues.amount(5, "seed", 4, 900) > 0)
    }

    @Test
    fun namesAreStableAndNotTheRealOnes() {
        assertEquals(SampleValues.name(SampleNameKind.PAYEE, "p1"), SampleValues.name(SampleNameKind.PAYEE, "p1"))
        assertTrue(SampleValues.name(SampleNameKind.BUDGET, "b").startsWith("Sample Budget "))
        assertTrue(SampleValues.name(SampleNameKind.ACCOUNT, "a1").matches(Regex(".+ \\d\\d")))
    }

    @Test
    fun projectedMonthTotalsAddUp() {
        val real = month()
        val shown = SampleValues.project(real)
        assertEquals(shown, SampleValues.project(real))
        for (group in shown.categoryGroups) {
            assertEquals(group.categories.sumOf { it.budgeted }, group.budgeted)
            assertEquals(group.categories.sumOf { it.spent }, group.spent)
            assertEquals(group.categories.sumOf { it.balance }, group.balance)
        }
        val expenses = shown.categoryGroups.filter { !it.isIncome }
        assertEquals(expenses.sumOf { it.budgeted }, shown.totalBudgeted)
        assertEquals(expenses.sumOf { it.balance }, shown.totalBalance)
        assertEquals(shown.categoryGroups.first { it.isIncome }.spent, shown.totalIncome)
        assertEquals(shown.toBudget, shown.incomeAvailable)
        assertNotEquals(real.categories.first().budgeted, shown.categories.first().budgeted)
        assertTrue(shown.categoryGroups.first { it.isIncome }.categories.single().budgeted == 0L)
    }

    @Test
    fun accountsAndTransactionsAreProjected() {
        val account = Account("a1", "My Bank", false, false, 123_456, 100_000, 1.0)
        val shown = SampleValues.account(account)
        assertNotEquals("My Bank", shown.name)
        assertNotEquals(123_456L, shown.balance)
        val row = TransactionRow(
            "t1", "a1", "My Bank", null, -4_321, "p1", "Corner Shop", "c1", "Dining", "private note",
            false, false, false, false, null, null, null, false,
        )
        val sample = SampleValues.transaction(row)
        assertNull(sample.notes)
        assertNotEquals("Corner Shop", sample.payeeName)
        assertTrue(sample.amount < 0)
        assertEquals(sample, SampleValues.transaction(row))
    }
}
