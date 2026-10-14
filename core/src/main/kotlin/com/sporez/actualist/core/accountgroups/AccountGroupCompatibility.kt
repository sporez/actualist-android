package com.sporez.actualist.core.accountgroups

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.Sql
import com.sporez.actualist.core.sync.SyncValue

/**
 * Account group schema compatibility, ported from Actualist
 * `BudgetDatabase+AccountGroupCompatibility`.
 *
 * Old imports may carry account group CRDT without the physical table or column. The
 * schema is ensured on every call (cheap, and required before any read of `accounts` or
 * `account_groups`); the historical `messages_crdt` replay runs once per file.
 */
object AccountGroupCompatibility {
    const val MIGRATION = "account-group-compatibility-v1"
    const val UPSTREAM_MIGRATION_ID = 1_787_013_118_115L

    private val upstreamGroupColumns = mapOf(
        "id" to "text",
        "name" to "text",
        "sort_order" to "real",
        "tombstone" to "integer",
    )

    fun prepare(database: BudgetDatabase) {
        val sql = database.sql
        sql.transaction {
            if (!tableExists(sql, "accounts")) return@transaction
            val groupsTableExisted = tableExists(sql, "account_groups")
            sql.execute(
                "CREATE TABLE IF NOT EXISTS account_groups " +
                    "(id TEXT PRIMARY KEY, name TEXT, sort_order REAL, tombstone INTEGER DEFAULT 0)",
            )
            val accountColumns = columnNames(sql, "accounts")
            val addedGroupColumn = "account_group_id" !in accountColumns
            if (addedGroupColumn) {
                sql.execute("ALTER TABLE accounts ADD COLUMN account_group_id TEXT DEFAULT NULL")
            }
            database.invalidateSchemaCaches()
            if (addedGroupColumn || !groupsTableExisted) recordUpstreamMigrationId(sql)

            if (database.localMigrationApplied(MIGRATION)) return@transaction
            if (!tableExists(sql, "messages_crdt")) {
                database.recordLocalMigration(MIGRATION)
                return@transaction
            }

            val groupColumns = columnNames(sql, "account_groups")
            val rows = sql.query(
                "SELECT dataset, \"row\" AS row_id, \"column\" AS column_name, value FROM messages_crdt " +
                    "WHERE dataset = 'account_groups' OR (dataset = 'accounts' AND \"column\" = 'account_group_id') " +
                    "ORDER BY timestamp",
            )
            for (entry in rows) {
                val dataset = entry.string("dataset") ?: continue
                val rowId = entry.string("row_id") ?: continue
                val column = entry.string("column_name") ?: continue
                val serialized = entry.string("value") ?: continue
                if (rowId.isEmpty() || column.isEmpty()) continue
                val value = try {
                    SyncValue.deserialize(serialized)
                } catch (error: LocalFirstException) {
                    continue
                }
                if (dataset == "account_groups") {
                    if (column !in groupColumns) continue
                    val exists = sql.queryOne("SELECT id FROM account_groups WHERE id = ? LIMIT 1", rowId) != null
                    applyValue(sql, "account_groups", column, rowId, exists, value)
                } else {
                    if (dataset != "accounts" || column != "account_group_id") continue
                    if (sql.queryOne("SELECT id FROM accounts WHERE id = ? LIMIT 1", rowId) == null) continue
                    applyValue(sql, "accounts", column, rowId, true, value)
                }
            }
            database.recordLocalMigration(MIGRATION)
        }
    }

    private fun applyValue(sql: Sql, table: String, column: String, rowId: String, rowExists: Boolean, value: SyncValue) {
        val tableSql = Sql.quoted(table)
        val columnSql = Sql.quoted(column)
        if (rowExists) {
            sql.execute("UPDATE $tableSql SET $columnSql = ? WHERE id = ?", value.sqlValue, rowId)
        } else {
            sql.execute("INSERT INTO $tableSql (id, $columnSql) VALUES (?, ?)", rowId, value.sqlValue)
        }
    }

    private fun tableExists(sql: Sql, table: String): Boolean =
        sql.queryOne("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", table) != null

    private fun columnNames(sql: Sql, table: String): Set<String> =
        sql.query("PRAGMA table_info(${Sql.quoted(table)})").mapNotNull { it.string("name") }.toSet()

    /** Records upstream's migration id when the physical schema equals upstream's, as Swift does. */
    private fun recordUpstreamMigrationId(sql: Sql) {
        if (!tableExists(sql, "__migrations__")) return
        val groupTypes = declaredTypes(sql, "account_groups")
        val accountTypes = declaredTypes(sql, "accounts")
        if (groupTypes != upstreamGroupColumns || accountTypes["account_group_id"] != "text") return
        sql.execute("INSERT OR IGNORE INTO __migrations__ (id) VALUES (?)", UPSTREAM_MIGRATION_ID)
    }

    private fun declaredTypes(sql: Sql, table: String): Map<String, String> {
        val result = HashMap<String, String>()
        for (entry in sql.query("PRAGMA table_info(${Sql.quoted(table)})")) {
            val name = entry.string("name") ?: continue
            result[name.lowercase()] = (entry.string("type") ?: "").lowercase()
        }
        return result
    }
}
