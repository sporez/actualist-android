package com.sporez.actualist.core.widgets

import com.sporez.actualist.core.budget.BudgetMonthCategory
import com.sporez.actualist.core.transactions.Account
import org.junit.Test
import org.junit.Assert.assertEquals

class WidgetShapingTest {
    private fun category(id: String, balance: Long, income: Boolean = false, hidden: Boolean = false) =
        BudgetMonthCategory(id, id, income, hidden, "g", 0, 0, balance, false)

    private fun account(id: String, order: Double, off: Boolean = false, closed: Boolean = false) =
        Account(id, id, off, closed, 100, 100, order)

    @Test
    fun rolloverCategoriesCountOnlyWhenIncluded() {
        val rolling = BudgetMonthCategory("r", "r", false, false, "g", 0, 0, -20, true)
        val plain = category("p", -5)
        assertEquals(listOf("p"), WidgetShaping.overspent(listOf(rolling, plain)).map { it.id })
        assertEquals(listOf("r", "p"), WidgetShaping.overspent(listOf(rolling, plain), includeRollover = true).map { it.id })
    }

    @Test
    fun overspentSkipsIncomeHiddenAndPositive() {
        val result = WidgetShaping.overspent(
            listOf(category("a", -5), category("b", 10), category("c", -50), category("d", -9, income = true), category("e", -9, hidden = true)),
        )
        assertEquals(listOf("c", "a"), result.map { it.id })
        assertEquals(2, AttentionSummary(result, 0).issueCount)
    }

    @Test
    fun accountsAreOnBudgetOpenSortedAndLimited() {
        val result = WidgetShaping.onBudgetAccounts(
            listOf(account("x", 3.0), account("y", 1.0), account("z", 2.0, off = true), account("w", 0.5, closed = true), account("v", 2.0)), 2,
        )
        assertEquals(listOf("y", "v"), result.map { it.id })
    }
}
