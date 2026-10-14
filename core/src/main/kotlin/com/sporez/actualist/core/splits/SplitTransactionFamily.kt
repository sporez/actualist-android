package com.sporez.actualist.core.splits

import java.util.UUID

/** Actual `SplitTransactionError`: parent amount minus child total. */
data class SplitTransactionError(
    val type: String = "SplitTransactionError",
    val version: Int = 1,
    val difference: Long,
) {
    companion object {
        fun of(total: Long, parentAmount: Long) = SplitTransactionError(difference = parentAmount - total)
    }
}

/**
 * Distinguishes omitted JSON keys from explicit null, matching JavaScript `'field' in data`
 * in Actual `makeChild`.
 */
sealed class SplitOptionalField<out T> {
    object Omitted : SplitOptionalField<Nothing>()
    data class Value<out T>(val value: T?) : SplitOptionalField<T>()
}

/** Patch for `makeChild` / conversion inputs. Omitted category and payee inherit from the parent. */
data class SplitTransactionPatch(
    val id: String? = null,
    val amount: Long? = null,
    val category: SplitOptionalField<String> = SplitOptionalField.Omitted,
    val payee: SplitOptionalField<String> = SplitOptionalField.Omitted,
    val notes: SplitOptionalField<String> = SplitOptionalField.Omitted,
    val sortOrder: SplitOptionalField<Double> = SplitOptionalField.Omitted,
) {
    companion object {
        fun fromRecord(record: SplitTransactionRecord, includePayee: Boolean = true) = SplitTransactionPatch(
            id = record.id,
            amount = record.amount,
            category = SplitOptionalField.Value(record.category),
            payee = if (includePayee) SplitOptionalField.Value(record.payee) else SplitOptionalField.Omitted,
            notes = SplitOptionalField.Value(record.notes),
            sortOrder = SplitOptionalField.Value(record.sortOrder),
        )
    }
}

/** Canonical split-family row. Field names match the pinned `family-transformations.json` shape. */
data class SplitTransactionRecord(
    val id: String,
    val amount: Long = 0,
    val account: String? = null,
    val date: String? = null,
    val category: String? = null,
    val payee: String? = null,
    val notes: String? = null,
    val cleared: Boolean? = null,
    val reconciled: Boolean? = null,
    val startingBalance: Boolean? = null,
    val sortOrder: Double? = null,
    val isParent: Boolean = false,
    val isChild: Boolean = false,
    val parentId: String? = null,
    val transferId: String? = null,
    val error: SplitTransactionError? = null,
    val deleted: Boolean = false,
    val subtransactions: List<SplitTransactionRecord> = emptyList(),
) {
    /** `selected()` shape used by the pinned oracle JSON: no embedded children. */
    fun selected(): SplitTransactionRecord = copy(subtransactions = emptyList())

    val effectiveParentId: String? get() = if (isChild) parentId else null
}

data class SplitTransactionFamily(
    val parent: SplitTransactionRecord,
    val children: List<SplitTransactionRecord>,
) {
    val selected: SplitTransactionFamily
        get() = SplitTransactionFamily(parent.selected(), children.map { it.selected() })
}

/** Row cells a split diff can report as changed. */
enum class SplitTransactionField {
    AMOUNT, ACCOUNT, DATE, CATEGORY, PAYEE, NOTES, CLEARED, RECONCILED, STARTING_BALANCE,
    SORT_ORDER, IS_PARENT, IS_CHILD, PARENT_ID, TRANSFER_ID, ERROR, DELETED,
}

/** Actual `getChangedValues`: the new record plus exactly the cells that differ. */
data class SplitTransactionUpdate(val record: SplitTransactionRecord, val changedFields: Set<SplitTransactionField>)

data class SplitTransactionDiff(
    val added: List<SplitTransactionRecord>,
    val updated: List<SplitTransactionUpdate>,
    val deleted: List<String>,
) {
    companion object {
        val EMPTY = SplitTransactionDiff(emptyList(), emptyList(), emptyList())
    }
}

data class SplitTransactionChangeSet(
    val data: List<SplitTransactionRecord>,
    val newTransaction: SplitTransactionRecord?,
    val diff: SplitTransactionDiff,
)

/** The flat rows of a family and, when the parent is among them, the family. */
data class SplitRowsAndFamily(val rows: List<SplitTransactionRecord>, val family: SplitTransactionFamily?)

