package com.sporez.actualist.core.commands

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.CellDraft
import com.sporez.actualist.core.db.Clock
import java.util.UUID

/** What a duplicate created: a copy of every selected row, and of the rows tied to it. */
data class DuplicateResult(
    /** The copy of each selected transaction, in selection order. */
    val duplicateIds: List<String>,
    /** Every source row copied (the whole split family and transfer pair) to its new id. */
    val sourceToDuplicate: Map<String, String>,
    val changedAccountIds: List<String>,
    val changedMonths: List<String>,
)

/** What a merge did: the row that stays and the row that was tombstoned. */
data class MergeResult(
    val keeperId: String,
    val removedId: String,
    /** Every row the merge touched, including split children and transfer peers. */
    val affectedTransactionIds: List<String>,
    val changedAccountIds: List<String>,
    val changedMonths: List<String>,
)

/** What a merge would do, for the confirmation the UI shows before it commits. */
data class MergePreview(
    val keeperId: String,
    val removedId: String,
    /** Reconciled rows in the merge. Non-empty means the user must confirm. */
    val reconciledIds: List<String>,
)

/**
 * Transaction commands: duplicate and merge. Port of Actualist's `BudgetDatabase`
 * `+TransactionDuplicate` and `+TransactionMerge` with their planners. Each command plans the
 * whole graph, validates it, and commits every cell in one atomic [BudgetDatabase.commitLocal];
 * a refused command writes nothing and throws [LocalFirstException.InvalidLocalWrite] with a
 * reason the UI can show.
 *
 * Not ported: Actualist's review fingerprints (this facade plans and commits in one call, so
 * nothing can go stale between review and commit) and the action-log undo record.
 */
