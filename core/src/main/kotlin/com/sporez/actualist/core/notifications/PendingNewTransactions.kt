package com.sporez.actualist.core.notifications

import com.sporez.actualist.core.db.BudgetDatabase

/** The notification text, as Actualist's `NewTransactionsNotificationCopy`. */
object NewTransactionsNotificationCopy {
    const val TITLE = "Actualist"
    const val BODY = "New transactions found"
}

/** Where a pending-new row came from, stored by name as Actualist stores its raw values. */
enum class PendingNewTransactionSource(val raw: String) {
    REMOTE_SYNC("remoteSync"),
    BANK_SYNC("bankSync"),
    LEGACY_SETTINGS("legacySettings"),
}

/** One notification still to be shown: its id and the transactions it announces. */
data class PendingNewTransactionDelivery(val notificationId: String, val transactionIds: List<String>)

/**
 * Port of Actualist's `BudgetDatabase+PendingNewTransactions`: transactions that arrived from a
 * sync and have not been looked at yet. The table is local only: its `actualist_` prefix keeps
 * it out of the CRDT flow ([BudgetDatabase.isReservedDataset]), out of the budget ZIP export
 * (the sanitizer drops `actualist_` tables) and out of undo (it is never written through
 * `commitLocal`). A reviewed row stays as a tombstone, so seeing the same transaction again
 * never makes it new again.
 */
class PendingNewTransactions(private val database: BudgetDatabase) {
    private val sql get() = database.sql

    private fun ensureSchema() {
        sql.execute(
            """
            CREATE TABLE IF NOT EXISTS $TABLE (
                transaction_id TEXT PRIMARY KEY,
                account_id TEXT NOT NULL,
                source TEXT NOT NULL,
                detected_at REAL NOT NULL,
                notification_id TEXT,
                notification_acknowledged_at REAL,
                reviewed_at REAL
            )
            """.trimIndent(),
        )
        sql.execute("CREATE INDEX IF NOT EXISTS ${TABLE}_review ON $TABLE(reviewed_at, account_id)")
        sql.execute("CREATE INDEX IF NOT EXISTS ${TABLE}_delivery ON $TABLE(notification_acknowledged_at, reviewed_at, detected_at)")
    }

    /** Every live top-level transaction id with its account, for before/after comparison around a sync. */
    fun liveTransactionAccounts(): Map<String, String> {
        if (!database.tableExists("transactions")) return emptyMap()
        val rows = sql.query(
            "SELECT id, acct FROM transactions WHERE (tombstone IS NULL OR tombstone = 0) AND parent_id IS NULL AND acct IS NOT NULL",
        )
        val out = HashMap<String, String>(rows.size)
        for (row in rows) {
            val id = row.string("id") ?: continue
            val account = row.string("acct") ?: continue
            out[id] = account
        }
        return out
    }

    /** Transactions present now that were not in [before], grouped by account. */
    fun newSince(before: Map<String, String>): Map<String, List<String>> {
        val grouped = HashMap<String, MutableList<String>>()
        for ((id, account) in liveTransactionAccounts()) {
            if (id !in before) grouped.getOrPut(account) { mutableListOf() } += id
        }
        return grouped
    }

    /** Records transactions as new. Existing rows, reviewed ones included, are left alone. Returns how many were added. */
    fun record(
        idsByAccount: Map<String, Collection<String>>,
        source: PendingNewTransactionSource,
        notificationId: String?,
        alreadyAcknowledged: Boolean = false,
        nowMillis: Long = System.currentTimeMillis(),
    ): Int {
        if (idsByAccount.isEmpty()) return 0
        val now = nowMillis / 1000.0
        val acknowledgedAt: Double? = if (alreadyAcknowledged) now else null
        return sql.transaction {
            ensureSchema()
            var added = 0
            for ((accountId, ids) in idsByAccount) {
                for (id in ids.toSet()) {
                    if (id.isEmpty()) continue
                    val exists = sql.queryOne("SELECT 1 AS one FROM $TABLE WHERE transaction_id = ?", id) != null
                    if (exists) continue
                    sql.execute(
                        "INSERT INTO $TABLE (transaction_id, account_id, source, detected_at, notification_id, notification_acknowledged_at, reviewed_at) VALUES (?, ?, ?, ?, ?, ?, NULL)",
                        id,
                        accountId,
                        source.raw,
                        now,
                        notificationId,
                        acknowledgedAt,
                    )
                    added++
                }
            }
            added
        }
    }

