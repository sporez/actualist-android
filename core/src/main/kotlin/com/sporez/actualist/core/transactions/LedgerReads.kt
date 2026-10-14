package com.sporez.actualist.core.transactions

import com.sporez.actualist.core.budget.Months
import com.sporez.actualist.core.budget.TransactionSplitSql
import com.sporez.actualist.core.budget.normalizedMonthSql
import com.sporez.actualist.core.db.BudgetDatabase
import java.time.LocalDate

data class Account(
    val id: String,
    val name: String,
    val offBudget: Boolean,
    val closed: Boolean,
    val balance: Long,
    val clearedBalance: Long,
    val sortOrder: Double,
)

data class Payee(val id: String, val name: String, val transferAccountId: String?) {
    val isTransfer: Boolean get() = transferAccountId != null
}

data class CategoryOption(val id: String, val name: String, val groupName: String, val isIncome: Boolean, val hidden: Boolean)

data class TransactionRow(
    val id: String,
    val accountId: String,
    val accountName: String,
    val date: LocalDate?,
    val amount: Long,
    val payeeId: String?,
    val payeeName: String?,
    val categoryId: String?,
    val categoryName: String?,
    val notes: String?,
    val cleared: Boolean,
    val reconciled: Boolean,
    val isParent: Boolean,
    val isChild: Boolean,
    val parentId: String?,
    val transferId: String?,
    val transferAccountName: String?,
    val startingBalance: Boolean,
    val children: List<TransactionRow> = emptyList(),
) {
    val isTransfer: Boolean get() = transferId != null
    /** What a list shows as the counterparty: the transfer account, payee, or nothing. */
    val displayPayee: String get() = when {
        transferAccountName != null -> (if (amount < 0) "Transfer to " else "Transfer from ") + transferAccountName
        !payeeName.isNullOrBlank() -> payeeName
        else -> ""
    }
}

data class TransactionQuery(
    val accountId: String? = null,
    val search: String? = null,
    val uncategorizedOnly: Boolean = false,
    val month: String? = null,
    val categoryId: String? = null,
    val limit: Int = 500,
)

/**
 * Account, payee, category and transaction reads for the screens. Balances follow
 * Actualist's `accountBalances`: alive non-parent rows (split children count, their
 * parents do not).
 */
class LedgerReads(private val database: BudgetDatabase) {
    private val sql get() = database.sql

    fun accounts(includeClosed: Boolean = true): List<Account> = sql.locked {
        val split = TransactionSplitSql(database.columnSet("transactions"))
        val balances = HashMap<String, Pair<Long, Long>>()
        for (row in sql.query(
            """
            SELECT ${split.account} AS account_id, SUM(${split.amount}) AS balance,
                   SUM(CASE WHEN ${split.cleared} = 1 THEN ${split.amount} ELSE 0 END) AS cleared_balance
            FROM transactions t ${split.parentJoin()}
            WHERE ${split.liveInlinePredicate()}
            GROUP BY ${split.account}
            """.trimIndent(),
        )) {
            val id = row.string("account_id") ?: continue
            balances[id] = row.longOr0("balance") to row.longOr0("cleared_balance")
        }
        val columns = database.columnSet("accounts")
        sql.query(
            "SELECT id, name, offbudget, closed, sort_order FROM accounts WHERE ${BudgetDatabase.livePredicate(columns)} " +
                "ORDER BY sort_order, lower(name)",
        ).mapNotNull { row ->
            val id = row.string("id") ?: return@mapNotNull null
            val closed = row.bool("closed")
            if (closed && !includeClosed) return@mapNotNull null
            Account(
                id = id,
                name = row.string("name") ?: "",
                offBudget = row.bool("offbudget"),
                closed = closed,
                balance = balances[id]?.first ?: 0,
                clearedBalance = balances[id]?.second ?: 0,
                sortOrder = row.double("sort_order") ?: 0.0,
            )
        }
    }

