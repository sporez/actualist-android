package com.sporez.actualist.core.transactions

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.CellDraft
import com.sporez.actualist.core.sync.SyncMessage
import com.sporez.actualist.core.sync.SyncValue

data class PayeeUsage(val id: String, val name: String, val transactionCount: Int)

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
