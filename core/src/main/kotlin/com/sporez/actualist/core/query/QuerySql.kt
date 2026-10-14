package com.sporez.actualist.core.query

import com.sporez.actualist.core.budget.TransactionSplitSql
import com.sporez.actualist.core.budget.normalizedDateSql
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.Sql

/**
 * Split expressions for the query reads: [TransactionSplitSql] plus the few expressions it lacks
 * (imported payee, split-mode predicates, conditional default order). Ported from Actualist
 * `TransactionSplitQueryExpressions`.
 */
internal class QuerySplit(columns: Set<String>) {
    val base = TransactionSplitSql(columns)
    val alias: String = base.alias

    val account get() = base.account
    val date get() = base.date
    val amount get() = base.amount
    val category get() = base.category
    val payee get() = base.payee
    val notes get() = base.notes
    val parentId get() = base.parentId
    val isParent get() = base.isParent
    val isChild get() = base.isChild
    val cleared get() = base.cleared
    val reconciled get() = base.reconciled
    val schedule get() = base.schedule
    val effectiveParentId get() = base.effectiveParentId

    val importedPayee: String =
        listOf("imported_description", "imported_payee").firstOrNull { it in columns }?.let { "$alias.$it" } ?: "NULL"

    private val hasSortOrder = "sort_order" in columns
    private val hasStartingBalance = "starting_balance_flag" in columns

    val normalizedDate: String = normalizedDateSql(date)

    fun parentJoin(): String = base.parentJoin()
    fun liveEffectivePredicate(): String = base.liveEffectivePredicate()

    /** Parent category is null in the effective view. */
    fun effectiveCategory(mappedCategory: String): String = "CASE WHEN ($isParent) = 1 THEN NULL ELSE $mappedCategory END"

    fun splitModePredicate(mode: SplitMode): String = when (mode) {
        SplitMode.ALL, SplitMode.GROUPED -> "1 = 1"
        SplitMode.INLINE -> "($isParent = 0 OR $isParent IS NULL)"
        SplitMode.NONE -> "$effectiveParentId IS NULL"
    }

    fun defaultOrder(): String {
        val terms = mutableListOf("$normalizedDate DESC")
        if (hasStartingBalance) terms += "${base.startingBalance} ASC"
        if (hasSortOrder) terms += "${base.sortOrder} DESC"
        terms += "$alias.id ASC"
        return terms.joinToString(", ")
    }
}

/** Actualist `TransactionReadJoins`. */
internal class ReadJoins(
    val sql: String,
    val mappedPayee: String,
    val mappedCategory: String,
    val transferIdExpression: String,
    val isTransferExpression: String,
    val payeeNameSelect: String,
    val categoryNameSelect: String,
) {
    fun withSql(newSql: String) = ReadJoins(
        newSql, mappedPayee, mappedCategory, transferIdExpression, isTransferExpression, payeeNameSelect, categoryNameSelect,
    )
}

/** Actualist `UncategorizedReadContext`. */
internal class UncategorizedContext(val split: QuerySplit, val joins: ReadJoins, val conditions: List<String>)

/** Joins, select list and search predicates shared by the query and page reads. */
internal class QueryReads(private val database: BudgetDatabase) {
    val sql: Sql get() = database.sql