    fun payees(): List<Payee> {
        val columns = database.columnSet("payees")
        val accounts = accounts().associateBy { it.id }
        return sql.query("SELECT id, name, transfer_acct FROM payees WHERE ${BudgetDatabase.livePredicate(columns)}")
            .mapNotNull { row ->
                val id = row.string("id") ?: return@mapNotNull null
                val transfer = row.string("transfer_acct")?.takeIf { it.isNotEmpty() }
                if (transfer != null) {
                    val account = accounts[transfer] ?: return@mapNotNull null
                    if (account.closed) return@mapNotNull null
                    Payee(id, account.name, transfer)
                } else {
                    Payee(id, row.string("name") ?: "", null)
                }
            }
            .filter { it.name.isNotBlank() }
            .sortedWith(compareBy<Payee> { it.isTransfer }.thenBy { it.name.lowercase() })
    }

    fun categories(includeHidden: Boolean = false): List<CategoryOption> {
        val columns = database.columnSet("categories")
        return sql.query(
            """
            SELECT c.id AS id, c.name AS name, c.is_income AS is_income, c.hidden AS hidden, g.name AS group_name, g.hidden AS group_hidden
            FROM categories c LEFT JOIN category_groups g ON g.id = c.cat_group
            WHERE ${BudgetDatabase.livePredicate(columns, "c")} AND (g.tombstone IS NULL OR g.tombstone = 0)
            ORDER BY c.is_income, g.sort_order, lower(g.name), c.sort_order, lower(c.name)
            """.trimIndent(),
        ).mapNotNull { row ->
            val hidden = row.bool("hidden") || row.bool("group_hidden")
            if (hidden && !includeHidden) return@mapNotNull null
            CategoryOption(row.string("id") ?: return@mapNotNull null, row.string("name") ?: "", row.string("group_name") ?: "", row.bool("is_income"), hidden)
        }
    }

