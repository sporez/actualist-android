package com.sporez.actualist.core.commands

/** Reference data the merge planner needs: which accounts exist and where transfer payees lead. */
internal data class MergeReference(
    /** Account id to whether it is off budget. */
    val offBudgetByAccountId: Map<String, Boolean>,
    /** Transfer payee id to the account it transfers to. */
    val destinationByPayeeId: Map<String, String>,
)

internal data class MergePlan(
    val orderedIds: List<String>,
    val keptId: String,
    val droppedId: String,
    val beforeSnapshots: List<TxSnapshot>,
    val afterSnapshots: List<TxSnapshot>,
    val reconciledIds: List<String>,
    val changedAccountIds: List<String>,
    val changedMonths: List<String>,
)

internal sealed class MergePlanResult {
    data class Ready(val plan: MergePlan) : MergePlanResult()
    data class Blocked(val reason: String) : MergePlanResult()
}

/**
 * Pure merge planner. Port of Actualist `TransactionMergePlanner`, which follows Actual's
 * `transactions-merge`: the imported row wins over a manual one, then the earlier date;
 * the keeper's fields fall back to the dropped row's by JavaScript `||`; a split family or a
 * transfer pair moves with the row it belongs to. The caller loads each closure with
 * [TransactionGraphReader.graph] and rejects a graph that reports an invalid reason first.
 */
internal object TransactionMergePlanner {
    private class Blocked(val reason: String) : Exception(reason)

    private fun blocked(reason: String): Nothing = throw Blocked(reason)

    private class ValidatedGraph(val rows: Map<String, TxSnapshot>, val childrenByParentId: Map<String, List<String>>)

    private class PairMerge(val keptId: String, val droppedId: String)

    fun plan(
        orderedIds: List<String>,
        firstGraph: List<TxSnapshot>,
        secondGraph: List<TxSnapshot>,
        reference: MergeReference,
    ): MergePlanResult = try {
        MergePlanResult.Ready(buildPlan(orderedIds, firstGraph, secondGraph, reference))
    } catch (block: Blocked) {
        MergePlanResult.Blocked(block.reason)
    }

