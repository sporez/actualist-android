package com.sporez.actualist.core.reports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportSeriesFilterDraftTest {
    private val accounts = listOf(
        SeriesAccountOption("checking", "Checking", isOffBudget = false, isClosed = false),
        SeriesAccountOption("savings", "Savings", isOffBudget = false, isClosed = false),
        SeriesAccountOption("ira", "IRA", isOffBudget = true, isClosed = false),
        SeriesAccountOption("401k", "401k", isOffBudget = true, isClosed = false),
        SeriesAccountOption("old-brokerage", "Old", isOffBudget = true, isClosed = true),
    )
    private val categories = listOf(
        SeriesCategoryOption("rent", "Rent", "Needs", isIncome = false, isHidden = false),
        SeriesCategoryOption("food", "Food", "Needs", isIncome = false, isHidden = false),
        SeriesCategoryOption("legacy", "Legacy", "Needs", isIncome = false, isHidden = true),
    )
    private val spendingDefaults = SeriesFilters.defaults(ReportMetric.SPENDING)

    private fun draft(filters: SeriesFilters = spendingDefaults, defaults: SeriesFilters = spendingDefaults) =
        SeriesFilterDraft(filters, defaults, accounts, categories)

    @Test
    fun distinguishesAllSomeAndExplicitlyEmptySelections() {
        var draft = draft().withAccount("checking", included = false)
        assertEquals(setOf("savings", "ira", "401k", "old-brokerage"), draft.filters.accounts)
        draft = draft.withAccount("checking", included = true)
        assertEquals(null, draft.filters.accounts)

        draft = draft.clearAccounts().clearCategories()
        assertEquals(emptySet<String>(), draft.filters.accounts)
        assertEquals(emptySet<String>(), draft.filters.categories)
        assertFalse(draft.filters.includeUncategorized)
    }

    @Test
    fun offBudgetRowsReadOffWhileTheirSwitchIsOff() {
        val draft = draft()
        assertFalse(draft.filters.includeOffBudget)
        assertEquals(null, draft.filters.accounts)
        assertTrue(draft.isAccountIncluded("checking"))
        assertFalse(draft.isAccountIncluded("ira"))
        assertFalse(draft.isAccountIncluded("old-brokerage"))
    }

    @Test
    fun turningOnOneOffBudgetRowIncludesOnlyThatAccount() {
        val draft = draft().withAccount("ira", included = true)
        assertTrue(draft.filters.includeOffBudget)
        assertEquals(setOf("checking", "savings", "ira"), draft.filters.accounts)
        assertTrue(draft.isAccountIncluded("ira"))
        assertFalse(draft.isAccountIncluded("401k"))
        assertFalse(draft.isAccountIncluded("old-brokerage"))
        assertTrue(draft.isAccountIncluded("checking"))
    }

    @Test
    fun offBudgetSwitchTurnsEveryOffBudgetRowOnAndOff() {
        var draft = draft(SeriesFilters(accounts = setOf("checking"), includeOffBudget = false))

        draft = draft.withIncludesOffBudget(true)
        assertEquals(setOf("checking", "ira", "401k", "old-brokerage"), draft.filters.accounts)
        assertTrue(draft.isAccountIncluded("ira"))
        assertFalse(draft.isAccountIncluded("savings"))

        draft = draft.withIncludesOffBudget(false)
        assertFalse(draft.isAccountIncluded("ira"))
        assertTrue(draft.isAccountIncluded("checking"))
    }

    @Test
    fun closingTheDefaultOffBudgetSwitchKeepsTheDefaultSelection() {
        var draft = draft().withIncludesOffBudget(true)
        assertEquals(null, draft.filters.accounts)
        draft = draft.withIncludesOffBudget(false)
        assertEquals(spendingDefaults, draft.filters)
    }

    @Test
    fun allAndNoneMatchEveryAccountRow() {
        var draft = draft().selectAllAccounts()
        assertTrue(accounts.all { draft.isAccountIncluded(it.id) })

        draft = draft.clearAccounts()
        assertTrue(accounts.none { draft.isAccountIncluded(it.id) })
        assertFalse(draft.filters.includeOffBudget)
    }

    @Test
    fun hiddenCategoryRowsFollowTheirSwitch() {
        var draft = draft()
        assertTrue(draft.isCategoryIncluded("legacy"))

        draft = draft.withIncludesHiddenCategories(false)
        assertFalse(draft.isCategoryIncluded("legacy"))
        assertTrue(draft.isCategoryIncluded("rent"))

        draft = draft.withCategory("legacy", included = true)
        assertTrue(draft.filters.includeHiddenCategories)
        assertTrue(draft.isCategoryIncluded("legacy"))
        assertEquals(null, draft.filters.categories)
    }

    @Test
    fun allAndNoneMatchEveryCategoryRow() {
        var draft = draft().clearCategories()
        assertTrue(categories.none { draft.isCategoryIncluded(it.id) })
        assertFalse(draft.filters.includeHiddenCategories)
        assertFalse(draft.filters.includeUncategorized)

        draft = draft.selectAllCategories()
        assertTrue(categories.all { draft.isCategoryIncluded(it.id) })
        assertTrue(draft.filters.includeUncategorized)
    }

    @Test
    fun transferSwitchIncludesAllTransfersOrNone() {
        val calendarDefaults = spendingDefaults.copy(transfers = ReportTransferFilter.TRANSFERS)
        var draft = draft(calendarDefaults, calendarDefaults)
        assertTrue(draft.includesTransfers)

        draft = draft.withIncludesTransfers(false)
        assertEquals(ReportTransferFilter.NON_TRANSFERS, draft.filters.transfers)
        draft = draft.withIncludesTransfers(true)
        assertEquals(ReportTransferFilter.ALL, draft.filters.transfers)

        draft = draft.reset()
        assertEquals(ReportTransferFilter.TRANSFERS, draft.filters.transfers)
    }

    @Test
    fun resetRestoresDefaultFilters() {
        val draft = draft().selectAllAccounts().clearCategories().reset()
        assertEquals(spendingDefaults, draft.filters)
    }

    @Test
    fun netWorthStartsWithEveryAccountIncludedAndResetsBackToIt() {
        val netWorthDefaults = SeriesFilters.defaults(ReportMetric.NET_WORTH)
        var draft = draft(netWorthDefaults, netWorthDefaults)
        assertTrue(accounts.all { draft.isAccountIncluded(it.id) })

        draft = draft.withIncludesOffBudget(false)
        assertFalse(draft.isAccountIncluded("ira"))

        draft = draft.reset()
        assertEquals(netWorthDefaults, draft.filters)
        assertTrue(draft.isAccountIncluded("ira"))
    }
}
