package com.sporez.actualist.core.server

import androidx.sqlite.SQLiteDriver
import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.Sql
import java.io.File
import java.util.UUID

/**
 * Budget ZIP export and import-as-new, as Actualist's portable archive does. Export takes a
 * consistent snapshot of the open file (never a copy of the live file), strips local-only tables
 * with the import sanitizer, checks integrity and zips it with `metadata.json` under a fresh id.
 * An imported archive has its sync history reset like Actual's `resetSync` before it is
 * registered as a new server budget, so the new group starts from an empty history.
 */
object PortableBudget {
    private val tombstoneTables = listOf("transactions", "accounts", "payees", "categories", "category_groups", "schedules", "rules")

    fun exportArchive(
        database: BudgetDatabase,
        budgetName: String,
        driver: SQLiteDriver,
        scratchDirectory: File,
        newId: () -> String = { UUID.randomUUID().toString() },
    ): ByteArray {
        scratchDirectory.mkdirs()
        val file = File(scratchDirectory, "portable-export-${UUID.randomUUID()}.sqlite")
        try {
            database.sql.execute("VACUUM INTO ?", file.absolutePath)
            BudgetImporter.sanitize(file, driver)
            val sql = Sql(driver.open(file.absolutePath))
            try {
                val integrity = sql.query("PRAGMA integrity_check").mapNotNull { row -> row.string("integrity_check") }
                if (integrity != listOf("ok")) throw LocalFirstException.InvalidDownloadedBudget("the snapshot failed its integrity check")
            } finally {
                sql.close()
            }
            return NewBudget.archive(file, newId(), budgetName)
        } finally {
            file.delete()
            File(file.path + "-journal").delete()
        }
    }

    /** Port of Actualist `resetSyncHistory` (upstream `resetSync`) on a staged, not yet opened file. */
    fun resetSyncHistory(file: File, driver: SQLiteDriver) {
        val sql = Sql(driver.open(file.absolutePath))
        try {
            sql.execute("PRAGMA trusted_schema = OFF")
            sql.transaction {
                val tables = sql.query("SELECT name FROM sqlite_master WHERE type = 'table'").mapNotNull { it.string("name") }.toSet()
                for (table in listOf("messages_pending", "messages_crdt", "messages_clock")) {
                    if (table in tables) sql.execute("DELETE FROM ${Sql.quoted(table)}")
                }
                for (table in tombstoneTables) {
                    if (table !in tables) continue
                    val columns = sql.query("PRAGMA table_info(${Sql.quoted(table)})").mapNotNull { it.string("name") }
                    if ("tombstone" in columns) sql.execute("DELETE FROM ${Sql.quoted(table)} WHERE tombstone = 1")
                }
            }
            sql.execute("ANALYZE")
            sql.execute("VACUUM")
        } finally {
            sql.close()
        }
    }

    /** The archive's `metadata.json` budget name, if it has one. */
    fun budgetName(zipBytes: ByteArray): String? = runCatching {
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(zipBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name.replace('\\', '/') == "metadata.json") {
                    val text = zip.readBytes().toString(Charsets.UTF_8)
                    val root = kotlinx.serialization.json.Json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject
                    return@use (root?.get("budgetName") as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                }
            }
            null
        }
    }.getOrNull()
}