    private fun buildPlan(
        orderedIds: List<String>,
        firstGraph: List<TxSnapshot>,
        secondGraph: List<TxSnapshot>,
        reference: MergeReference,
    ): MergePlan {
        if (orderedIds.size != 2) blocked("Select exactly two transactions to merge.")
        if (orderedIds.any { it.isEmpty() }) blocked("A selected transaction has no id.")
        if (orderedIds[0] == orderedIds[1]) blocked("Select two different transactions to merge.")

        val overlaps = firstGraph.map { it.id }.toSet().intersect(secondGraph.map { it.id }.toSet())
        if (overlaps.isNotEmpty()) blocked("These transactions are part of the same split or transfer, so they cannot be merged.")

        if (!referenceIsValid(reference)) blocked("The account or transfer payee data is inconsistent.")
        val first = validateGraph(firstGraph, orderedIds[0], reference)
        val second = validateGraph(secondGraph, orderedIds[1], reference)

        val firstRoot = first.rows[orderedIds[0]] ?: blocked("A selected transaction is missing.")
        val secondRoot = second.rows[orderedIds[1]] ?: blocked("A selected transaction is missing.")
        val firstAccountId = firstRoot.accountId
        val secondAccountId = secondRoot.accountId
        if (firstAccountId == null || secondAccountId == null || firstAccountId != secondAccountId) {
            blocked("Only transactions in the same account can be merged.")
        }
        val firstAmount = firstRoot.amount
        val secondAmount = secondRoot.amount
        if (firstAmount == null || secondAmount == null || firstAmount != secondAmount) {
            blocked("Only transactions with the same amount can be merged.")
        }

        val originalRows = LinkedHashMap<String, TxSnapshot>()
        originalRows.putAll(second.rows)
        originalRows.putAll(first.rows)
        val childrenByParentId = HashMap<String, List<String>>()
        childrenByParentId.putAll(second.childrenByParentId)
        childrenByParentId.putAll(first.childrenByParentId)

        val firstTransferPeerId = firstRoot.transferId
        val secondTransferPeerId = secondRoot.transferId
        val hasTwoRootTransfers = firstTransferPeerId != null && secondTransferPeerId != null
        val hasOneRootTransfer = (firstTransferPeerId != null) != (secondTransferPeerId != null)

        if (hasTwoRootTransfers) {
            val firstPayee = firstRoot.payeeId
            val secondPayee = secondRoot.payeeId
            val firstDestination = firstPayee?.let { reference.destinationByPayeeId[it] }
            val secondDestination = secondPayee?.let { reference.destinationByPayeeId[it] }
            if (firstPayee != secondPayee || firstDestination == null || secondDestination == null ||
                firstDestination != secondDestination
            ) {
                blocked("Transfers to different accounts cannot be merged.")
            }
        }

        val afterRows = LinkedHashMap<String, TxSnapshot>(originalRows)
        if (firstTransferPeerId != null) {
            afterRows[orderedIds[0]] = afterRows.getValue(orderedIds[0]).copy(transferId = null)
            afterRows[firstTransferPeerId]?.let { afterRows[firstTransferPeerId] = it.copy(transferId = null) }
        }
        if (secondTransferPeerId != null) {
            afterRows[orderedIds[1]] = afterRows.getValue(orderedIds[1]).copy(transferId = null)
            afterRows[secondTransferPeerId]?.let { afterRows[secondTransferPeerId] = it.copy(transferId = null) }
        }

        val rootPair = chooseKeepDrop(firstRoot, secondRoot)
        val rootTransferPayeeId: String? = when {
            firstTransferPeerId != null -> firstRoot.payeeId
            secondTransferPeerId != null -> secondRoot.payeeId
            else -> null
        }
        val rootTransferDestination = rootTransferPayeeId?.let { reference.destinationByPayeeId[it] }
        val rootDestinationIsOffBudget: Boolean? = rootTransferDestination?.let { reference.offBudgetByAccountId[it] }
        val clearsCategoryForTransfer = hasOneRootTransfer && rootDestinationIsOffBudget == false

        val deletedIds = HashSet<String>()
        val rootMerge = mergePair(
            rootPair, originalRows, afterRows, childrenByParentId, rootTransferPayeeId, clearsCategoryForTransfer, deletedIds,
        )

        if (hasTwoRootTransfers) {
            val firstPeer = originalRows[firstTransferPeerId!!] ?: blocked("A transfer in the selection is malformed.")
            val secondPeer = originalRows[secondTransferPeerId!!] ?: blocked("A transfer in the selection is malformed.")
            if (firstPeer.isChild != false || secondPeer.isChild != false) blocked("A transfer in the selection is malformed.")
            val peerPair = chooseKeepDrop(firstPeer, secondPeer)
            val peerMerge = mergePair(peerPair, originalRows, afterRows, childrenByParentId, null, false, deletedIds)
            if (rootTransferDestination == null) blocked("A transfer in the selection has no destination account.")
            afterRows[rootMerge.keptId] = afterRows.getValue(rootMerge.keptId).copy(transferId = peerMerge.keptId)
            afterRows[peerMerge.keptId] = afterRows.getValue(peerMerge.keptId).copy(transferId = rootMerge.keptId)
        } else if (hasOneRootTransfer) {
            val peerId = firstTransferPeerId ?: secondTransferPeerId ?: blocked("A transfer in the selection is malformed.")
            if (originalRows[peerId] == null || rootTransferDestination == null) blocked("A transfer in the selection is malformed.")
            afterRows[rootMerge.keptId] = afterRows.getValue(rootMerge.keptId).copy(transferId = peerId)
            afterRows[peerId] = afterRows.getValue(peerId).copy(transferId = rootMerge.keptId)
        }

        validateProposedGraph(afterRows, reference)

        val snapshots = afterRows.values.sortedBy { it.id }
        val beforeSnapshots = originalRows.values.sortedBy { it.id }
        val reconciledIds = beforeSnapshots.filter { it.reconciled == true }.map { it.id }.sorted()
        return MergePlan(
            orderedIds = orderedIds,
            keptId = rootMerge.keptId,
            droppedId = rootMerge.droppedId,
            beforeSnapshots = beforeSnapshots,
            afterSnapshots = snapshots,
            reconciledIds = reconciledIds,
            changedAccountIds = beforeSnapshots.mapNotNull { it.accountId }.toSet().sorted(),
            changedMonths = beforeSnapshots.mapNotNull { snapshot ->
                snapshot.date?.takeIf { PackedDates.isValid(it) }?.let { PackedDates.monthId(it) }
            }.toSet().sorted(),
        )
    }

