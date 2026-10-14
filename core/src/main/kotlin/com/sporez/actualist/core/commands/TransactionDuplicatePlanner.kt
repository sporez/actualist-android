package com.sporez.actualist.core.commands

import com.sporez.actualist.core.LocalFirstException

/** One cloned row: the source it copies, the id and the sort order the copy takes. */
internal data class DuplicateAllocation(val sourceId: String, val duplicateId: String, val sortOrder: Double)

internal data class DuplicatePlan(
    val allocations: List<DuplicateAllocation>,
    /** The cloned rows, sorted by their new ids. */
    val afterSnapshots: List<TxSnapshot>,
) {
    val sourceToDuplicate: Map<String, String> get() = allocations.associate { it.sourceId to it.duplicateId }
}

/**
 * Pure duplicate planner. Port of Actualist `TransactionDuplicatePlanner`: checks that the
 * supplied closure of source rows is self-consistent, then builds the cloned graph (new ids,
 * links remapped, cleared and reconciled reset) and validates its links. The caller loads the
 * closure and checks the allocated ids against rows outside it.
 */
internal object TransactionDuplicatePlanner {
    private const val REFUSED = "the selected transaction graph cannot be duplicated"

    private fun refuse(): Nothing = throw LocalFirstException.InvalidLocalWrite(REFUSED)

    private class SplitUnits(val unitByTransactionId: Map<String, String>, val membersByUnitId: Map<String, Set<String>>)

    fun plan(
        selectedIds: List<String>,
        sourceSnapshots: List<TxSnapshot>,
        allocations: List<DuplicateAllocation>,
    ): DuplicatePlan {
        if (selectedIds.isEmpty() || sourceSnapshots.isEmpty()) refuse()

        val snapshotsById = LinkedHashMap<String, TxSnapshot>()
        for (snapshot in sourceSnapshots) {
            if (snapshot.id.isEmpty() || snapshotsById.containsKey(snapshot.id)) refuse()
            snapshotsById[snapshot.id] = snapshot
        }
        validateSourceRows(sourceSnapshots)

        val units = makeSplitUnits(sourceSnapshots, snapshotsById)
        validateTransferLinks(sourceSnapshots, snapshotsById, units.unitByTransactionId)

        val uniqueSelections = LinkedHashSet<String>()
        for (id in selectedIds) {
            if (id.isEmpty() || !snapshotsById.containsKey(id)) refuse()
            uniqueSelections += id
        }

        val groupIdByTransactionId = HashMap<String, String>()
        for (selectedId in uniqueSelections) {
            val sourceGroup = completeDuplicateGroup(selectedId, snapshotsById, units)
            val rootIds = sourceGroup.filter { units.unitByTransactionId[it] == it }
            val groupId = rootIds.minOrNull() ?: sourceGroup.minOrNull() ?: refuse()
            for (memberId in sourceGroup) {
                val previous = groupIdByTransactionId[memberId]
                if (previous != null && previous != groupId) refuse()
                groupIdByTransactionId[memberId] = groupId
            }
        }

        val selectedSourceIds = groupIdByTransactionId.keys
        if (snapshotsById.keys.any { it !in selectedSourceIds }) refuse()

        val canonicalAllocations = validateAllocations(allocations, selectedSourceIds, snapshotsById)
        val allocationBySourceId = canonicalAllocations.associateBy { it.sourceId }
        val afterSnapshots = sourceSnapshots.sortedBy { it.id }
            .map { source ->
                val allocation = allocationBySourceId[source.id] ?: refuse()
                duplicateSnapshot(source, allocation, allocationBySourceId)
            }
            .sortedBy { it.id }
        validateProposedLinks(afterSnapshots)
        return DuplicatePlan(canonicalAllocations, afterSnapshots)
    }

    private fun validateSourceRows(snapshots: List<TxSnapshot>) {
        for (snapshot in snapshots) {
            if (snapshot.tombstone == true) refuse()
            if (snapshot.accountId.isNullOrEmpty()) refuse()
            val date = snapshot.date
            if (date == null || !PackedDates.isValid(date)) refuse()
            if (snapshot.amount == null) refuse()
            val sortOrder = snapshot.sortOrder
            if (sortOrder != null && !sortOrder.isFinite()) refuse()
            val error = snapshot.splitError
            if (!error.isNullOrEmpty() && error != "null") refuse()
        }
    }

    private fun makeSplitUnits(snapshots: List<TxSnapshot>, snapshotsById: Map<String, TxSnapshot>): SplitUnits {
        for (snapshot in snapshots) {
            if (snapshot.isParent == true && snapshot.isChild == true) refuse()
            val parentId = snapshot.parentId
            if (parentId != null) {
                val parent = snapshotsById[parentId]
                if (parentId.isEmpty() || parentId == snapshot.id || snapshot.isChild != true ||
                    parent == null || parent.isParent != true || parent.isChild == true
                ) {
                    refuse()
                }
            } else if (snapshot.isChild == true) {
                refuse()
            }
        }

        val membersByUnitId = HashMap<String, Set<String>>()
        val unitByTransactionId = HashMap<String, String>()
        for (parent in snapshots) {
            if (parent.isParent != true) continue
            val children = snapshots.filter { it.parentId == parent.id }
            if (children.isEmpty()) refuse()
            val allChildrenFit = children.all {
                it.isChild == true && it.parentId == parent.id && it.accountId == parent.accountId && it.date == parent.date
            }
            if (!allChildrenFit) refuse()
            val parentAmount = parent.amount ?: refuse()
            var total = 0L
            for (child in children) total += child.amount ?: refuse()
            if (total != parentAmount) refuse()
            val members = HashSet<String>()
            members += parent.id
            children.forEach { members += it.id }
            if (members.size != children.size + 1) refuse()
            membersByUnitId[parent.id] = members
            for (memberId in members) unitByTransactionId[memberId] = parent.id
        }
        for (snapshot in snapshots) {
            if (unitByTransactionId[snapshot.id] == null) {
                unitByTransactionId[snapshot.id] = snapshot.id
                membersByUnitId[snapshot.id] = setOf(snapshot.id)
            }
        }
        return SplitUnits(unitByTransactionId, membersByUnitId)
    }

