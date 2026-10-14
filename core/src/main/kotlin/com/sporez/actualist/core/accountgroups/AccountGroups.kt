package com.sporez.actualist.core.accountgroups

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.budget.SortOrder
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.CellDraft
import com.sporez.actualist.core.db.Clock
import com.sporez.actualist.core.sync.SyncValue
import java.util.UUID

data class AccountGroup(val id: String, val name: String, val sortOrder: Double)

/**
 * Account group reads and writes, ported from Actualist `BudgetDatabase+AccountGroupWrites`.
 * Every write is one atomic [BudgetDatabase.commitLocal] of per-column cell drafts.
 */
class AccountGroups(
    private val database: BudgetDatabase,
    private val clock: Clock = Clock.SYSTEM,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val sql get() = database.sql

    /** Live groups ordered by sort order, then id. */
    fun groups(): List<AccountGroup> {
        if (!database.tableExists("account_groups")) return emptyList()
        return liveGroupRows().map { AccountGroup(it.id, it.name, it.sortOrder) }
    }

    /** Live accounts mapped to their group id (null when ungrouped). */
    fun accountGroupIds(): Map<String, String?> {
        if (!database.tableExists("accounts") || "account_group_id" !in database.columnSet("accounts")) return emptyMap()
        val predicate = BudgetDatabase.livePredicate(database.columnSet("accounts"))
        val result = LinkedHashMap<String, String?>()
        for (row in sql.query("SELECT id, account_group_id FROM accounts WHERE $predicate ORDER BY id")) {
            val id = row.string("id") ?: continue
            result[id] = row.string("account_group_id")?.takeIf { it.isNotEmpty() }
        }
        return result
    }

    fun create(name: String): String {
        val id = newId()
        database.commitLocal { createDrafts(id, name) to Unit }
        return id
    }

    fun rename(id: String, name: String) {
        database.commitLocal { renameDrafts(id.trim(), name) to Unit }
    }

    /** Ungroups every live member account, then tombstones the group. */
    fun delete(id: String) {
        database.commitLocal { deleteDrafts(id.trim()) to Unit }
    }

    fun moveAccount(accountId: String, groupId: String?) {
        database.commitLocal { moveAccountDrafts(accountId, groupId) to Unit }
    }

    /** Moves [id] before [beforeId], or to the end when [beforeId] is null. */
    fun moveGroup(id: String, beforeId: String?) {
        database.commitLocal { moveGroupDrafts(id, beforeId) to Unit }
    }

    private fun createDrafts(groupId: String, name: String): List<CellDraft> {
        val trimmedId = groupId.trim()
        val trimmedName = validatedName(name)
        if (trimmedId.isEmpty()) throw LocalFirstException.InvalidLocalWrite("missing account group")
        val columns = database.requiredColumns("account_groups", listOf("name"))
        if (database.rowExists("account_groups", trimmedId)) {
            throw LocalFirstException.InvalidLocalWrite("account group already exists")
        }
        rejectDuplicateName(trimmedName, null)
        val drafts = mutableListOf<CellDraft>()
        drafts.add(CellDraft("account_groups", trimmedId, "name", SyncValue.text(trimmedName)))
        if ("tombstone" in columns) {
            drafts.add(CellDraft("account_groups", trimmedId, "tombstone", SyncValue.bool(false)))
        }
        if ("sort_order" in columns) {
            drafts.add(CellDraft("account_groups", trimmedId, "sort_order", SyncValue.Real(nextSortOrder(columns))))
        }
        return drafts
    }

    private fun renameDrafts(groupId: String, name: String): List<CellDraft> {
        val trimmedName = validatedName(name)
        val group = requiredLiveGroup(groupId)
        if (group.name == trimmedName) return emptyList()
        rejectDuplicateName(trimmedName, groupId)
        return listOf(CellDraft("account_groups", groupId, "name", SyncValue.text(trimmedName)))
    }

    private fun deleteDrafts(groupId: String): List<CellDraft> {
        requiredLiveGroup(groupId)
        val accountColumns = database.columnSet("accounts")
        if ("account_group_id" !in accountColumns) {
            throw LocalFirstException.InvalidLocalWrite("missing column accounts.account_group_id")
        }
        // Without the tombstone the group would stay live while its members were ungrouped.
        database.requiredColumns("account_groups", listOf("tombstone"))
        val predicate = BudgetDatabase.livePredicate(accountColumns)
        val drafts = mutableListOf<CellDraft>()
        val members = sql.query("SELECT id FROM accounts WHERE account_group_id = ? AND $predicate ORDER BY id", groupId)
        for (member in members) {
            val memberId = member.string("id") ?: continue
            drafts.add(CellDraft("accounts", memberId, "account_group_id", SyncValue.Null))
        }
        drafts.add(CellDraft("account_groups", groupId, "tombstone", SyncValue.bool(true)))
        return drafts
    }

    private fun moveAccountDrafts(accountId: String, groupId: String?): List<CellDraft> {
        val accountColumns = database.requiredColumns("accounts", listOf("account_group_id"))
        if (!database.liveRowExists("accounts", accountId)) throw LocalFirstException.InvalidLocalWrite("missing account")
        if (groupId != null) requiredLiveGroup(groupId)
        val predicate = BudgetDatabase.livePredicate(accountColumns)
        val current = sql.queryOne(
            "SELECT account_group_id FROM accounts WHERE id = ? AND $predicate LIMIT 1",
            accountId,
        )?.string("account_group_id")?.takeIf { it.isNotEmpty() }
        if (current == groupId) return emptyList()
        return listOf(CellDraft("accounts", accountId, "account_group_id", SyncValue.textOrNull(groupId)))
    }

    private fun moveGroupDrafts(groupId: String, beforeId: String?): List<CellDraft> {
        requiredLiveGroup(groupId)
        if (beforeId != null) requiredLiveGroup(beforeId)
        val groups = liveGroupRows()
        val result = SortOrder.shove(groups.map { SortOrder.Item(it.id, it.sortOrder) }, beforeId)
        val drafts = mutableListOf<CellDraft>()
        for (update in result.updates) {
            if (update.id == groupId) continue
            drafts.add(CellDraft("account_groups", update.id, "sort_order", SyncValue.Real(update.sortOrder)))
        }
        if (groups.firstOrNull { it.id == groupId }?.sortOrder != result.sortOrder) {
            drafts.add(CellDraft("account_groups", groupId, "sort_order", SyncValue.Real(result.sortOrder)))
        }
        return drafts
    }

    private fun rejectDuplicateName(name: String, excluding: String?) {
        val columns = database.columnSet("account_groups")
        val predicate = BudgetDatabase.livePredicate(columns)
        val existing = if (excluding == null) {
            sql.queryOne("SELECT name FROM account_groups WHERE $predicate AND name = ? COLLATE NOCASE LIMIT 1", name)
        } else {
            sql.queryOne(
                "SELECT name FROM account_groups WHERE $predicate AND name = ? COLLATE NOCASE AND id <> ? LIMIT 1",
                name,
                excluding,
            )
        }
        val existingName = existing?.string("name") ?: return
        throw LocalFirstException.InvalidLocalWrite("An '$existingName' account group already exists.")
    }

    private fun requiredLiveGroup(groupId: String): GroupRow {
        val columns = database.requiredColumns("account_groups", listOf("name"))
        val predicate = BudgetDatabase.livePredicate(columns)
        val row = sql.queryOne("SELECT id, name FROM account_groups WHERE id = ? AND $predicate LIMIT 1", groupId)
            ?: throw LocalFirstException.InvalidLocalWrite("account group no longer exists")
        return GroupRow(row.string("id") ?: groupId, row.string("name") ?: "", 0.0)
    }

    private fun nextSortOrder(columns: Set<String>): Double {
        if ("sort_order" !in columns) return SortOrder.INCREMENT
        val predicate = BudgetDatabase.livePredicate(columns)
        val max = sql.queryOne("SELECT MAX(sort_order) AS sort_order FROM account_groups WHERE $predicate")?.double("sort_order") ?: 0.0
        return max + SortOrder.INCREMENT
    }

    private fun liveGroupRows(): List<GroupRow> {
        val columns = database.requiredColumns("account_groups", listOf("id"))
        val predicate = BudgetDatabase.livePredicate(columns)
        val name = if ("name" in columns) "name" else "''"
        val sortOrder = if ("sort_order" in columns) "sort_order" else "0"
        val rows = sql.query("SELECT id, $name AS name, $sortOrder AS sort_order FROM account_groups WHERE $predicate ORDER BY sort_order, id")
        val result = mutableListOf<GroupRow>()
        for (row in rows) {
            val id = row.string("id")
            if (id.isNullOrEmpty()) continue
            result.add(GroupRow(id, row.string("name") ?: "", row.double("sort_order") ?: 0.0))
        }
        return result
    }

    private fun validatedName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw LocalFirstException.InvalidLocalWrite("account group name cannot be empty")
        return trimmed
    }

    private class GroupRow(val id: String, val name: String, val sortOrder: Double)
}