    private fun referenceIsValid(reference: MergeReference): Boolean {
        if (reference.offBudgetByAccountId.keys.any { it.isEmpty() }) return false
        if (reference.destinationByPayeeId.any { it.key.isEmpty() || it.value.isEmpty() }) return false
        return reference.destinationByPayeeId.values.all { reference.offBudgetByAccountId.containsKey(it) }
    }

    private fun validateGraph(snapshots: List<TxSnapshot>, selectedId: String, reference: MergeReference): ValidatedGraph {
        val rows = LinkedHashMap<String, TxSnapshot>()
        val orderedSnapshots = snapshots.sortedBy { it.id }
        for (snapshot in orderedSnapshots) {
            if (snapshot.id.isEmpty() || rows.containsKey(snapshot.id)) blocked("A transaction in the selection is malformed.")
            rows[snapshot.id] = snapshot
        }
        val selected = rows[selectedId] ?: blocked("A selected transaction is missing.")
        if (selected.isChild == true || selected.parentId != null) {
            blocked("A split entry cannot be merged on its own. Select the whole split transaction.")
        }
        if (orderedSnapshots.isEmpty()) blocked("A selected transaction is missing.")

        val childrenByParentId = LinkedHashMap<String, MutableList<String>>()
        for (snapshot in orderedSnapshots) {
            val accountId = snapshot.accountId
            val date = snapshot.date
            if (accountId.isNullOrEmpty() || !reference.offBudgetByAccountId.containsKey(accountId) ||
                date == null || !PackedDates.isValid(date) || snapshot.amount == null ||
                snapshot.isParent == null || snapshot.isChild == null || snapshot.tombstone != false
            ) {
                if (!accountId.isNullOrEmpty() && !reference.offBudgetByAccountId.containsKey(accountId)) {
                    blocked("An account in the selection could not be found.")
                }
                blocked("A transaction in the selection is malformed.")
            }
            if (hasSplitError(snapshot.splitError)) blocked("A split in the selection has an error, so it cannot be merged.")
            if (snapshot.isParent == true && snapshot.isChild == true) blocked("A split in the selection is malformed.")
            if (snapshot.isChild == true) {
                val parentId = snapshot.parentId
                if (parentId.isNullOrEmpty() || parentId == snapshot.id) blocked("A split in the selection is malformed.")
                childrenByParentId.getOrPut(parentId) { mutableListOf() }.add(snapshot.id)
            } else if (snapshot.parentId != null) {
                blocked("A split in the selection is malformed.")
            }
        }

        for (snapshot in orderedSnapshots) {
            val children = childrenByParentId[snapshot.id] ?: emptyList<String>()
            if (snapshot.isParent == true) {
                if (children.isEmpty()) blocked("A split in the selection has no entries, so it cannot be merged.")
                if (snapshot.categoryId != null) blocked("A split in the selection is malformed.")
                if (!children.all { rows[it]?.isChild == true }) blocked("A split in the selection is malformed.")
                if (!splitIsBalanced(snapshot, children, rows)) blocked("A split in the selection does not add up, so it cannot be merged.")
            } else if (children.isNotEmpty()) {
                blocked("A split in the selection is malformed.")
            }
        }
        for (child in orderedSnapshots) {
            if (child.isChild != true) continue
            val parent = child.parentId?.let { rows[it] }
            if (parent == null || parent.isParent != true) blocked("A split in the selection is malformed.")
        }

        validateTransferLinks(rows, reference, onlyLive = false)
        val disconnectedId = disconnectedRowId(rows, childrenByParentId, selectedId)
        if (disconnectedId != null) blocked("A transaction in the selection is malformed.")
        return ValidatedGraph(rows, childrenByParentId)
    }