/** Port of Actualist `SplitTransactionFamilyOps` (Actual `shared/transactions.ts` family operations). */
object SplitTransactionFamilyOps {
    private fun defaultId(): String = UUID.randomUUID().toString()

    fun makeChild(
        parent: SplitTransactionRecord,
        data: SplitTransactionPatch = SplitTransactionPatch(),
        idGenerator: () -> String = { defaultId() },
    ): SplitTransactionRecord {
        val prefix = if (parent.id == "temp") "temp" else ""
        val category = when (val field = data.category) {
            is SplitOptionalField.Omitted -> parent.category
            is SplitOptionalField.Value -> field.value
        }
        val payee = when (val field = data.payee) {
            is SplitOptionalField.Omitted -> parent.payee
            is SplitOptionalField.Value -> field.value
        }
        val notes = when (val field = data.notes) {
            is SplitOptionalField.Omitted -> null
            is SplitOptionalField.Value -> field.value
        }
        val sortOrder = when (val field = data.sortOrder) {
            is SplitOptionalField.Omitted -> parent.sortOrder
            is SplitOptionalField.Value -> field.value
        }
        return SplitTransactionRecord(
            id = data.id ?: (prefix + idGenerator()),
            amount = data.amount ?: 0L,
            account = parent.account,
            date = parent.date,
            category = category,
            payee = payee,
            notes = notes,
            cleared = parent.cleared,
            reconciled = parent.reconciled,
            startingBalance = parent.startingBalance,
            sortOrder = sortOrder,
            isParent = false,
            isChild = true,
            parentId = parent.id,
            transferId = null,
            error = null,
            deleted = false,
        )
    }

    fun makeChild(
        parent: SplitTransactionRecord,
        record: SplitTransactionRecord,
        includePayee: Boolean = true,
        idGenerator: () -> String = { defaultId() },
    ): SplitTransactionRecord =
        makeChild(parent, SplitTransactionPatch.fromRecord(record, includePayee), idGenerator)

    fun recalculateSplit(transaction: SplitTransactionRecord): SplitTransactionRecord {
        val total = transaction.subtransactions.sumOf { it.amount }
        return transaction.copy(
            error = if (total == transaction.amount) null else SplitTransactionError.of(total, transaction.amount),
        )
    }

    fun groupTransaction(split: List<SplitTransactionRecord>): SplitTransactionRecord {
        val parent = split.firstOrNull() ?: return SplitTransactionRecord(id = "")
        return parent.copy(subtransactions = split.drop(1))
    }

    fun ungroupTransaction(split: SplitTransactionRecord?): List<SplitTransactionRecord> =
        if (split == null) emptyList() else ungroupTransactions(listOf(split))

    fun ungroupTransactions(transactions: List<SplitTransactionRecord>): List<SplitTransactionRecord> {
        val list = mutableListOf<SplitTransactionRecord>()
        for (parent in transactions) {
            list.add(parent.copy(subtransactions = emptyList()))
            list.addAll(parent.subtransactions)
        }
        return list
    }

    fun addSplitTransaction(
        transactions: List<SplitTransactionRecord>,
        id: String,
        idGenerator: () -> String = { defaultId() },
    ): SplitTransactionChangeSet = replaceTransactions(transactions, id) { trans ->
        if (!trans.isParent) {
            trans
        } else {
            val previous = trans.subtransactions.lastOrNull()
            val child = makeChild(
                trans,
                SplitTransactionPatch(
                    amount = 0,
                    payee = SplitOptionalField.Value(previous?.payee ?: trans.payee),
                    sortOrder = SplitOptionalField.Value((previous?.sortOrder ?: 0.0) - 1.0),
                ),
                idGenerator,
            )
            trans.copy(subtransactions = trans.subtransactions + child)
        }
    }

    fun splitTransaction(
        transactions: List<SplitTransactionRecord>,
        id: String,
        createSubtransactions: ((SplitTransactionRecord) -> List<SplitTransactionRecord>)? = null,
        idGenerator: () -> String = { defaultId() },
    ): SplitTransactionChangeSet = replaceTransactions(transactions, id) { trans ->
        if (trans.isParent || trans.isChild) {
            trans
        } else {
            val subtransactions = createSubtransactions?.invoke(trans) ?: listOf(makeChild(trans, SplitTransactionPatch(), idGenerator))
            trans.copy(
                isParent = true,
                payee = null,
                error = if (trans.amount == 0L) null else SplitTransactionError.of(0, trans.amount),
                subtransactions = subtransactions.map { child ->
                    // Actual: `sort_order || -1`, so 0 is also replaced.
                    val order = child.sortOrder ?: 0.0
                    child.copy(sortOrder = if (order == 0.0) -1.0 else child.sortOrder)
                },
            )
        }
    }