    private fun validateTransferLinks(
        snapshots: List<TxSnapshot>,
        snapshotsById: Map<String, TxSnapshot>,
        unitByTransactionId: Map<String, String>,
    ) {
        val incomingIds = HashMap<String, MutableSet<String>>()
        for (snapshot in snapshots) {
            val transferId = snapshot.transferId ?: continue
            val paired = snapshotsById[transferId]
            val accountId = snapshot.accountId
            val pairedAccountId = paired?.accountId
            val amount = snapshot.amount
            if (transferId.isEmpty() || transferId == snapshot.id || paired == null || paired.transferId != snapshot.id ||
                paired.tombstone == true || accountId == null || pairedAccountId == null || accountId == pairedAccountId ||
                amount == null || amount == Long.MIN_VALUE || paired.amount != -amount ||
                unitByTransactionId[transferId] == null
            ) {
                refuse()
            }
            incomingIds.getOrPut(transferId) { HashSet() } += snapshot.id
        }
        for (snapshot in snapshots) {
            val expected: Set<String> = snapshot.transferId?.let { setOf(it) } ?: emptySet()
            val incoming: Set<String> = incomingIds[snapshot.id] ?: emptySet()
            if (incoming != expected) refuse()
        }
    }

    private fun completeDuplicateGroup(
        selectedId: String,
        snapshotsById: Map<String, TxSnapshot>,
        units: SplitUnits,
    ): Set<String> {
        val firstUnitId = units.unitByTransactionId[selectedId] ?: refuse()
        val pendingUnitIds = mutableListOf(firstUnitId)
        val visitedUnitIds = HashSet<String>()
        val members = HashSet<String>()
        while (pendingUnitIds.isNotEmpty()) {
            val unitId = pendingUnitIds.removeAt(pendingUnitIds.lastIndex)
            if (!visitedUnitIds.add(unitId)) continue
            val unitMembers = units.membersByUnitId[unitId]
            if (unitMembers.isNullOrEmpty()) refuse()
            members += unitMembers
            for (memberId in unitMembers) {
                val snapshot = snapshotsById[memberId] ?: refuse()
                val transferId = snapshot.transferId
                if (transferId != null) {
                    pendingUnitIds += units.unitByTransactionId[transferId] ?: refuse()
                }
            }
        }
        return members
    }

    private fun validateAllocations(
        allocations: List<DuplicateAllocation>,
        sourceIds: Set<String>,
        snapshotsById: Map<String, TxSnapshot>,
    ): List<DuplicateAllocation> {
        val bySourceId = HashMap<String, DuplicateAllocation>()
        val duplicateIds = HashSet<String>()
        val allocatedOrders = HashSet<Double>()
        val sourceOrders = snapshotsById.values.mapNotNull { it.sortOrder }.toSet()
        for (allocation in allocations) {
            if (bySourceId.containsKey(allocation.sourceId)) refuse()
            if (allocation.sourceId !in sourceIds) refuse()
            if (allocation.duplicateId.isEmpty()) refuse()
            if (!duplicateIds.add(allocation.duplicateId)) refuse()
            if (snapshotsById.containsKey(allocation.duplicateId)) refuse()
            if (!allocation.sortOrder.isFinite()) refuse()
            if (allocation.sortOrder in sourceOrders) refuse()
            if (!allocatedOrders.add(allocation.sortOrder)) refuse()
            bySourceId[allocation.sourceId] = allocation
        }
        if (sourceIds.any { it !in bySourceId }) refuse()
        return bySourceId.values.sortedBy { it.sourceId }
    }

    private fun duplicateSnapshot(
        source: TxSnapshot,
        allocation: DuplicateAllocation,
        allocationBySourceId: Map<String, DuplicateAllocation>,
    ): TxSnapshot {
        val parentId = source.parentId?.let { (allocationBySourceId[it] ?: refuse()).duplicateId }
        val transferId = source.transferId?.let { (allocationBySourceId[it] ?: refuse()).duplicateId }
        return source.copy(
            id = allocation.duplicateId,
            cleared = false,
            reconciled = false,
            tombstone = false,
            parentId = parentId,
            transferId = transferId,
            sortOrder = allocation.sortOrder,
        )
    }

    private fun validateProposedLinks(snapshots: List<TxSnapshot>) {
        val byId = snapshots.associateBy { it.id }
        for (snapshot in snapshots) {
            if (snapshot.cleared != false || snapshot.reconciled != false) refuse()
            val parentId = snapshot.parentId
            if (parentId != null) {
                val parent = byId[parentId]
                if (parentId == snapshot.id || parent == null || parent.isParent != true || snapshot.isChild != true) refuse()
            }
            if (snapshot.isParent == true) {
                if (snapshots.none { it.parentId == snapshot.id && it.isChild == true }) refuse()
            }
            val transferId = snapshot.transferId
            if (transferId != null) {
                val paired = byId[transferId]
                if (transferId == snapshot.id || paired == null || paired.transferId != snapshot.id) refuse()
            }
        }
    }
}