    fun readJoins(split: QuerySplit, transactionColumns: Set<String>, includeNames: Boolean): ReadJoins {
        val transferColumn = categoryMappingTransferColumn()
        val mappedCategory = transferColumn?.let { "COALESCE(cm.${Sql.quoted(it)}, ${split.category})" } ?: split.category
        val categoryMappingJoin = if (transferColumn == null) "" else "LEFT JOIN category_mapping cm ON cm.id = ${split.category}"

        val hasTransactionPayee = listOf("description", "payee").any { it in transactionColumns }
        val payeeMappingTarget: String? =
            if (hasTransactionPayee && database.tableExists("payee_mapping")) {
                val mappingColumns = database.columnSet("payee_mapping")
                listOf("targetId", "target_id").firstOrNull { it in mappingColumns }
            } else {
                null
            }
        val mappedPayee = payeeMappingTarget?.let { "COALESCE(pm.$it, ${split.payee})" } ?: split.payee
        val payeeMappingJoin = if (payeeMappingTarget == null) "" else "LEFT JOIN payee_mapping pm ON pm.id = ${split.payee}"

        // Actual's logical transfer_id maps to transferred_id in persisted budgets.
        val transferIdColumn = listOf("transferred_id", "transfer_id").firstOrNull { it in transactionColumns }
        val transferIdExpression = transferIdColumn?.let { "${split.alias}.$it" } ?: "NULL"
        val transferPredicates = mutableListOf<String>()
        if (transferIdColumn != null) {
            transferPredicates += "($transferIdExpression IS NOT NULL AND $transferIdExpression != '')"
        }

        var payeeJoin = ""
        var payeeAccountJoin = ""
        var payeeNameSelect = "NULL"
        if (database.tableExists("payees")) {
            val payeeColumns = database.columnSet("payees")
            val transferAccount = listOf("transfer_acct", "transfer_account").firstOrNull { it in payeeColumns }
            if (includeNames || (hasTransactionPayee && transferAccount != null)) {
                payeeJoin = "LEFT JOIN payees py ON py.id = $mappedPayee"
            }
            if (hasTransactionPayee && transferAccount != null) {
                transferPredicates += "(py.$transferAccount IS NOT NULL AND py.$transferAccount != '')"
            }
            if (includeNames) {
                if (transferAccount != null && database.tableExists("accounts")) {
                    payeeAccountJoin = "LEFT JOIN accounts pax ON pax.id = py.$transferAccount"
                    payeeNameSelect = "COALESCE(NULLIF(py.name, ''), pax.name)"
                } else {
                    payeeNameSelect = "py.name"
                }
            }
        }
        // Reports and backend transaction queries must classify both graph and payee transfers.
        val isTransferExpression =
            if (transferPredicates.isEmpty()) "0" else "CASE WHEN ${transferPredicates.joinToString(" OR ")} THEN 1 ELSE 0 END"

        var categoryNameJoin = ""
        var categoryNameSelect = "NULL"
        if (includeNames && database.tableExists("categories")) {
            categoryNameJoin = "LEFT JOIN categories cat ON cat.id = $mappedCategory"
            categoryNameSelect = "cat.name"
        }

        return ReadJoins(
            sql = listOf(categoryMappingJoin, payeeMappingJoin, payeeJoin, payeeAccountJoin, categoryNameJoin)
                .filter { it.isNotEmpty() }
                .joinToString("\n"),
            mappedPayee = mappedPayee,
            mappedCategory = mappedCategory,
            transferIdExpression = transferIdExpression,
            isTransferExpression = isTransferExpression,
            payeeNameSelect = payeeNameSelect,
            categoryNameSelect = categoryNameSelect,
        )
    }

    private fun categoryMappingTransferColumn(): String? {
        if (!database.tableExists("category_mapping")) return null
        val columns = database.columnSet("category_mapping")
        return listOf("transferId", "transfer_id").firstOrNull { it in columns }
    }

    fun statusFilterPredicate(filter: StatusFilter, split: QuerySplit): String? = when (filter) {
        StatusFilter.ALL, StatusFilter.UNCATEGORIZED -> null
        StatusFilter.UNCLEARED -> "IFNULL(${split.cleared}, 0) = 0 AND IFNULL(${split.reconciled}, 0) = 0"
        StatusFilter.CLEARED -> "${split.cleared} = 1 AND IFNULL(${split.reconciled}, 0) = 0"
        StatusFilter.RECONCILED -> "IFNULL(${split.reconciled}, 0) = 1"
    }

    fun uncategorizedContext(): UncategorizedContext {
        val columns = database.columnSet("transactions")
        val split = QuerySplit(columns)
        val joins = readJoins(split, columns, includeNames = true)

        var extraJoin = ""
        val conditions = mutableListOf(
            split.liveEffectivePredicate(),
            "(${joins.mappedCategory} IS NULL OR ${joins.mappedCategory} = '')",
        )
        if (database.tableExists("accounts")) {
            extraJoin = "LEFT JOIN accounts src ON src.id = ${split.account}"
            conditions += "IFNULL(src.offbudget, 0) = 0"
        }
        if (database.tableExists("payees")) {
            val payeeColumns = database.columnSet("payees")
            if ("transfer_acct" in payeeColumns && database.tableExists("accounts")) {
                conditions += "(py.transfer_acct IS NULL OR py.transfer_acct = '' OR IFNULL(pax.offbudget, 0) = 1)"
            }
        }
        return UncategorizedContext(
            split,
            joins.withSql(listOf(joins.sql, extraJoin).filter { it.isNotEmpty() }.joinToString("\n")),
            conditions,
        )
    }

    fun selectList(split: QuerySplit, joins: ReadJoins): String =
        """
        t.id AS id,
        ${split.account} AS account_id,
        ${split.normalizedDate} AS date,
        ${split.amount} AS amount,
        ${joins.mappedPayee} AS payee_id,
        ${joins.payeeNameSelect} AS payee_name,
        ${split.importedPayee} AS imported_payee,
        ${split.effectiveCategory(joins.mappedCategory)} AS category_id,
        ${split.notes} AS notes,
        ${split.cleared} AS cleared,
        ${split.reconciled} AS reconciled,
        ${split.effectiveParentId} AS parent_id,
        ${split.isParent} AS is_parent,
        ${split.isChild} AS is_child,
        ${split.schedule} AS schedule
        """.trimIndent()

