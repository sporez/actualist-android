package com.sporez.actualist.core.transactions

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.budget.Months
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.CellDraft
import com.sporez.actualist.core.db.Clock
import com.sporez.actualist.core.rules.RuleEngine
import com.sporez.actualist.core.rules.RuleTransactionInput
import com.sporez.actualist.core.sync.SyncValue
import java.time.LocalDate
import java.util.UUID

data class ReconciliationSnapshot(val accountId: String, val clearedBalance: Long, val unclearedCount: Int, val lastReconciled: Long?)

/**
 * Account reconciliation, following Actual's account screen (and Actualist's
 * `BudgetDatabase+Reconciliation`):
 *
 * - the cleared balance sums cleared live rows of the account (split children count, parents do not);
 * - a nonzero difference is closed by a cleared "Reconciliation balance adjustment" row dated today;
 * - finishing at a zero difference locks every cleared, unlocked row (parents and children) and stamps
 *   `last_reconciled`; a nonzero difference is refused (Actualist's OP-16 choice);
 * - unlocking a simple row unlocks it, a parent unlocks its family, and a child unlocks only when its
 *   parent is already unlocked (then the whole family, as Actual's grouped query does).
 *
 */
class Reconciliation(
    private val database: BudgetDatabase,
    private val clock: Clock = Clock.SYSTEM,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val sql get() = database.sql

    /**
     * The balance the bank last reported for a SimpleFIN-linked account (`accounts.balance_current`,
     * written by sync), or null when the account has none. Actualist's `lastSyncedBalance`.
     */
    fun bankBalance(accountId: String): Long? {
        if ("balance_current" !in database.columnSet("accounts")) return null
        return sql.queryLong("SELECT balance_current FROM accounts WHERE id = ?", accountId)
    }

    fun snapshot(accountId: String): ReconciliationSnapshot {
        requireAccount(accountId)
        val cleared = sql.queryLong(
            """
            SELECT SUM(t.amount) FROM transactions t
            LEFT JOIN transactions parent ON parent.id = t.parent_id
            WHERE t.acct = ? AND (t.tombstone IS NULL OR t.tombstone = 0) AND t.cleared = 1
              AND (t.isParent IS NULL OR t.isParent = 0)
              AND (t.isChild IS NULL OR t.isChild = 0 OR (parent.tombstone IS NULL OR parent.tombstone = 0))
            """.trimIndent(),
            accountId,
        ) ?: 0
        val uncleared = sql.queryLong(
            "SELECT COUNT(*) FROM transactions WHERE acct = ? AND (tombstone IS NULL OR tombstone = 0) AND (cleared IS NULL OR cleared = 0) AND (isChild IS NULL OR isChild = 0)",
            accountId,
        ) ?: 0
        val last = sql.queryString("SELECT last_reconciled FROM accounts WHERE id = ?", accountId)?.toLongOrNull()
        return ReconciliationSnapshot(accountId, cleared, uncleared.toInt(), last)
    }

    /**
     * A cleared adjustment row closing the gap to [bankBalance]; no write when there is no gap.
     * The budget's rules run on it first, as Actualist's `reconciliationAdjustmentWrite` does: a
     * deleting rule cancels the adjustment, a rule that clears the notes keeps the default note
     * (Actualist's intended OP-17), and the result is written as a split, transfer or simple row.
     */
    fun adjustmentDrafts(accountId: String, bankBalance: Long, today: LocalDate): List<CellDraft> {
        val difference = bankBalance - snapshot(accountId).clearedBalance
        if (difference == 0L) return emptyList()
        val id = newId()
        var draft = TransactionDraft(
            accountId = accountId,
            date = today,
            amount = difference,
            notes = ADJUSTMENT_NOTE,
            cleared = true,
            sortOrder = clock.nowMillis().toDouble(),
        )
        val preview = RuleEngine(database).run(
            RuleTransactionInput(accountId = accountId, date = today, amount = difference, notes = ADJUSTMENT_NOTE, cleared = true, sortOrder = draft.sortOrder),
            today,
        )
        var payeeId: String? = null
        if (preview.appliedRuleIds.isNotEmpty()) {
            if (preview.deletesTransaction) return emptyList()
            payeeId = preview.payeeId
            draft = draft.copy(
                accountId = preview.accountId,
                date = preview.date,
                amount = preview.amount,
                categoryId = if (preview.splits.isEmpty()) preview.categoryId else null,
                notes = preview.notes?.takeIf { it.isNotEmpty() } ?: ADJUSTMENT_NOTE,
                cleared = preview.cleared,
                splits = preview.splits.map { SplitDraft(it.categoryId, it.amount, it.notes) },
            )
        }
        val writes = TransactionWrites(database, clock, newId)
        return when {
            draft.splits.isNotEmpty() -> writes.splitDrafts(draft, id, payeeId)
            payeeId != null && writes.transferAccountIdOf(payeeId) != null ->
                writes.transferDrafts(draft.copy(payeeId = payeeId, isTransfer = true), id, payeeId).drafts
            else -> writes.simpleDrafts(draft, id, payeeId)
        }
    }

    /** Locks cleared rows when the cleared balance equals [bankBalance]; refuses otherwise. */
    fun finishDrafts(accountId: String, bankBalance: Long): List<CellDraft> {
        val snapshot = snapshot(accountId)
        if (snapshot.clearedBalance != bankBalance) {
            throw LocalFirstException.InvalidLocalWrite("the cleared balance does not match the bank balance yet")
        }
        val ids = sql.query(
            """
            SELECT t.id AS id FROM transactions t
            WHERE t.acct = ? AND (t.tombstone IS NULL OR t.tombstone = 0) AND t.cleared = 1 AND (t.reconciled IS NULL OR t.reconciled = 0)
            ORDER BY t.id
            """.trimIndent(),
            accountId,
        ).mapNotNull { it.string("id") }
        return ids.map { CellDraft("transactions", it, "reconciled", SyncValue.bool(true)) } +
            CellDraft("accounts", accountId, "last_reconciled", SyncValue.text(clock.nowMillis().toString()))
    }

    fun unlockDrafts(transactionId: String): List<CellDraft> {
        val row = sql.queryOne(
            "SELECT id, isParent, isChild, parent_id, reconciled FROM transactions WHERE id = ? AND (tombstone IS NULL OR tombstone = 0)",
            transactionId,
        ) ?: throw LocalFirstException.InvalidLocalWrite("missing transaction")
        val familyId = when {
            row.bool("isParent") -> transactionId
            row.bool("isChild") -> row.string("parent_id")
            else -> null
        }
        if (familyId == null) {
            return if (row.bool("reconciled")) listOf(CellDraft("transactions", transactionId, "reconciled", SyncValue.bool(false))) else emptyList()
        }
        val parentReconciled = sql.queryOne("SELECT reconciled FROM transactions WHERE id = ?", familyId)?.bool("reconciled") ?: false
        // A locked parent keeps its children locked; unlock the parent instead.
        if (row.bool("isChild") && parentReconciled) return emptyList()
        val members = (listOf(familyId) + sql.query(
            "SELECT id FROM transactions WHERE parent_id = ? AND (tombstone IS NULL OR tombstone = 0) ORDER BY sort_order DESC, id",
            familyId,
        ).mapNotNull { it.string("id") })
        val reconciled = sql.query(
            "SELECT id FROM transactions WHERE id IN (${members.joinToString(",") { "?" }}) AND reconciled = 1",
            *members.toTypedArray(),
        ).mapNotNull { it.string("id") }.toSet()
        return members.filter { it in reconciled }.map { CellDraft("transactions", it, "reconciled", SyncValue.bool(false)) }
    }

    fun createAdjustment(accountId: String, bankBalance: Long, today: LocalDate) =
        database.commitLocal { adjustmentDrafts(accountId, bankBalance, today) to Unit }.messages

    fun finish(accountId: String, bankBalance: Long) =
        database.commitLocal { finishDrafts(accountId, bankBalance) to Unit }.messages

    fun unlock(transactionId: String) =
        database.commitLocal { unlockDrafts(transactionId) to Unit }.messages

    /** Marks one row cleared or uncleared; reconciled rows stay locked. */
    fun setCleared(transactionId: String, cleared: Boolean) = database.commitLocal {
        val row = sql.queryOne("SELECT reconciled, cleared FROM transactions WHERE id = ? AND (tombstone IS NULL OR tombstone = 0)", transactionId)
            ?: throw LocalFirstException.InvalidLocalWrite("missing transaction")
        if (row.bool("reconciled")) throw LocalFirstException.InvalidLocalWrite("reconciled transactions are locked")
        (if (row.bool("cleared") == cleared) emptyList() else listOf(CellDraft("transactions", transactionId, "cleared", SyncValue.bool(cleared)))) to Unit
    }.messages

    private fun requireAccount(accountId: String) {
        if (!database.liveRowExists("accounts", accountId)) throw LocalFirstException.InvalidLocalWrite("missing account")
    }

    companion object {
        const val ADJUSTMENT_NOTE = "Reconciliation balance adjustment"
    }
}