    /** Ids still marked new, for one account or all of them. */
    fun pendingIds(accountId: String? = null): Set<String> {
        ensureSchema()
        val rows = if (accountId == null) {
            sql.query("SELECT transaction_id FROM $TABLE WHERE reviewed_at IS NULL")
        } else {
            sql.query("SELECT transaction_id FROM $TABLE WHERE reviewed_at IS NULL AND account_id = ?", accountId)
        }
        return rows.mapNotNull { it.string("transaction_id") }.toSet()
    }

    /** The oldest notification that has not been shown, with every transaction it announces. */
    fun delivery(): PendingNewTransactionDelivery? {
        ensureSchema()
        val notificationId = sql.queryString(
            """
            SELECT notification_id FROM $TABLE
            WHERE reviewed_at IS NULL AND notification_acknowledged_at IS NULL AND notification_id IS NOT NULL
            ORDER BY detected_at, notification_id LIMIT 1
            """.trimIndent(),
        ) ?: return null
        val ids = sql.query(
            """
            SELECT transaction_id FROM $TABLE
            WHERE reviewed_at IS NULL AND notification_acknowledged_at IS NULL AND notification_id IS NOT NULL
            ORDER BY transaction_id
            """.trimIndent(),
        ).mapNotNull { it.string("transaction_id") }
        return PendingNewTransactionDelivery(notificationId, ids)
    }

    /** Marks the notification for these transactions as shown. */
    fun acknowledgeDelivery(transactionIds: Collection<String>, nowMillis: Long = System.currentTimeMillis()) {
        if (transactionIds.isEmpty()) return
        ensureSchema()
        val now = nowMillis / 1000.0
        sql.transaction {
            for (id in transactionIds) {
                sql.execute(
                    "UPDATE $TABLE SET notification_acknowledged_at = COALESCE(notification_acknowledged_at, ?) WHERE transaction_id = ?",
                    now,
                    id,
                )
            }
        }
    }

    /** Alerts turned off: nothing waiting is ever announced, though the rows stay marked new. */
    fun suppressDeliveries(nowMillis: Long = System.currentTimeMillis()) {
        ensureSchema()
        sql.execute(
            "UPDATE $TABLE SET notification_acknowledged_at = COALESCE(notification_acknowledged_at, ?) WHERE notification_acknowledged_at IS NULL",
            nowMillis / 1000.0,
        )
    }

    /** Marks these transactions reviewed (within [accountId] when given). Returns how many changed. */
    fun review(transactionIds: Collection<String>, accountId: String? = null, nowMillis: Long = System.currentTimeMillis()): Int {
        if (transactionIds.isEmpty()) return 0
        ensureSchema()
        val now = nowMillis / 1000.0
        return sql.transaction {
            var changed = 0
            for (id in transactionIds) {
                if (id.isEmpty()) continue
                val before = pendingCount(id, accountId)
                if (accountId == null) {
                    sql.execute("UPDATE $TABLE SET reviewed_at = COALESCE(reviewed_at, ?) WHERE reviewed_at IS NULL AND transaction_id = ?", now, id)
                } else {
                    sql.execute(
                        "UPDATE $TABLE SET reviewed_at = COALESCE(reviewed_at, ?) WHERE reviewed_at IS NULL AND transaction_id = ? AND account_id = ?",
                        now,
                        id,
                        accountId,
                    )
                }
                changed += before
            }
            changed
        }
    }

    private fun pendingCount(id: String, accountId: String?): Int {
        val count = if (accountId == null) {
            sql.queryLong("SELECT COUNT(*) FROM $TABLE WHERE reviewed_at IS NULL AND transaction_id = ?", id)
        } else {
            sql.queryLong("SELECT COUNT(*) FROM $TABLE WHERE reviewed_at IS NULL AND transaction_id = ? AND account_id = ?", id, accountId)
        }
        return (count ?: 0L).toInt()
    }

    companion object {
        const val TABLE = "actualist_pending_new_transactions"
    }
}