    fun searchPredicate(split: QuerySplit, joins: ReadJoins): String =
        """
        (${joins.payeeNameSelect} LIKE ? ESCAPE '\'
         OR ${split.notes} LIKE ? ESCAPE '\'
         OR ${joins.categoryNameSelect} LIKE ? ESCAPE '\'
         OR CAST(${split.amount} AS TEXT) LIKE ? ESCAPE '\')
        """.trimIndent()

    fun escapeLikePattern(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    fun mapRow(row: com.sporez.actualist.core.db.SqlRow): QueryTransaction {
        val parentId = row.string("parent_id")?.takeIf { it.isNotEmpty() }
        val payeeName = row.string("payee_name")?.takeIf { it.isNotEmpty() }
        val isParent = row.bool("is_parent")
        val isChild = row.bool("is_child")
        return QueryTransaction(
            id = row.string("id"),
            accountId = row.string("account_id") ?: "",
            date = row.string("date") ?: "",
            amount = row.long("amount"),
            payeeId = row.string("payee_id"),
            payeeName = payeeName,
            importedPayee = row.string("imported_payee"),
            categoryId = if (isParent) null else row.string("category_id"),
            notes = row.string("notes"),
            cleared = row.bool("cleared"),
            reconciled = row.bool("reconciled"),
            isParent = isParent,
            isChild = isChild,
            parentId = if (isChild) parentId else null,
            scheduleId = row.string("schedule"),
        )
    }

    /** Children nest under their parent; every other row is a root. Row order is kept. */
    fun assemble(rows: List<com.sporez.actualist.core.db.SqlRow>): List<QueryTransaction> {
        val childrenByParent = LinkedHashMap<String, MutableList<QueryTransaction>>()
        val parents = mutableListOf<QueryTransaction>()
        for (row in rows) {
            val transaction = mapRow(row)
            val parentId = transaction.parentId
            if (transaction.isChild && parentId != null && parentId.isNotEmpty()) {
                childrenByParent.getOrPut(parentId) { mutableListOf() }.add(transaction)
            } else {
                parents += transaction
            }
        }
        return parents.map { parent ->
            val children = parent.id?.let { childrenByParent[it] }
            if (children.isNullOrEmpty()) parent else parent.copy(subtransactions = children.toList())
        }
    }

    /** Whole families (parents with live children) for the given group ids, in default order. */
    fun assembledForGroups(groupIds: List<String>, split: QuerySplit, joins: ReadJoins): List<QueryTransaction> {
        if (groupIds.isEmpty()) return emptyList()
        val placeholders = groupIds.joinToString(", ") { "?" }
        val rows = sql.query(
            """
            SELECT ${selectList(split, joins)}
            FROM transactions t
            ${joins.sql}
            ${split.parentJoin()}
            WHERE ${split.liveEffectivePredicate()}
              AND IFNULL(${split.effectiveParentId}, t.id) IN ($placeholders)
            ORDER BY ${split.defaultOrder()}
            """.trimIndent(),
            *groupIds.toTypedArray(),
        )
        return assemble(rows)
    }

    /**
     * Nested children on parent rows. Does not add or remove page rows, so `all` search match sets
     * and pagination stay intact.
     */
    fun attachingSplitFamilies(transactions: List<QueryTransaction>, split: QuerySplit, joins: ReadJoins): List<QueryTransaction> {
        val parentIds = transactions.filter { it.isParent }.mapNotNull { it.id }
        if (parentIds.isEmpty()) return transactions
        val childrenByParent = HashMap<String, List<QueryTransaction>>()
        for (parent in assembledForGroups(parentIds, split, joins)) {
            val id = parent.id ?: continue
            childrenByParent[id] = parent.subtransactions
        }
        return transactions.map { transaction ->
            val children = transaction.id?.let { childrenByParent[it] }
            if (children == null) transaction else transaction.copy(subtransactions = children)
        }
    }

    /** Actualist `TransactionGroupedOrdering`: assembled roots in the order of the page's group ids. */
    fun orderedByGroups(assembled: List<QueryTransaction>, groupIds: List<String>): List<QueryTransaction> {
        val byId = HashMap<String, QueryTransaction>(assembled.size * 2)
        for (transaction in assembled) {
            val id = transaction.id ?: continue
            byId[id] = transaction
        }
        return groupIds.mapNotNull { byId[it] }
    }

    fun limitSql(limit: Int?, offset: Int, arguments: MutableList<Any?>): String {
        if (limit == null) return ""
        arguments.add(limit + 1)
        arguments.add(offset)
        return "LIMIT ? OFFSET ?"
    }
}