    private fun disconnectedRowId(
        rows: Map<String, TxSnapshot>,
        childrenByParentId: Map<String, List<String>>,
        selectedId: String,
    ): String? {
        val adjacent = HashMap<String, MutableSet<String>>()
        for ((parentId, childIds) in childrenByParentId) {
            for (childId in childIds) {
                adjacent.getOrPut(parentId) { HashSet() }.add(childId)
                adjacent.getOrPut(childId) { HashSet() }.add(parentId)
            }
        }
        for (snapshot in rows.values) {
            val peerId = snapshot.transferId ?: continue
            adjacent.getOrPut(snapshot.id) { HashSet() }.add(peerId)
            adjacent.getOrPut(peerId) { HashSet() }.add(snapshot.id)
        }
        val reachable = HashSet<String>()
        reachable.add(selectedId)
        val pending = mutableListOf(selectedId)
        while (pending.isNotEmpty()) {
            val currentId = pending.removeAt(pending.lastIndex)
            for (neighborId in (adjacent[currentId] ?: emptySet<String>()).sorted()) {
                if (reachable.add(neighborId)) pending.add(neighborId)
            }
        }
        return rows.keys.filter { it !in reachable }.sorted().firstOrNull()
    }

    /** With [onlyLive] the rows being checked are the live ones of a proposed graph. */
    private fun validateTransferLinks(rows: Map<String, TxSnapshot>, reference: MergeReference, onlyLive: Boolean) {
        val active: Map<String, TxSnapshot> = if (onlyLive) rows.filter { it.value.tombstone == false } else rows
        val ordered = active.values.sortedBy { it.id }
        val incoming = LinkedHashMap<String, MutableList<String>>()
        for (snapshot in ordered) {
            val peerId = snapshot.transferId ?: continue
            if (peerId.isEmpty()) blocked("A transfer in the selection is malformed.")
            incoming.getOrPut(peerId) { mutableListOf() }.add(snapshot.id)
        }
        for (targetId in incoming.keys.sorted()) {
            if ((incoming[targetId]?.size ?: 0) > 1) blocked("A transfer in the selection has an extra link.")
        }
        for (snapshot in ordered) {
            val peerId = snapshot.transferId ?: continue
            val peer = active[peerId]
            val accountId = snapshot.accountId
            val peerAccountId = peer?.accountId
            val amount = snapshot.amount
            if (peer == null || peerId == snapshot.id || peer.transferId != snapshot.id || accountId == null ||
                peerAccountId == null || accountId == peerAccountId || amount == null || amount == Long.MIN_VALUE ||
                peer.amount != -amount
            ) {
                blocked("A transfer in the selection is incomplete or malformed.")
            }
            val payeeId = snapshot.payeeId
            if (payeeId == null || reference.destinationByPayeeId[payeeId] != peerAccountId) {
                blocked("A transfer in the selection has no destination account.")
            }
            val peerPayeeId = peer.payeeId
            if (peerPayeeId == null || reference.destinationByPayeeId[peerPayeeId] != accountId) {
                blocked("A transfer in the selection has no destination account.")
            }
        }
    }

