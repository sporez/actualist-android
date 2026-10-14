package com.sporez.actualist.core.transactions

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.CellDraft
import com.sporez.actualist.core.rules.RuleEngine
import com.sporez.actualist.core.rules.RulePayees
import com.sporez.actualist.core.sync.SyncMessage
import com.sporez.actualist.core.sync.SyncValue
import java.util.UUID

data class PayeeUsage(val id: String, val name: String, val transactionCount: Int)

/** A payee on the management page. A transfer payee shows its account's name and is read-only. */
data class PayeeEntry(
    val id: String,
    val name: String,
    val isTransfer: Boolean,
    val transactionCount: Int,
    val ruleCount: Int,
    val favorite: Boolean,
    val learnsCategories: Boolean,
) {
    val isUnused: Boolean get() = !isTransfer && transactionCount == 0 && ruleCount == 0
}

/** [unreadableRules] blocks deleting, since an undecodable rule might still reference a payee. */
data class PayeeOverview(val payees: List<PayeeEntry>, val unreadableRules: Boolean) {
    fun canDelete(entry: PayeeEntry): Boolean = entry.isUnused && !unreadableRules
}

/**
 * Payee rename and merge, following Actual's `mergePayees` as Actualist ports it: a merge
 * points every `payee_mapping` row of the merged payees (and the payees themselves) at the
 * target and tombstones the merged payees. Transactions keep their payee id; reads resolve
 * it through `payee_mapping`. Transfer payees are never renamed or merged.
 */
class PayeeWrites(private val database: BudgetDatabase) {
    private val sql get() = database.sql

    fun usage(): List<PayeeUsage> = sql.query(
        """
        SELECT p.id AS id, p.name AS name,
               (SELECT COUNT(*) FROM transactions t LEFT JOIN payee_mapping pm ON pm.id = t.description
                WHERE COALESCE(pm.targetId, t.description) = p.id AND (t.tombstone IS NULL OR t.tombstone = 0)) AS count
        FROM payees p
        WHERE (p.tombstone IS NULL OR p.tombstone = 0) AND (p.transfer_acct IS NULL OR p.transfer_acct = '') AND p.name IS NOT NULL AND p.name != ''
        ORDER BY lower(p.name)
        """.trimIndent(),
    ).map { PayeeUsage(it.string("id")!!, it.string("name") ?: "", it.longOr0("count").toInt()) }

    /** Every live payee with usage, rule references and switches; transfer payees come named after their account. */
    fun overview(): PayeeOverview {
        if (!database.tableExists("payees")) return PayeeOverview(emptyList(), false)
        val columns = database.columnSet("payees")
        val favorite = if ("favorite" in columns) "p.favorite" else "0"
        val learns = if ("learn_categories" in columns) "p.learn_categories" else "1"
        val transfer = if ("transfer_acct" in columns) "p.transfer_acct" else "NULL"
        val counts = HashMap<String, Int>()
        for (row in sql.query(
            "SELECT COALESCE(pm.targetId, t.description) AS payee, COUNT(*) AS count FROM transactions t " +
                "LEFT JOIN payee_mapping pm ON pm.id = t.description WHERE (t.tombstone IS NULL OR t.tombstone = 0) AND t.description IS NOT NULL GROUP BY 1",
        )) {
            row.string("payee")?.let { counts[it] = row.longOr0("count").toInt() }
        }
        val rules = RuleEngine(database).fetchRules().filter { !it.isCompletedScheduleRule }
        val unreadable = rules.any { RulePayees.referencedIds(it) == null }
        val ruleCounts = HashMap<String, Int>()
        for (rule in rules) RulePayees.referencedIds(rule)?.forEach { ruleCounts[it] = (ruleCounts[it] ?: 0) + 1 }
        val rows = sql.query(
            "SELECT p.id AS id, p.name AS name, $transfer AS transfer, $favorite AS favorite, $learns AS learns, a.name AS account_name " +
                "FROM payees p LEFT JOIN accounts a ON a.id = $transfer WHERE (p.tombstone IS NULL OR p.tombstone = 0)",
        )
        val entries = rows.mapNotNull { row ->
            val id = row.string("id") ?: return@mapNotNull null
            val isTransfer = !row.string("transfer").isNullOrEmpty()
            val name = if (isTransfer) row.string("account_name").orEmpty() else row.string("name").orEmpty()
            if (name.isEmpty()) return@mapNotNull null
            PayeeEntry(id, name, isTransfer, counts[id] ?: 0, ruleCounts[id] ?: 0, row.bool("favorite"), row["learns"]?.let { row.bool("learns") } ?: true)
        }.sortedWith(compareBy({ it.isTransfer }, { it.name.lowercase() }))
        return PayeeOverview(entries, unreadable)
    }