    /**
     * Top-level rows (simple transactions and split parents) newest first, with split
     * children attached to their parent. Search matches payee, notes, category or amount text.
     */
    fun transactions(query: TransactionQuery = TransactionQuery()): List<TransactionRow> = sql.locked {
        val split = TransactionSplitSql(database.columnSet("transactions"))
        val predicates = mutableListOf(split.liveTopLevelPredicate())
        val args = mutableListOf<Any?>()
        query.accountId?.let {
            predicates += "${split.account} = ?"
            args += it
        }
        query.month?.let {
            predicates += "${normalizedMonthSql(split.date)} = ?"
            args += it
        }
        if (query.uncategorizedOnly) {
            predicates += "(${split.category} IS NULL OR ${split.category} = '') AND ${split.isParent} = 0 AND ${split.transferredId} IS NULL AND a.offbudget = 0"
        }
        query.categoryId?.let {
            predicates += "(${split.category} = ? OR EXISTS (SELECT 1 FROM transactions c WHERE c.parent_id = t.id AND c.category = ? AND (c.tombstone IS NULL OR c.tombstone = 0)))"
            args += it
            args += it
        }
        query.search?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
            predicates += "(lower(COALESCE(p.name, '')) LIKE ? OR lower(COALESCE(ta.name, '')) LIKE ? OR lower(COALESCE(${split.notes}, '')) LIKE ? " +
                "OR lower(COALESCE(cat.name, '')) LIKE ? OR CAST(abs(${split.amount}) AS TEXT) LIKE ?)"
            val like = "%" + text.lowercase() + "%"
            args += listOf(like, like, like, like, "%" + text.filter { it.isDigit() }.ifEmpty { "\u0000" } + "%")
        }
        args += query.limit
        val rows = sql.query(
            """
            SELECT t.id AS id, ${split.account} AS account_id, a.name AS account_name, ${split.date} AS date, ${split.amount} AS amount,
                   ${split.payee} AS payee_id, p.name AS payee_name, p.transfer_acct AS transfer_acct, ta.name AS transfer_account_name,
                   ${split.category} AS category_id, cat.name AS category_name, ${split.notes} AS notes, ${split.cleared} AS cleared,
                   ${split.reconciled} AS reconciled, ${split.isParent} AS is_parent, ${split.transferredId} AS transfer_id,
                   ${split.startingBalance} AS starting_balance
            FROM transactions t
            ${split.parentJoin()}
            LEFT JOIN accounts a ON a.id = ${split.account}
            LEFT JOIN payee_mapping pm ON pm.id = ${split.payee}
            LEFT JOIN payees p ON p.id = COALESCE(pm.targetId, ${split.payee})
            LEFT JOIN accounts ta ON ta.id = p.transfer_acct
            LEFT JOIN category_mapping cm ON cm.id = ${split.category}
            LEFT JOIN categories cat ON cat.id = COALESCE(cm.transferId, ${split.category})
            WHERE ${predicates.joinToString(" AND ")}
            ORDER BY ${split.defaultOrder()}
            LIMIT ?
            """.trimIndent(),
            *args.toTypedArray(),
        ).map(::mapRow)
        val parentIds = rows.filter { it.isParent }.map { it.id }
        val children = if (parentIds.isEmpty()) emptyMap() else childrenOf(parentIds)
        rows.map { if (it.isParent) it.copy(children = children[it.id].orEmpty()) else it }
    }

    fun transaction(id: String): TransactionRow? =
        transactions(TransactionQuery(limit = 1_000_000)).firstOrNull { it.id == id }
            ?: transactions(TransactionQuery(limit = 1_000_000)).flatMap { it.children }.firstOrNull { it.id == id }

    private fun childrenOf(parentIds: List<String>): Map<String, List<TransactionRow>> {
        val placeholders = parentIds.joinToString(",") { "?" }
        return sql.query(
            """
            SELECT t.id AS id, t.acct AS account_id, a.name AS account_name, t.date AS date, t.amount AS amount,
                   t.description AS payee_id, p.name AS payee_name, p.transfer_acct AS transfer_acct, ta.name AS transfer_account_name,
                   t.category AS category_id, cat.name AS category_name, t.notes AS notes, t.cleared AS cleared,
                   t.reconciled AS reconciled, 0 AS is_parent, t.transferred_id AS transfer_id, t.parent_id AS parent_id,
                   0 AS starting_balance
            FROM transactions t
            LEFT JOIN accounts a ON a.id = t.acct
            LEFT JOIN payees p ON p.id = t.description
            LEFT JOIN accounts ta ON ta.id = p.transfer_acct
            LEFT JOIN categories cat ON cat.id = t.category
            WHERE t.parent_id IN ($placeholders) AND (t.tombstone IS NULL OR t.tombstone = 0)
            ORDER BY t.sort_order DESC, t.id
            """.trimIndent(),
            *parentIds.toTypedArray(),
        ).map(::mapRow).groupBy { it.parentId ?: "" }
    }

    private fun mapRow(row: com.sporez.actualist.core.db.SqlRow): TransactionRow {
        val transferAccount = row.string("transfer_acct")?.takeIf { it.isNotEmpty() }
        return TransactionRow(
            id = row.string("id") ?: "",
            accountId = row.string("account_id") ?: "",
            accountName = row.string("account_name") ?: "",
            date = row.long("date")?.let(Months::dateFromPacked) ?: row.string("date")?.let(Months::parseDate),
            amount = row.longOr0("amount"),
            payeeId = row.string("payee_id"),
            payeeName = row.string("payee_name"),
            categoryId = row.string("category_id"),
            categoryName = row.string("category_name"),
            notes = row.string("notes"),
            cleared = row.bool("cleared"),
            reconciled = row.bool("reconciled"),
            isParent = row.bool("is_parent"),
            isChild = row.string("parent_id") != null,
            parentId = row.string("parent_id"),
            transferId = row.string("transfer_id"),
            transferAccountName = if (transferAccount != null) row.string("transfer_account_name") else null,
            startingBalance = row.bool("starting_balance"),
        )
    }

    /** Number of uncategorized on-budget rows, which Actualist surfaces as a review prompt. */
    fun uncategorizedCount(): Int = transactions(TransactionQuery(uncategorizedOnly = true, limit = 100_000)).size
}
