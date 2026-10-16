package com.sporez.actualist.core.reports

/**
 * Edits filters so every row shows whether the report counts it (Actualist `ReportExplorerFilterDraft`).
 * Off-budget accounts and hidden categories only count while their include switch is on, so a row in
 * that gated group reads off while the switch is off, and turning one on opens the switch for that row alone.
 */
data class SeriesFilterDraft(
    val filters: SeriesFilters,
    val defaults: SeriesFilters,
    val accounts: List<SeriesAccountOption>,
    val categories: List<SeriesCategoryOption>,
) {
    private val accountIds = accounts.map { it.id }.toSet()
    private val offBudgetAccountIds = accounts.filter { it.isOffBudget }.map { it.id }.toSet()
    private val categoryIds = categories.map { it.id }.toSet()
    private val hiddenCategoryIds = categories.filter { it.isHidden }.map { it.id }.toSet()

    val includesTransfers: Boolean get() = filters.transfers != ReportTransferFilter.NON_TRANSFERS

    fun isAccountIncluded(id: String): Boolean =
        (filters.includeOffBudget || id !in offBudgetAccountIds) && (filters.accounts?.contains(id) ?: true)

    fun isCategoryIncluded(id: String): Boolean =
        (filters.includeHiddenCategories || id !in hiddenCategoryIds) && (filters.categories?.contains(id) ?: true)

    fun withAccount(id: String, included: Boolean): SeriesFilterDraft {
        val (selection, open) = setRow(id, included, filters.accounts, filters.includeOffBudget, accountIds, offBudgetAccountIds)
        return copy(filters = filters.copy(accounts = selection, includeOffBudget = open))
    }

    fun withCategory(id: String, included: Boolean): SeriesFilterDraft {
        val (selection, open) = setRow(id, included, filters.categories, filters.includeHiddenCategories, categoryIds, hiddenCategoryIds)
        return copy(filters = filters.copy(categories = selection, includeHiddenCategories = open))
    }

    fun withIncludesOffBudget(included: Boolean): SeriesFilterDraft =
        copy(filters = filters.copy(accounts = setGate(included, filters.accounts, accountIds, offBudgetAccountIds), includeOffBudget = included))

    fun withIncludesHiddenCategories(included: Boolean): SeriesFilterDraft =
        copy(filters = filters.copy(categories = setGate(included, filters.categories, categoryIds, hiddenCategoryIds), includeHiddenCategories = included))

    fun selectAllAccounts(): SeriesFilterDraft = copy(filters = filters.copy(accounts = null, includeOffBudget = true))

    fun clearAccounts(): SeriesFilterDraft = copy(filters = filters.copy(accounts = emptySet(), includeOffBudget = false))

    fun selectAllCategories(): SeriesFilterDraft =
        copy(filters = filters.copy(categories = null, includeHiddenCategories = true, includeUncategorized = true))

    fun clearCategories(): SeriesFilterDraft =
        copy(filters = filters.copy(categories = emptySet(), includeHiddenCategories = false, includeUncategorized = false))

    fun withIncludesTransfers(included: Boolean): SeriesFilterDraft =
        copy(filters = filters.copy(transfers = if (included) ReportTransferFilter.ALL else ReportTransferFilter.NON_TRANSFERS))

    fun withIncludesUncategorized(included: Boolean): SeriesFilterDraft = copy(filters = filters.copy(includeUncategorized = included))

    fun reset(): SeriesFilterDraft = copy(filters = defaults)

    private fun normalized(ids: Set<String>, available: Set<String>): Set<String>? = if (ids == available) null else ids

    /** Opening a closed gate from one row adds only that row: the other gated rows read off while the gate is closed, so they stay off. */
    private fun setRow(
        id: String,
        included: Boolean,
        selection: Set<String>?,
        gateIsOpen: Boolean,
        available: Set<String>,
        gated: Set<String>,
    ): Pair<Set<String>?, Boolean> {
        val ids = (selection ?: available).toMutableSet()
        var open = gateIsOpen
        if (included) {
            if (id in gated && !open) {
                ids.removeAll(gated)
                open = true
            }
            ids.add(id)
        } else {
            ids.remove(id)
        }
        return normalized(ids, available) to open
    }

    /** Opening the gate turns every gated row on. Closing it leaves the selection alone because the gate already excludes those rows. */
    private fun setGate(open: Boolean, selection: Set<String>?, available: Set<String>, gated: Set<String>): Set<String>? {
        if (!open) return selection
        return normalized((selection ?: available) + gated, available)
    }
}