    /** Actual's `insertPayee`: the payee and its own `payee_mapping` row. */
    fun create(name: String, newId: () -> String = { UUID.randomUUID().toString() }): Pair<String, List<SyncMessage>> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw LocalFirstException.InvalidLocalWrite("missing payee name")
        val id = newId()
        val messages = database.commitLocal {
            val duplicate = sql.queryOne(
                "SELECT id FROM payees WHERE lower(name) = lower(?) AND (tombstone IS NULL OR tombstone = 0) AND (transfer_acct IS NULL OR transfer_acct = '')",
                trimmed,
            )
            if (duplicate != null) throw LocalFirstException.InvalidLocalWrite("a payee named $trimmed already exists")
            listOf(
                CellDraft("payees", id, "name", SyncValue.text(trimmed)),
                CellDraft("payee_mapping", id, "targetId", SyncValue.text(id)),
            ) to Unit
        }.messages
        return id to messages
    }

    /** Actual's `deletePayee`: tombstones the payee. Only unused payees no rule references may go; transfer payees never. */
    fun delete(payeeId: String): List<SyncMessage> = database.commitLocal {
        requireRegular(payeeId)
        val entry = overview().let { overview -> overview.payees.firstOrNull { it.id == payeeId }?.takeIf { overview.canDelete(it) } }
            ?: throw LocalFirstException.InvalidLocalWrite("only unused payees without rule references can be deleted")
        listOf(CellDraft("payees", entry.id, "tombstone", SyncValue.bool(true))) to Unit
    }.messages

    fun setFavorite(payeeId: String, favorite: Boolean): List<SyncMessage> = database.commitLocal {
        requireRegular(payeeId)
        listOf(CellDraft("payees", payeeId, "favorite", SyncValue.bool(favorite))) to Unit
    }.messages

    fun rename(payeeId: String, name: String): List<SyncMessage> = database.commitLocal {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw LocalFirstException.InvalidLocalWrite("missing payee name")
        val current = requireRegular(payeeId)
        if (current == trimmed) return@commitLocal emptyList<CellDraft>() to Unit
        listOf(CellDraft("payees", payeeId, "name", SyncValue.text(trimmed))) to Unit
    }.messages

    fun merge(sourceIds: Set<String>, targetId: String): List<SyncMessage> = database.commitLocal {
        if (sourceIds.isEmpty() || targetId in sourceIds) throw LocalFirstException.InvalidLocalWrite("choose at least one different payee to merge")
        requireRegular(targetId)
        sourceIds.forEach(::requireRegular)
        val placeholders = sourceIds.joinToString(",") { "?" }
        val mappingIds = sql.query("SELECT id FROM payee_mapping WHERE targetId IN ($placeholders)", *sourceIds.toTypedArray())
            .mapNotNull { it.string("id") }.toSortedSet()
        mappingIds.addAll(sourceIds)
        val drafts = mappingIds.map { CellDraft("payee_mapping", it, "targetId", SyncValue.text(targetId)) } +
            sourceIds.sorted().map { CellDraft("payees", it, "tombstone", SyncValue.bool(true)) }
        drafts to Unit
    }.messages

    private fun requireRegular(payeeId: String): String {
        val row = sql.queryOne("SELECT name, transfer_acct FROM payees WHERE id = ? AND (tombstone IS NULL OR tombstone = 0)", payeeId)
            ?: throw LocalFirstException.InvalidLocalWrite("missing payee")
        if (!row.string("transfer_acct").isNullOrEmpty()) throw LocalFirstException.InvalidLocalWrite("transfer payees cannot be changed")
        return row.string("name") ?: ""
    }
}
