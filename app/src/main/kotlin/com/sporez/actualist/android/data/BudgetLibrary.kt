package com.sporez.actualist.android.data

import android.content.Context
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.demo.DemoBudget
import com.sporez.actualist.core.store.BudgetSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

/** Local metadata for one budget file on this device. */
@Serializable
data class LocalBudget(
    val id: String,
    val name: String,
    val isDemo: Boolean,
    val cloudFileId: String? = null,
    val groupId: String? = null,
    val encryptKeyId: String? = null,
    val nodeId: String,
    val lastSyncedAtMillis: Long? = null,
)

/**
 * Budget files under `files/budgets/<id>/`: `db.sqlite` plus `metadata.json`. The demo
 * budget is generated on first use and can be reset at any time.
 */
class BudgetLibrary(context: Context) {
    private val root = File(context.filesDir, "budgets").apply { mkdirs() }
    private val driver = BundledSQLiteDriver()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun directory(id: String) = File(root, id.replace(Regex("[^A-Za-z0-9._-]"), "_"))

    fun databaseFile(id: String) = File(directory(id), "db.sqlite")

    fun list(): List<LocalBudget> = root.listFiles().orEmpty().mapNotNull { metadata(it.name) }.sortedBy { it.name.lowercase() }

    fun metadata(id: String): LocalBudget? {
        val file = File(directory(id), "metadata.json")
        if (!file.exists()) return null
        return runCatching { json.decodeFromString(LocalBudget.serializer(), file.readText()) }.getOrNull()
    }

    fun saveMetadata(budget: LocalBudget) {
        val directory = directory(budget.id).apply { mkdirs() }
        val temp = File(directory, "metadata.json.tmp")
        temp.writeText(json.encodeToString(LocalBudget.serializer(), budget))
        if (!temp.renameTo(File(directory, "metadata.json"))) {
            File(directory, "metadata.json").writeText(temp.readText())
            temp.delete()
        }
    }

    /** Opens the demo budget, creating it for [today] when it does not exist yet. */
    fun openDemo(today: LocalDate = LocalDate.now()): BudgetSession {
        val id = DemoBudget.FILE_ID
        val file = databaseFile(id)
        val database = if (file.exists() && metadata(id) != null) {
            BudgetDatabase.open(driver, file.absolutePath, DemoBudget.NODE_ID)
        } else {
            directory(id).deleteRecursively()
            directory(id).mkdirs()
            val created = DemoBudget.create(driver, file.absolutePath, today)
            saveMetadata(LocalBudget(id, DemoBudget.NAME, isDemo = true, nodeId = DemoBudget.NODE_ID))
            created
        }
        return BudgetSession(id, DemoBudget.NAME, database, isDemo = true)
    }

    fun open(id: String): BudgetSession? {
        val metadata = metadata(id) ?: return null
        if (metadata.isDemo) return openDemo()
        val file = databaseFile(id)
        if (!file.exists()) return null
        val database = BudgetDatabase.open(driver, file.absolutePath, metadata.nodeId)
        // Older files may hold account-group CRDT without the table or column (Actualist's compatibility step).
        com.sporez.actualist.core.accountgroups.AccountGroupCompatibility.prepare(database)
        return BudgetSession(id, metadata.name, database, isDemo = false)
    }

    fun delete(id: String) {
        directory(id).deleteRecursively()
    }

    /** Local changes of the stored budget [id] the server has not confirmed (0 for the demo or an unreadable file). */
    fun pendingChangeCount(id: String): Int {
        val metadata = metadata(id) ?: return 0
        val file = databaseFile(id)
        if (metadata.isDemo || !file.exists()) return 0
        return runCatching { BudgetDatabase.open(driver, file.absolutePath, metadata.nodeId).use { it.pendingLocalMessageCount() } }.getOrDefault(0)
    }

    fun openDriverDatabase(path: String, nodeId: String?): BudgetDatabase = BudgetDatabase.open(driver, path, nodeId)
}
