package com.sporez.actualist.core.budget

/**
 * Actual's split-transaction view semantics over the physical `transactions` table,
 * ported from Actualist `TransactionSplitQueryExpressions` (`v_transactions_internal`
 * and `v_transactions_internal_alive`). Callers compose these into their own SQL.
 */
class TransactionSplitSql(private val columns: Set<String>, val alias: String = "t") {
    private fun physical(names: List<String>, fallback: String) = names.firstOrNull { it in columns } ?: fallback
    private fun qualify(expression: String) =
        if (expression == "NULL" || expression == "0" || expression.contains(".") || expression.contains("(")) {
            expression
        } else {
            "$alias.$expression"
        }

    private val hasParentId = "parent_id" in columns
    private val tombstone: String? = when {
        "tombstone" in columns -> "tombstone"
        "deleted" in columns -> "deleted"
        else -> null
    }

    val account = qualify(physical(listOf("acct", "account"), "NULL"))
    val date = qualify(physical(listOf("date"), "NULL"))
    val amount = qualify(physical(listOf("amount"), "0"))
    val category = qualify(physical(listOf("category"), "NULL"))
    val payee = qualify(physical(listOf("description", "payee"), "NULL"))
    val notes = qualify(physical(listOf("notes"), "NULL"))
    val parentId = qualify(physical(listOf("parent_id"), "NULL"))
    val sortOrder = qualify(physical(listOf("sort_order"), "0"))
    val startingBalance = qualify(physical(listOf("starting_balance_flag"), "0"))
    val cleared = qualify(physical(listOf("cleared"), "0"))
    val reconciled = qualify(physical(listOf("reconciled"), "0"))
    val transferredId = qualify(physical(listOf("transferred_id"), "NULL"))
    val schedule = qualify(physical(listOf("schedule"), "NULL"))

    val isChild: String = run {
        val name = listOf("isChild", "is_child").firstOrNull { it in columns }
        when {
            name != null && hasParentId ->
                // NULL isChild is unmigrated, not an explicit 0. Explicit 0 still wins over parent_id.
                "CASE WHEN $alias.$name IS NULL THEN CASE WHEN $alias.parent_id IS NULL OR $alias.parent_id = '' THEN 0 ELSE 1 END " +
                    "WHEN $alias.$name = 0 THEN 0 ELSE 1 END"
            name != null -> "IFNULL($alias.$name, 0)"
            hasParentId -> "CASE WHEN $alias.parent_id IS NULL OR $alias.parent_id = '' THEN 0 ELSE 1 END"
            else -> "0"
        }
    }

    val isParent: String = listOf("isParent", "is_parent").firstOrNull { it in columns }
        ?.let { "IFNULL($alias.$it, 0)" } ?: "0"

    /** `v_transactions_internal` parent_id: flags are authoritative. */
    val effectiveParentId get() = "CASE WHEN ($isChild) = 0 THEN NULL ELSE $parentId END"

    private val internalViewPredicate get() =
        "$date IS NOT NULL AND $account IS NOT NULL AND (($isChild) = 0 OR $parentId IS NOT NULL)"

    private val liveRowPredicate get() =
        tombstone?.let { "($alias.$it IS NULL OR $alias.$it = 0)" } ?: "1 = 1"

    fun parentJoin(parentAlias: String = "parent") =
        "LEFT JOIN transactions $parentAlias ON (($isChild) = 1 AND $parentAlias.id = $parentId)"

    /** A child is alive only while its parent row exists and is not deleted. */
    private fun alivePredicate(parentAlias: String) =
        tombstone?.let { "(($isChild) = 0 OR $parentAlias.$it = 0)" } ?: "1 = 1"

    fun liveEffectivePredicate(parentAlias: String = "parent") =
        "$liveRowPredicate AND $internalViewPredicate AND ${alivePredicate(parentAlias)}"

    /** Alive non-parent rows: account balances, budget spending, reports. */
    fun liveInlinePredicate(parentAlias: String = "parent") =
        "${liveEffectivePredicate(parentAlias)} AND ($isParent = 0 OR $isParent IS NULL)"

    /** Alive top-level rows (simple transactions and split parents): transaction lists. */
    fun liveTopLevelPredicate(parentAlias: String = "parent") =
        "${liveEffectivePredicate(parentAlias)} AND $effectiveParentId IS NULL"

    fun defaultOrder(): String = "${normalizedDateSql(date)} DESC, $startingBalance ASC, $sortOrder DESC, $alias.id ASC"
}