    private fun mergePair(
        chosen: PairMerge,
        originalRows: Map<String, TxSnapshot>,
        afterRows: MutableMap<String, TxSnapshot>,
        childrenByParentId: Map<String, List<String>>,
        transferPayeeId: String?,
        clearCategoryForOnBudgetTransfer: Boolean,
        deletedIds: MutableSet<String>,
    ): PairMerge {
        val keep = originalRows[chosen.keptId] ?: blocked("A transaction in the selection is malformed.")
        val drop = originalRows[chosen.droppedId] ?: blocked("A transaction in the selection is malformed.")
        val proposed = afterRows[chosen.keptId] ?: blocked("A transaction in the selection is malformed.")
        val keepChildren = (childrenByParentId[keep.id] ?: emptyList<String>()).sorted()
        val dropChildren = (childrenByParentId[drop.id] ?: emptyList<String>()).sorted()
        val keepWasSplit = keepChildren.isNotEmpty()
        val dropWasSplit = dropChildren.isNotEmpty()
        val adoptingSplit = !keepWasSplit && dropWasSplit

        val payeeFallback = javascriptOr(keep.payeeId, drop.payeeId)
        var categoryAfter = javascriptOr(keep.categoryId, drop.categoryId)
        val payeeAfter = transferPayeeId ?: payeeFallback
        if (adoptingSplit) categoryAfter = null
        if (clearCategoryForOnBudgetTransfer) categoryAfter = null

        if (keepWasSplit) {
            if (categoryAfter != null || !doesNotAcquireParentPayee(payeeAfter, keep.payeeId)) {
                blocked("The split transaction would take a payee or category from the other transaction, which a split cannot hold.")
            }
        } else if (adoptingSplit) {
            if (!doesNotAcquireParentPayee(payeeAfter, keep.payeeId)) {
                blocked("The split transaction would take a payee or category from the other transaction, which a split cannot hold.")
            }
        }

        afterRows[keep.id] = proposed.copy(
            payeeId = payeeAfter,
            categoryId = categoryAfter,
            notes = javascriptOr(keep.notes, drop.notes),
            cleared = javascriptOr(keep.cleared, drop.cleared),
            reconciled = javascriptOr(keep.reconciled, drop.reconciled),
            scheduleId = javascriptOr(keep.scheduleId, drop.scheduleId),
            isParent = if (adoptingSplit) true else proposed.isParent,
            isChild = if (adoptingSplit) false else proposed.isChild,
            parentId = if (adoptingSplit) null else proposed.parentId,
            splitError = if (adoptingSplit) null else proposed.splitError,
        )

        if (adoptingSplit) {
            for (childId in dropChildren) {
                val child = afterRows[childId] ?: blocked("A split in the selection is malformed.")
                afterRows[childId] = child.copy(parentId = keep.id)
            }
            tombstone(drop.id, afterRows, deletedIds)
        } else {
            tombstoneComponent(drop.id, afterRows, childrenByParentId, deletedIds)
        }
        return chosen
    }

    /** Actual keeps the imported row, then the one with an imported payee, then the earlier date. */
    private fun chooseKeepDrop(first: TxSnapshot, second: TxSnapshot): PairMerge {
        if (isTruthy(second.importedId) && !isTruthy(first.importedId)) return PairMerge(second.id, first.id)
        if (isTruthy(first.importedId) && !isTruthy(second.importedId)) return PairMerge(first.id, second.id)
        if (isTruthy(second.effectiveImportedPayee) && !isTruthy(first.effectiveImportedPayee)) {
            return PairMerge(second.id, first.id)
        }
        if (isTruthy(first.effectiveImportedPayee) && !isTruthy(second.effectiveImportedPayee)) {
            return PairMerge(first.id, second.id)
        }
        val firstDate = first.date
        val secondDate = second.date
        if (firstDate == null || secondDate == null) return PairMerge(second.id, first.id)
        if (firstDate < secondDate) return PairMerge(first.id, second.id)
        return PairMerge(second.id, first.id)
    }

    private fun tombstoneComponent(
        transactionId: String,
        afterRows: MutableMap<String, TxSnapshot>,
        childrenByParentId: Map<String, List<String>>,
        visited: MutableSet<String>,
    ) {
        if (!visited.add(transactionId)) return
        val row = afterRows[transactionId] ?: return
        afterRows[transactionId] = row.copy(tombstone = true)
        for (childId in childrenByParentId[transactionId] ?: emptyList<String>()) {
            tombstoneComponent(childId, afterRows, childrenByParentId, visited)
        }
        val peerId = row.transferId
        if (peerId != null) tombstoneComponent(peerId, afterRows, childrenByParentId, visited)
    }

