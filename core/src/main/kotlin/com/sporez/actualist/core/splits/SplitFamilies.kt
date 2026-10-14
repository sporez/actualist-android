package com.sporez.actualist.core.splits

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.CellDraft
import com.sporez.actualist.core.db.Clock
import com.sporez.actualist.core.sync.SyncMessage
import java.time.LocalDate
import java.util.UUID

/**
 * One child of a split family as the editor collects it (Actualist `TransactionSplitDraft`).
 * A null [id] adds a new child; an id of an existing child edits it in place. Omitted payee,
 * notes and sort order keep an existing child's value, and a new child inherits the parent's payee.
 */
data class SplitChildDraft(
    val id: String? = null,
    val categoryId: String?,
    val amount: Long,
    val payeeId: SplitOptionalField<String> = SplitOptionalField.Omitted,
    val notes: SplitOptionalField<String> = SplitOptionalField.Omitted,
    val sortOrder: SplitOptionalField<Double> = SplitOptionalField.Omitted,
)

/**
 * A whole transaction after an edit (Actualist `TransactionDraft` as the split path reads it).
 * Non-empty [splits] makes a split family; empty [splits] makes a simple row (editing a family's
 * parent with no splits removes every child), or edits one child when the id is a child's.
 * [payeeId] is an already resolved payee id.
 */
data class SplitFamilyDraft(
    val accountId: String,
    val date: LocalDate,
    val amount: Long,
    val payeeId: String? = null,
    val categoryId: String? = null,
    val notes: String? = null,
    val cleared: Boolean = false,
    val reconciled: Boolean = false,
    val sortOrder: Double? = null,
    val importedPayee: String? = null,
    val importedId: String? = null,
    val scheduleId: String? = null,
    val splits: List<SplitChildDraft> = emptyList(),
) {
    val isSplit: Boolean get() = splits.isNotEmpty()
}

/** The cells a split-family write plans, and the accounts and rows it touches. */
data class SplitWritePlan(
    val drafts: List<CellDraft>,
    val affectedAccountIds: Set<String>,
    val affectedTransactionIds: Set<String>,
)

data class SplitWriteResult(
    val transactionId: String,
    val messages: List<SyncMessage>,
    val affectedAccountIds: Set<String>,
    val affectedTransactionIds: Set<String>,
)

/**
 * Split-family writes as Actual CRDT cells: create, edit (parent fields, add, remove or change
 * children, simple to split, split back to simple) and delete. Port of Actualist
 * `BudgetDatabase+TransactionSplitWrites`: the edited family goes through [SplitTransactionFamilyOps]
 * and the old and new rows are diffed into cells, including transfer pairs of children.
 *
 * Every `*Drafts` function must run inside [BudgetDatabase.commitLocal]; the `create`, `update`
 * and `delete` entry points do that.
 */
class SplitFamilies(
    private val database: BudgetDatabase,
    private val clock: Clock = Clock.SYSTEM,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val planner = SplitFamilyPlanner(database, newId)

    // region Commit entry points

    /** Creates a split family and returns the parent id. */
    fun create(draft: SplitFamilyDraft): SplitWriteResult {
        val id = newId()
        return commit(id) { createDrafts(draft, id) }
    }

    /** Applies [edit] to the family containing [transactionId] (a parent, a child, or a simple row). */
    fun update(transactionId: String, edit: SplitFamilyDraft): SplitWriteResult =
        commit(transactionId) { updateDrafts(transactionId, edit) }

    /** Deletes a family (parent id), one child (child id; the last child collapses the parent) or a row. */
    fun delete(transactionId: String): SplitWriteResult = commit(transactionId) { deleteDrafts(transactionId) }

    private fun commit(transactionId: String, plan: () -> SplitWritePlan): SplitWriteResult {
        val result = database.commitLocal {
            val planned = plan()
            planned.drafts to planned
        }
        return SplitWriteResult(transactionId, result.messages, result.outcome.affectedAccountIds, result.outcome.affectedTransactionIds)
    }

    // endregion

    // region Planning (inside commitLocal)

    fun createDrafts(draft: SplitFamilyDraft, parentId: String): SplitWritePlan {
        if (draft.accountId.isEmpty()) throw LocalFirstException.InvalidLocalWrite("missing account")
        val effective = withoutOffBudgetCategory(draft)
        if (effective.payeeId != null && planner.transferAccountId(effective.payeeId) != null) {
            throw LocalFirstException.InvalidLocalWrite("transfers cannot be split")
        }
        return planner.createFamily(effective, parentId, effective.payeeId, clock.nowMillis())
    }

    fun updateDrafts(transactionId: String, edit: SplitFamilyDraft): SplitWritePlan {
        val effective = withoutOffBudgetCategory(edit)
        val loaded = planner.loadFamily(transactionId)
        if (loaded.none { it.id == transactionId } || loaded.firstOrNull { it.id == transactionId }?.deleted == true) {
            throw LocalFirstException.InvalidLocalWrite("missing transaction")
        }
        if (loaded.any { it.reconciled == true }) throw LocalFirstException.InvalidLocalWrite("reconciled transactions are locked")
        val current = loaded.first { it.id == transactionId }
        if (effective.isSplit) {
            if (!current.isParent && !current.isChild && current.transferId != null) {
                throw LocalFirstException.InvalidLocalWrite("transfers cannot be split")
            }
            if (effective.payeeId != null && planner.transferAccountId(effective.payeeId) != null) {
                throw LocalFirstException.InvalidLocalWrite("transfers cannot be split")
            }
        }
        return planner.updateFamily(transactionId, effective, effective.payeeId)
    }

    fun deleteDrafts(transactionId: String): SplitWritePlan {
        val loaded = planner.loadFamily(transactionId)
        if (loaded.firstOrNull { it.id == transactionId }?.deleted != false) {
            throw LocalFirstException.InvalidLocalWrite("missing transaction")
        }
        if (loaded.any { it.reconciled == true }) throw LocalFirstException.InvalidLocalWrite("reconciled transactions are locked")
        return planner.deleteFamily(transactionId)
    }

    private fun withoutOffBudgetCategory(draft: SplitFamilyDraft): SplitFamilyDraft =
        if (planner.accountOffBudget(draft.accountId)) {
            draft.copy(categoryId = null, splits = draft.splits.map { it.copy(categoryId = null) })
        } else {
            draft
        }

    // endregion
}