    fun updateTransaction(
        transactions: List<SplitTransactionRecord>,
        transaction: SplitTransactionRecord,
        conversionChildren: List<SplitTransactionPatch>? = null,
        idGenerator: () -> String = { defaultId() },
    ): SplitTransactionChangeSet {
        val conversionPatches = conversionChildren ?: transaction.subtransactions.map { SplitTransactionPatch.fromRecord(it) }
        return replaceTransactions(transactions, transaction.id) { trans ->
            if (trans.isParent) {
                val parent = if (trans.id == transaction.id) merged(trans, transaction) else trans
                val originalSubtransactions = if (parent.subtransactions.isEmpty()) trans.subtransactions else parent.subtransactions
                val sub = originalSubtransactions.map { child ->
                    var next = child
                    var rebuilt: SplitTransactionRecord? = null
                    if (trans.id == transaction.id) {
                        val newPayee = if (child.payee == trans.payee) transaction.payee else child.payee
                        if (newPayee == null) {
                            rebuilt = makeChild(parent, child, false, idGenerator)
                        } else {
                            next = child.copy(payee = newPayee)
                        }
                    } else if (child.id == transaction.id) {
                        next = transaction
                    }
                    rebuilt ?: makeChild(parent, next, true, idGenerator)
                }
                recalculateSplit(parent.copy(subtransactions = sub))
            } else if (conversionPatches.isNotEmpty()) {
                val base = merged(trans, transaction)
                val parent = base.copy(isParent = true, isChild = false, parentId = null)
                val children = conversionPatches.mapIndexed { index, sub ->
                    val order = sub.sortOrder
                    val patch = if (order is SplitOptionalField.Value && order.value != null) {
                        sub
                    } else {
                        sub.copy(sortOrder = SplitOptionalField.Value(-(index + 1).toDouble()))
                    }
                    makeChild(parent, patch, idGenerator)
                }
                recalculateSplit(parent.copy(subtransactions = children))
            } else {
                transaction
            }
        }
    }

    fun deleteTransaction(transactions: List<SplitTransactionRecord>, id: String): SplitTransactionChangeSet =
        replaceTransactions(transactions, id) { trans ->
            if (trans.isParent) {
                if (trans.id == id) {
                    null
                } else if (trans.subtransactions.size == 1) {
                    trans.copy(subtransactions = emptyList(), isParent = false, error = null)
                } else {
                    recalculateSplit(trans.copy(subtransactions = trans.subtransactions.filter { it.id != id }))
                }
            } else {
                null
            }
        }

    fun family(rows: List<SplitTransactionRecord>, parentId: String = "parent-1"): SplitRowsAndFamily {
        val selectedRows = rows.map { it.selected() }
        val parent = rows.firstOrNull { it.id == parentId } ?: return SplitRowsAndFamily(selectedRows, null)
        val family = SplitTransactionFamily(
            parent.selected(),
            rows.filter { it.parentId == parentId }.map { it.selected() },
        ).selected
        return SplitRowsAndFamily(selectedRows, family)
    }

    fun diffItems(items: List<SplitTransactionRecord>, newItems: List<SplitTransactionRecord>): SplitTransactionDiff {
        val grouped = items.associateBy { it.id }
        val newGrouped = newItems.associateBy { it.id }
        val deleted = items.filter { newGrouped[it.id] == null }.map { it.id }
        val added = mutableListOf<SplitTransactionRecord>()
        val updated = mutableListOf<SplitTransactionUpdate>()
        for (newItem in newItems) {
            val item = grouped[newItem.id]
            if (item == null) {
                added.add(newItem)
                continue
            }
            changedValues(item, newItem)?.let { updated.add(it) }
        }
        return SplitTransactionDiff(added, updated, deleted)
    }

    // region Internals