class TransactionCommands(
    private val database: BudgetDatabase,
    private val clock: Clock = Clock.SYSTEM,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val sql get() = database.sql
    private val reader = TransactionGraphReader(database)

    // region Duplicate

    /**
     * Copies each selected transaction. A split child, or a row in a split, copies the whole
     * family; a transfer copies both sides. The copies are uncleared and unreconciled and keep
     * everything else, including imported metadata and schedule.
     */
    fun duplicate(transactionIds: List<String>): DuplicateResult {
        val selected = transactionIds.distinct()
        if (selected.isEmpty() || selected.any { it.isEmpty() }) {
            throw LocalFirstException.InvalidLocalWrite("invalid transaction duplicate selection")
        }
        val committed = database.commitLocal {
            val plan = planDuplicate(selected)
            val drafts = plan.afterSnapshots.flatMap { restoreDrafts(it) }
            if (drafts.isEmpty()) throw LocalFirstException.InvalidLocalWrite("the transaction duplicate has no writable rows")
            drafts to plan
        }
        val plan = committed.outcome
        val sourceToDuplicate = plan.sourceToDuplicate
        return DuplicateResult(
            duplicateIds = selected.mapNotNull { sourceToDuplicate[it] },
            sourceToDuplicate = sourceToDuplicate,
            changedAccountIds = plan.afterSnapshots.mapNotNull { it.accountId }.toSet().sorted(),
            changedMonths = plan.afterSnapshots.mapNotNull { snapshot ->
                snapshot.date?.takeIf { PackedDates.isValid(it) }?.let { PackedDates.monthId(it) }
            }.toSet().sorted(),
        )
    }

    private fun planDuplicate(selected: List<String>): DuplicatePlan {
        val snapshotsById = HashMap<String, TxSnapshot>()
        for (id in selected) {
            val graph = reader.graph(id)
            val chosen = graph.snapshots[id]
            if (graph.invalidReason != null || chosen == null ||
                !graph.snapshots.values.all { it.tombstone == false && it.isComplete }
            ) {
                throw LocalFirstException.InvalidLocalWrite("a transaction duplicate source graph is incomplete")
            }
            for (snapshot in graph.snapshots.values) {
                val existing = snapshotsById[snapshot.id]
                if (existing != null && existing != snapshot) {
                    throw LocalFirstException.InvalidLocalWrite("overlapping transaction duplicate sources changed")
                }
                snapshotsById[snapshot.id] = snapshot
            }
        }
        val sources = snapshotsById.values.sortedBy { it.id }
        if (sources.isEmpty() || !reader.graphMembershipMatches(sources)) {
            throw LocalFirstException.InvalidLocalWrite("a transaction duplicate source graph is incomplete")
        }

        val sortOrderBase = clock.nowMillis().toDouble()
        val allocations = sources.mapIndexed { index, source ->
            DuplicateAllocation(source.id, newId(), sortOrderBase + index)
        }
        validateCloneIds(allocations, sources)
        return TransactionDuplicatePlanner.plan(selected, sources, allocations)
    }

    private fun validateCloneIds(allocations: List<DuplicateAllocation>, sources: List<TxSnapshot>) {
        val cloneIds = allocations.map { it.duplicateId }
        val sourceIds = sources.map { it.id }.toSet()
        if (cloneIds.isEmpty() || cloneIds.any { it.isEmpty() } || cloneIds.toSet().size != cloneIds.size ||
            cloneIds.any { it in sourceIds }
        ) {
            throw LocalFirstException.InvalidLocalWrite("invalid or colliding transaction duplicate IDs")
        }
        for (cloneId in cloneIds) {
            if (database.rowExists("transactions", cloneId)) {
                throw LocalFirstException.InvalidLocalWrite("a transaction duplicate ID already exists")
            }
        }
    }

    // endregion

    // region Merge

    /** Plans a merge without writing, so the UI can ask for confirmation first. */
    fun previewMerge(firstId: String, secondId: String): MergePreview {
        val plan = sql.transaction { planMerge(firstId, secondId) }
        return MergePreview(plan.keptId, plan.droppedId, plan.reconciledIds)
    }

    /**
     * Merges two transactions of one account and amount into the one Actual would keep:
     * the imported row over a manual one, then the earlier date. The keeper takes the other's
     * payee, category, notes, cleared, reconciled and schedule where it has none; a split
     * family or a transfer pair follows. The other row is tombstoned. Merging reconciled rows
     * needs [allowReconciled], which the UI sets after the user confirms.
     */
    fun merge(firstId: String, secondId: String, allowReconciled: Boolean = false): MergeResult {
        val committed = database.commitLocal {
            val plan = planMerge(firstId, secondId)
            if (plan.reconciledIds.isNotEmpty() && !allowReconciled) {
                throw LocalFirstException.InvalidLocalWrite("confirm the reconciled transaction warning again")
            }
            val drafts = mergeDrafts(plan)
            if (drafts.isEmpty()) throw LocalFirstException.InvalidLocalWrite("the merge has no transaction changes")
            drafts to plan
        }
        val plan = committed.outcome
        return MergeResult(
            keeperId = plan.keptId,
            removedId = plan.droppedId,
            affectedTransactionIds = plan.beforeSnapshots.map { it.id },
            changedAccountIds = plan.changedAccountIds,
            changedMonths = plan.changedMonths,
        )
    }

    private fun planMerge(firstId: String, secondId: String): MergePlan {
        if (firstId.isEmpty() || secondId.isEmpty() || firstId == secondId) {
            throw LocalFirstException.InvalidLocalWrite("select two different transactions to merge")
        }
        val first = mergeGraph(firstId)
        val second = mergeGraph(secondId)
        val all = LinkedHashMap<String, TxSnapshot>()
        for (snapshot in first.values.sortedBy { it.id } + second.values.sortedBy { it.id }) {
            if (!all.containsKey(snapshot.id)) all[snapshot.id] = snapshot
        }
        val reference = mergeReference(all.values.toList())
        return when (
            val result = TransactionMergePlanner.plan(
                listOf(firstId, secondId),
                first.values.sortedBy { it.id },
                second.values.sortedBy { it.id },
                reference,
            )
        ) {
            is MergePlanResult.Ready -> result.plan
            is MergePlanResult.Blocked -> throw LocalFirstException.InvalidLocalWrite(result.reason)
        }
    }

    private fun mergeGraph(transactionId: String): Map<String, TxSnapshot> {
        val graph = reader.graph(transactionId)
        if (graph.invalidReason != null || graph.snapshots[transactionId] == null || !graph.snapshots.values.all { it.isComplete }) {
            throw LocalFirstException.InvalidLocalWrite(graph.invalidReason ?: "the selected transaction graph is incomplete")
        }
        return graph.snapshots
    }

    private fun mergeReference(snapshots: List<TxSnapshot>): MergeReference {
        val destinations = LinkedHashMap<String, String>()
        for (payeeId in snapshots.mapNotNull { it.payeeId }.toSet().sorted()) {
            val accountId = transferAccountIdOf(payeeId)
            if (accountId != null) destinations[payeeId] = accountId
        }
        val accounts = LinkedHashMap<String, Boolean>()
        if (database.tableExists("accounts") && "id" in database.columnSet("accounts")) {
            val accountIds = snapshots.mapNotNull { it.accountId }.toMutableSet()
            accountIds.addAll(destinations.values)
            for (accountId in accountIds.sorted()) {
                if (database.rowExists("accounts", accountId)) accounts[accountId] = accountOffBudget(accountId)
            }
        }
        return MergeReference(accounts, destinations)
    }

    private fun transferAccountIdOf(payeeId: String): String? {
        if (payeeId.isEmpty() || !database.tableExists("payees")) return null
        if ("transfer_acct" !in database.columnSet("payees")) return null
        return sql.queryString("SELECT transfer_acct FROM payees WHERE id = ?", payeeId)?.takeIf { it.isNotEmpty() }
    }

    private fun accountOffBudget(accountId: String): Boolean =
        (sql.queryLong("SELECT offbudget FROM accounts WHERE id = ?", accountId) ?: 0L) != 0L

    /** One cell per changed field of each touched row, in Actualist's field order. */
    private fun mergeDrafts(plan: MergePlan): List<CellDraft> {
        if (plan.beforeSnapshots.map { it.id } != plan.afterSnapshots.map { it.id }) {
            throw LocalFirstException.InvalidLocalWrite("the transaction merge plan has incomplete snapshots")
        }
        val drafts = mutableListOf<CellDraft>()
        for (index in plan.beforeSnapshots.indices) {
            val before = plan.beforeSnapshots[index]
            val after = plan.afterSnapshots[index]
            if (!before.isComplete || !after.isComplete) {
                throw LocalFirstException.InvalidLocalWrite("the transaction merge schema changed")
            }
            val beforeCells = restoreDrafts(before)
            val afterCells = restoreDrafts(after)
            for (cellIndex in beforeCells.indices) {
                val old = beforeCells[cellIndex]
                val new = afterCells[cellIndex]
                if (old.value != new.value) drafts += new
            }
        }
        return drafts
    }

    // endregion
}