    private fun tombstone(transactionId: String, afterRows: MutableMap<String, TxSnapshot>, deletedIds: MutableSet<String>) {
        val row = afterRows[transactionId] ?: return
        afterRows[transactionId] = row.copy(tombstone = true)
        deletedIds.add(transactionId)
    }

    private fun validateProposedGraph(rows: Map<String, TxSnapshot>, reference: MergeReference) {
        val childrenByParentId = LinkedHashMap<String, MutableList<String>>()
        val active = rows.filter { it.value.tombstone == false }
        val ordered = active.values.sortedBy { it.id }
        for (snapshot in ordered) {
            val accountId = snapshot.accountId
            val date = snapshot.date
            val isParent = snapshot.isParent
            val isChild = snapshot.isChild
            if (accountId.isNullOrEmpty() || !reference.offBudgetByAccountId.containsKey(accountId) ||
                date == null || !PackedDates.isValid(date) || snapshot.amount == null || isParent == null || isChild == null
            ) {
                if (!accountId.isNullOrEmpty() && !reference.offBudgetByAccountId.containsKey(accountId)) {
                    blocked("An account in the selection could not be found.")
                }
                blocked("The merged transaction would be malformed.")
            }
            if (isParent && isChild) blocked("The merged split would be malformed.")
            if (isChild) {
                val parentId = snapshot.parentId
                val parent = if (parentId.isNullOrEmpty()) null else active[parentId]
                if (parentId.isNullOrEmpty() || parent == null || parent.isParent != true) {
                    blocked("The merged split would be malformed.")
                }
                childrenByParentId.getOrPut(parentId) { mutableListOf() }.add(snapshot.id)
            } else if (snapshot.parentId != null) {
                blocked("The merged split would be malformed.")
            }
            if (isParent && snapshot.categoryId != null) {
                blocked("The split transaction would take a payee or category from the other transaction, which a split cannot hold.")
            }
            if (hasSplitError(snapshot.splitError)) blocked("The merged split would have an error.")
        }
        for (snapshot in ordered) {
            val children = childrenByParentId[snapshot.id] ?: emptyList<String>()
            if (snapshot.isParent == true) {
                if (children.isEmpty()) blocked("The merged split would have no entries.")
                if (!splitIsBalanced(snapshot, children, active)) blocked("The merged split would not add up.")
            } else if (children.isNotEmpty()) {
                blocked("The merged split would be malformed.")
            }
        }
        validateTransferLinks(rows, reference, onlyLive = true)
    }

    private fun splitIsBalanced(parent: TxSnapshot, childIds: List<String>, rows: Map<String, TxSnapshot>): Boolean {
        val parentAmount = parent.amount ?: return false
        var total = 0L
        for (childId in childIds) {
            val amount = rows[childId]?.amount ?: return false
            try {
                total = Math.addExact(total, amount)
            } catch (overflow: ArithmeticException) {
                return false
            }
        }
        return total == parentAmount
    }

    private fun hasSplitError(value: String?): Boolean = !value.isNullOrEmpty() && value != "null"

    /** JavaScript string truthiness for the source's `||` merge expressions. */
    private fun isTruthy(value: String?): Boolean = !value.isNullOrEmpty()

    private fun javascriptOr(first: String?, second: String?): String? = if (isTruthy(first)) first else second

    /** Preserves the operand JavaScript returns: `false || null` is null, `false || false` is false. */
    private fun javascriptOr(first: Boolean?, second: Boolean?): Boolean? = if (first == true) first else second

    /** A split parent keeps its own payee but cannot acquire a truthy one from a dropped simple row. */
    private fun doesNotAcquireParentPayee(proposed: String?, kept: String?): Boolean = proposed == kept || !isTruthy(proposed)
}