    private fun merged(base: SplitTransactionRecord, overlay: SplitTransactionRecord): SplitTransactionRecord =
        if (overlay.subtransactions.isEmpty()) overlay.copy(subtransactions = base.subtransactions) else overlay

    private fun findParentIndex(transactions: List<SplitTransactionRecord>, idx: Int): Int? {
        var index = idx
        while (index >= 0) {
            if (transactions[index].isParent) return index
            index -= 1
        }
        return null
    }

    private fun getSplit(transactions: List<SplitTransactionRecord>, parentIndex: Int): List<SplitTransactionRecord> {
        val split = mutableListOf(transactions[parentIndex])
        var current = parentIndex + 1
        while (current < transactions.size && transactions[current].isChild) {
            split.add(transactions[current])
            current += 1
        }
        return split
    }

    private fun replaceTransactions(
        transactions: List<SplitTransactionRecord>,
        id: String,
        transform: (SplitTransactionRecord) -> SplitTransactionRecord?,
    ): SplitTransactionChangeSet {
        val none = SplitTransactionChangeSet(emptyList(), null, SplitTransactionDiff.EMPTY)
        val idx = transactions.indexOfFirst { it.id == id }
        if (idx < 0) return none
        val trans = transactions[idx]
        val copy = transactions.toMutableList()
        if (trans.isParent || trans.isChild) {
            val parentIndex = findParentIndex(transactions, idx) ?: return none
            val split = getSplit(transactions, parentIndex)
            val grouped = transform(groupTransaction(split))
            val newSplit = ungroupTransaction(grouped)
            val diff: SplitTransactionDiff
            val newTransaction: SplitTransactionRecord?
            copy.subList(parentIndex, parentIndex + split.size).clear()
            if (newSplit.isEmpty()) {
                diff = SplitTransactionDiff(emptyList(), emptyList(), listOf(split[0].id))
                newTransaction = split[0].copy(deleted = true)
            } else {
                diff = diffItems(split, newSplit)
                copy.addAll(parentIndex, newSplit)
                newTransaction = grouped
            }
            return SplitTransactionChangeSet(copy, newTransaction, diff)
        }
        val grouped = transform(trans)
        val newTrans = ungroupTransaction(grouped)
        copy.removeAt(idx)
        copy.addAll(idx, newTrans)
        val newTransaction = grouped ?: trans.copy(deleted = true)
        return SplitTransactionChangeSet(copy, newTransaction, diffItems(listOf(trans), newTrans))
    }

    private fun sameDouble(a: Double?, b: Double?): Boolean {
        if (a == null || b == null) return a == null && b == null
        return a == b
    }

    private fun changedValues(old: SplitTransactionRecord, new: SplitTransactionRecord): SplitTransactionUpdate? {
        val changed = mutableSetOf<SplitTransactionField>()
        if (old.amount != new.amount) changed.add(SplitTransactionField.AMOUNT)
        if (old.account != new.account) changed.add(SplitTransactionField.ACCOUNT)
        if (old.date != new.date) changed.add(SplitTransactionField.DATE)
        if (old.category != new.category) changed.add(SplitTransactionField.CATEGORY)
        if (old.payee != new.payee) changed.add(SplitTransactionField.PAYEE)
        if (old.notes != new.notes) changed.add(SplitTransactionField.NOTES)
        if (old.cleared != new.cleared) changed.add(SplitTransactionField.CLEARED)
        if (old.reconciled != new.reconciled) changed.add(SplitTransactionField.RECONCILED)
        if (old.startingBalance != new.startingBalance) changed.add(SplitTransactionField.STARTING_BALANCE)
        if (!sameDouble(old.sortOrder, new.sortOrder)) changed.add(SplitTransactionField.SORT_ORDER)
        if (old.isParent != new.isParent) changed.add(SplitTransactionField.IS_PARENT)
        if (old.isChild != new.isChild) changed.add(SplitTransactionField.IS_CHILD)
        if (old.parentId != new.parentId) changed.add(SplitTransactionField.PARENT_ID)
        if (old.transferId != new.transferId) changed.add(SplitTransactionField.TRANSFER_ID)
        if (old.error != new.error) changed.add(SplitTransactionField.ERROR)
        if (old.deleted != new.deleted) changed.add(SplitTransactionField.DELETED)
        return if (changed.isEmpty()) null else SplitTransactionUpdate(new, changed)
    }

    // endregion
}
