package com.sporez.actualist.core.db

import androidx.sqlite.SQLiteDriver
import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.sync.HybridLogicalClock
import com.sporez.actualist.core.sync.MerkleTrie
import com.sporez.actualist.core.sync.SyncMessage
import com.sporez.actualist.core.sync.SyncTimestamp
import com.sporez.actualist.core.sync.SyncValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.Closeable

/** Wall clock seam, so tests and demo seeding are deterministic. */
fun interface Clock {
    fun nowMillis(): Long

    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() }
    }
}

/** A queued local change awaiting upload. */
data class PendingLocalMessage(
    val message: SyncMessage,
    val baseTimestamp: String,
    val attemptCount: Int,
    val lastError: String?,
)

data class RemoteApplyResult(
    val appliedMessageCount: Int,
    val insertedTransactionIds: Set<String> = emptySet(),
    /** Timestamps stored but not applied because their values were unreadable. */
    val quarantinedTimestamps: List<String> = emptyList(),
) {
    fun merging(later: RemoteApplyResult) = RemoteApplyResult(
        appliedMessageCount + later.appliedMessageCount,
        insertedTransactionIds + later.insertedTransactionIds,
        quarantinedTimestamps + later.quarantinedTimestamps,
    )

    companion object {
        val EMPTY = RemoteApplyResult(0)
    }
}

/**
 * The imported Actual budget file and Actualist's local-first bookkeeping in it.
 *
 * Port of the core of Actualist `BudgetDatabase` (`+Sync`, `+Merkle`, `+LocalCommit`):
 * remote CRDT messages apply with last-write-wins per cell, local writes mint HLC
 * timestamps, apply, append to `messages_crdt` and enqueue in `actualist_outbox` in one
 * SQLite transaction, and the merkle trie in `messages_clock` is maintained in that same
 * transaction.
 */
class BudgetDatabase private constructor(
    val sql: Sql,
    localNodeId: String?,
    private val clock: Clock,
) : Closeable {
    @Volatile
    var localClock: HybridLogicalClock? = null
        private set

    private val tableExistsCache = HashMap<String, Boolean>()
    private val columnSetCache = HashMap<String, Set<String>>()

    init {
        if (localNodeId != null) {
            val latest = sql.queryString("SELECT MAX(timestamp) FROM messages_crdt") ?: SyncTimestamp.ZERO
            localClock = HybridLogicalClock(localNodeId, latest)
        }
        prepareMerkleTrie()
    }

    override fun close() = sql.close()

    val localClockTimestamp: String? get() = localClock?.lastTimestamp

    // region Schema helpers

    fun tableExists(table: String): Boolean = sql.locked {
        tableExistsCache.getOrPut(table) {
            sql.queryOne("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", table) != null
        }
    }

    fun columnSet(table: String): Set<String> = sql.locked {
        columnSetCache.getOrPut(table) {
            sql.query("PRAGMA table_info(${Sql.quoted(table)})").mapNotNull { it.string("name") }.toSet()
        }
    }

    fun requiredColumns(table: String, required: List<String>): Set<String> {
        if (!tableExists(table)) throw LocalFirstException.SchemaUnavailable("missing $table table")
        val columns = columnSet(table)
        for (column in required) {
            if (column !in columns) throw LocalFirstException.SchemaUnavailable("missing column $table.$column")
        }
        return columns
    }

    fun rowExists(table: String, rowId: String): Boolean =
        sql.queryOne("SELECT id FROM ${Sql.quoted(table)} WHERE id = ? LIMIT 1", rowId) != null

    /** A tombstoned row does not count. */
    fun liveRowExists(table: String, rowId: String): Boolean {
        if (!rowExists(table, rowId)) return false
        if (table == "zero_budgets" || table == "reflect_budgets") return true
        val predicate = livePredicate(columnSet(table))
        return sql.queryOne("SELECT id FROM ${Sql.quoted(table)} WHERE id = ? AND $predicate LIMIT 1", rowId) != null
    }

    fun invalidateSchemaCaches() = sql.locked {
        tableExistsCache.clear()
        columnSetCache.clear()
    }

    // endregion

    // region Sync reads

    /** The `since` for the next pull, clamped so a stored far-future row cannot freeze sync. */
    fun latestSyncTimestamp(nowMillis: Long = clock.nowMillis()): String {
        if (!tableExists("messages_crdt")) return SyncTimestamp.ZERO
        val stored = sql.queryString("SELECT MAX(timestamp) FROM messages_crdt")
        return SyncTimestamp.clampedSince(stored, nowMillis)
    }

    fun messageCount(): Long = sql.queryLong("SELECT COUNT(*) FROM messages_crdt") ?: 0L

    fun pendingLocalMessageCount(): Int {
        if (!tableExists(OUTBOX)) return 0
        return (sql.queryLong("SELECT COUNT(*) FROM $OUTBOX") ?: 0L).toInt()
    }

    fun pendingLocalMessages(limit: Int = 500): List<PendingLocalMessage> {
        if (!tableExists(OUTBOX)) return emptyList()
        return sql.query(
            """
            SELECT timestamp, dataset, row, column, value, base_timestamp, attempt_count, last_error
            FROM $OUTBOX ORDER BY timestamp ASC LIMIT ?
            """.trimIndent(),
            limit,
        ).map { row ->
            PendingLocalMessage(
                SyncMessage(
                    row.string("timestamp") ?: "",
                    row.string("dataset") ?: "",
                    row.string("row") ?: "",
                    row.string("column") ?: "",
                    row.string("value") ?: "",
                ),
                row.string("base_timestamp") ?: SyncTimestamp.ZERO,
                row.longOr0("attempt_count").toInt(),
                row.string("last_error"),
            )
        }
    }

    fun deletePendingLocalMessages(timestamps: Collection<String>) {
        if (timestamps.isEmpty() || !tableExists(OUTBOX)) return
        sql.transaction {
            for (timestamp in timestamps) sql.execute("DELETE FROM $OUTBOX WHERE timestamp = ?", timestamp)
        }
    }

    fun markPendingLocalMessagesFailed(timestamps: Collection<String>, error: String) {
        if (timestamps.isEmpty() || !tableExists(OUTBOX)) return
        val now = SyncTimestamp.wallTimeString(clock.nowMillis())
        sql.transaction {
            for (timestamp in timestamps) {
                sql.execute(
                    "UPDATE $OUTBOX SET attempt_count = attempt_count + 1, last_attempt_at = ?, last_error = ? WHERE timestamp = ?",
                    now,
                    error,
                    timestamp,
                )
            }
        }
    }

    /** Demo mode keeps every write local: the outbox is drained so nothing is ever uploaded. */
    fun drainAllPendingLocalMessages(): Int {
        if (!tableExists(OUTBOX)) return 0
        return sql.transaction {
            val count = (sql.queryLong("SELECT COUNT(*) FROM $OUTBOX") ?: 0L).toInt()
            if (count > 0) sql.execute("DELETE FROM $OUTBOX")
            count
        }
    }

    // endregion

    // region Remote apply

    /**
     * Applies a pulled batch. Mirrors Actualist `applyRemoteSyncMessagesTrackingInserts`:
     * the whole batch is validated first; exact duplicates are skipped (a second insert
     * would cancel in the merkle XOR); superseded messages are stored but not applied;
     * reserved datasets, unknown tables and unknown columns are stored as bookkeeping
     * only; unreadable values are stored and quarantined; `prefs` is never applied.
     */
    fun applyRemoteMessages(messages: List<SyncMessage>, nowMillis: Long = clock.nowMillis()): RemoteApplyResult {
        if (messages.isEmpty()) return RemoteApplyResult.EMPTY
        SyncTimestamp.validateRemoteBatch(messages.map { it.timestamp }, nowMillis)
        val result = sql.transaction {
            if (!tableExists("messages_crdt")) return@transaction RemoteApplyResult.EMPTY
            var trie = currentMerkleTrie()
            var applied = 0
            val insertedRows = HashSet<Pair<String, String>>()
            val insertedTransactions = HashSet<String>()
            val quarantined = mutableListOf<String>()
            for (message in messages.sortedBy { it.timestamp }) {
                if (messageExists(message, "=")) continue
                if (messageExists(message, ">")) {
                    trie = insertCrdtMessage(message, trie)
                    continue
                }
                if (isReservedDataset(message.dataset) || !tableExists(message.dataset) ||
                    message.column !in columnSet(message.dataset)
                ) {
                    trie = insertCrdtMessage(message, trie)
                    applied++
                    continue
                }
                val key = message.dataset to message.row
                val hasRow = key in insertedRows || rowExists(message.dataset, message.row)
                val value = try {
                    SyncValue.deserialize(message.serializedValue)
                } catch (error: LocalFirstException.InvalidSyncValue) {
                    trie = insertCrdtMessage(message, trie)
                    quarantined += message.timestamp
                    continue
                }
                if (message.dataset != "prefs") {
                    applyCell(message, value, hasRow)
                    insertedRows += key
                    if (message.dataset == "transactions" && !hasRow) insertedTransactions += message.row
                }
                trie = insertCrdtMessage(message, trie)
                applied++
            }
            persistMerkleTrie(trie.pruned())
            RemoteApplyResult(applied, insertedTransactions, quarantined)
        }
        var observed = localClock
        if (observed != null) {
            for (message in messages) observed = observed!!.observe(message.timestamp)
            localClock = observed
        }
        return result
    }

    private fun messageExists(message: SyncMessage, comparison: String): Boolean = sql.queryOne(
        """
        SELECT timestamp FROM messages_crdt
        WHERE dataset = ? AND row = ? AND column = ? AND timestamp $comparison ?
        LIMIT 1
        """.trimIndent(),
        message.dataset,
        message.row,
        message.column,
        message.timestamp,
    ) != null

    private fun applyCell(message: SyncMessage, value: SyncValue, rowExists: Boolean) {
        val table = Sql.quoted(message.dataset)
        val column = Sql.quoted(message.column)
        if (rowExists) {
            sql.execute("UPDATE $table SET $column = ? WHERE id = ?", value.sqlValue, message.row)
        } else {
            sql.execute("INSERT INTO $table (id, $column) VALUES (?, ?)", message.row, value.sqlValue)
        }
    }

    private fun insertCrdtMessage(message: SyncMessage, trie: MerkleTrie): MerkleTrie {
        sql.execute(
            "INSERT INTO messages_crdt (timestamp, dataset, row, column, value) VALUES (?, ?, ?, ?, ?)",
            message.timestamp,
            message.dataset,
            message.row,
            message.column,
            message.serializedValue,
        )
        // Local and validated remote timestamps always parse.
        val parsed = SyncTimestamp.parse(message.timestamp) ?: return trie
        return trie.insert(parsed)
    }

    // endregion

    // region Local commit

    /**
     * Commits drafts built by [prepare] inside one write transaction: each draft gets an
     * HLC timestamp, is validated, refused if a same-or-newer message exists for its
     * cell, applied, appended to `messages_crdt` and enqueued in the outbox. A failure
     * rolls back every row and leaves the in-memory clock where it was.
     *
     * Drafts are applied in the order given. [prepare] runs inside the transaction so
     * the writes it plans start from the latest committed state.
     */
    fun <T> commitLocal(nowMillis: Long = clock.nowMillis(), prepare: () -> Pair<List<CellDraft>, T>): CommitResult<T> {
        var committedClock = localClock
        val result = sql.transaction {
            val (drafts, outcome) = prepare()
            if (drafts.isEmpty()) return@transaction CommitResult(outcome, emptyList())
            var clock = committedClock ?: throw LocalFirstException.InvalidLocalWrite("local clock is not configured")
            if (!tableExists("messages_crdt")) throw LocalFirstException.InvalidLocalWrite("missing messages_crdt table")
            ensureOutbox()
            val baseTimestamp = sql.queryString("SELECT MAX(timestamp) FROM messages_crdt") ?: SyncTimestamp.ZERO
            var trie = currentMerkleTrie()
            val insertedRows = HashSet<Pair<String, String>>()
            val committed = mutableListOf<SyncMessage>()
            for (draft in drafts) {
                val minted = clock.next(nowMillis)
                clock = minted.clock
                val message = SyncMessage(minted.timestamp, draft.dataset, draft.row, draft.column, draft.value.serialized)
                validateLocalMessage(message)
                if (messageExists(message, ">=")) throw LocalFirstException.LocalWriteSuperseded()
                val key = message.dataset to message.row
                val hasRow = key in insertedRows || rowExists(message.dataset, message.row)
                applyCell(message, draft.value, hasRow)
                insertedRows += key
                trie = insertCrdtMessage(message, trie)
                sql.execute(
                    """
                    INSERT INTO $OUTBOX (timestamp, dataset, row, column, value, base_timestamp, created_at,
                        attempt_count, last_attempt_at, last_error)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 0, NULL, NULL)
                    """.trimIndent(),
                    message.timestamp,
                    message.dataset,
                    message.row,
                    message.column,
                    message.serializedValue,
                    baseTimestamp,
                    SyncTimestamp.wallTimeString(nowMillis),
                )
                committed += message
            }
            persistMerkleTrie(trie.pruned(), clock)
            committedClock = clock
            CommitResult(outcome, committed)
        }
        localClock = committedClock
        return result
    }

    fun commitLocal(drafts: List<CellDraft>, nowMillis: Long = clock.nowMillis()): List<SyncMessage> =
        commitLocal(nowMillis) { drafts to Unit }.messages

    private fun validateLocalMessage(message: SyncMessage) {
        if (isReservedDataset(message.dataset) || !tableExists(message.dataset)) {
            throw LocalFirstException.InvalidLocalWrite("unknown dataset ${message.dataset}")
        }
        if (message.column !in columnSet(message.dataset)) {
            throw LocalFirstException.InvalidLocalWrite("unknown column ${message.dataset}.${message.column}")
        }
    }

    private fun ensureOutbox() {
        sql.execute(
            """
            CREATE TABLE IF NOT EXISTS $OUTBOX (
                timestamp TEXT PRIMARY KEY,
                dataset TEXT NOT NULL,
                row TEXT NOT NULL,
                column TEXT NOT NULL,
                value TEXT NOT NULL,
                base_timestamp TEXT NOT NULL,
                created_at TEXT NOT NULL,
                attempt_count INTEGER NOT NULL DEFAULT 0,
                last_attempt_at TEXT,
                last_error TEXT
            )
            """.trimIndent(),
        )
        tableExistsCache.remove(OUTBOX)
    }

    // endregion

    // region Merkle

    /** Milliseconds of the earliest minute where the server's trie and this file's disagree, or null. */
    fun merkleDivergence(server: MerkleTrie): Long? = MerkleTrie.diff(server, sql.locked { currentMerkleTrie() })

    fun merkleTrie(): MerkleTrie = sql.locked { currentMerkleTrie() }

    /** Recomputes the trie from `messages_crdt` and persists it. */
    fun rebuildMerkleTrie() {
        sql.transaction { persistMerkleTrie(rebuiltMerkleTrie()) }
    }

    private fun currentMerkleTrie(): MerkleTrie = storedMerkleTrie() ?: rebuiltMerkleTrie()

    private fun rebuiltMerkleTrie(): MerkleTrie {
        if (!tableExists("messages_crdt")) return MerkleTrie.EMPTY
        var trie = MerkleTrie.EMPTY
        for (row in sql.query("SELECT timestamp FROM messages_crdt")) {
            val parsed = row.string("timestamp")?.let(SyncTimestamp::parse) ?: continue
            trie = trie.insert(parsed)
        }
        return trie.pruned()
    }

    private fun storedClockJson(): JsonObject? {
        if (!tableExists("messages_clock")) return null
        val text = sql.queryString("SELECT clock FROM messages_clock ORDER BY id LIMIT 1") ?: return null
        return try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (error: Exception) {
            null
        }
    }

    private fun storedMerkleTrie(): MerkleTrie? {
        val merkle = storedClockJson()?.get("merkle") ?: return null
        return MerkleTrie.fromJsonElement(merkle)
    }

    /**
     * Writes upstream's `{"timestamp": <HLC>, "merkle": {...}}`, keeping a valid stored HLC
     * (it names the node other clients adopt).
     */
    private fun persistMerkleTrie(trie: MerkleTrie, clock: HybridLogicalClock? = localClock) {
        sql.execute("CREATE TABLE IF NOT EXISTS messages_clock (id INTEGER PRIMARY KEY, clock TEXT)")
        tableExistsCache.remove("messages_clock")
        val stored = (storedClockJson()?.get("timestamp") as? JsonPrimitive)?.contentOrNull
        val timestamp = when {
            stored != null && SyncTimestamp.parse(stored) != null -> stored
            clock != null && SyncTimestamp.parse(clock.lastTimestamp) != null -> clock.lastTimestamp
            else -> {
                val node = HybridLogicalClock.normalizedNodeId(clock?.nodeId ?: "")
                SyncTimestamp.ZERO.dropLast(16) + "0".repeat(maxOf(0, 16 - node.length)) + node
            }
        }
        val clockJson = "{\"timestamp\":\"$timestamp\",\"merkle\":${trie.jsonString}}"
        sql.execute("DELETE FROM messages_clock WHERE id <> 1")
        sql.execute("INSERT OR REPLACE INTO messages_clock (id, clock) VALUES (1, ?)", clockJson)
    }

    /** An imported `messages_clock` is not trusted: rebuild it once per file. */
    private fun prepareMerkleTrie() {
        if (!tableExists("messages_crdt") || localMigrationApplied(MERKLE_REBUILD_MIGRATION)) return
        sql.transaction {
            val trie = rebuiltMerkleTrie()
            val hadClockRow = tableExists("messages_clock") &&
                (sql.queryLong("SELECT COUNT(*) FROM messages_clock") ?: 0L) > 0
            if (!trie.isEmpty || hadClockRow) persistMerkleTrie(trie)
            recordLocalMigration(MERKLE_REBUILD_MIGRATION)
        }
    }

    // endregion

    // region Local migrations

    fun localMigrationApplied(name: String): Boolean {
        if (!tableExists(LOCAL_MIGRATIONS)) return false
        return sql.queryOne("SELECT name FROM $LOCAL_MIGRATIONS WHERE name = ?", name) != null
    }

    fun recordLocalMigration(name: String) {
        sql.execute("CREATE TABLE IF NOT EXISTS $LOCAL_MIGRATIONS (name TEXT PRIMARY KEY, applied_at TEXT NOT NULL)")
        tableExistsCache.remove(LOCAL_MIGRATIONS)
        sql.execute(
            "INSERT OR REPLACE INTO $LOCAL_MIGRATIONS (name, applied_at) VALUES (?, ?)",
            name,
            (clock.nowMillis() / 1000.0).toString(),
        )
    }

    // endregion

    data class CommitResult<T>(val outcome: T, val messages: List<SyncMessage>)

    companion object {
        const val OUTBOX = "actualist_outbox"
        const val LOCAL_MIGRATIONS = "actualist_local_migrations"
        const val MERKLE_REBUILD_MIGRATION = "merkle-v1"
        const val LOCAL_TABLE_PREFIX = "actualist_"
        private val reservedPrefixes = listOf("sqlite_", LOCAL_TABLE_PREFIX, "messages_", "kvcache", "__", "db_version")

        /**
         * Datasets stored in `messages_crdt` for bookkeeping but never applied: SQLite
         * internals, this client's own tables and upstream bookkeeping tables.
         */
        fun isReservedDataset(dataset: String): Boolean {
            val name = dataset.lowercase()
            return reservedPrefixes.any { name.startsWith(it) }
        }

        fun livePredicate(columns: Set<String>, alias: String? = null): String {
            val prefix = alias?.let { "$it." } ?: ""
            return when {
                "tombstone" in columns -> "(${prefix}tombstone IS NULL OR ${prefix}tombstone = 0)"
                "deleted" in columns -> "(${prefix}deleted IS NULL OR ${prefix}deleted = 0)"
                else -> "1 = 1"
            }
        }

        /** Opens an existing budget file. */
        fun open(driver: SQLiteDriver, path: String, localNodeId: String?, clock: Clock = Clock.SYSTEM): BudgetDatabase {
            val sql = Sql(driver.open(path))
            return BudgetDatabase(sql, localNodeId, clock)
        }

        /** Creates a new budget file with Actual v26.10.0's starter schema. */
        fun createStarter(driver: SQLiteDriver, path: String, localNodeId: String?, clock: Clock = Clock.SYSTEM): BudgetDatabase {
            val sql = Sql(driver.open(path))
            sql.transaction { sql.executeScript(starterSchemaSql()) }
            return BudgetDatabase(sql, localNodeId, clock)
        }

        fun starterSchemaSql(): String {
            val stream = BudgetDatabase::class.java.getResourceAsStream("/actual/starter-schema.sql")
                ?: throw LocalFirstException.SchemaUnavailable("starter schema resource missing")
            return stream.bufferedReader().use { it.readText() }
        }
    }
}

/** One planned cell write; the database assigns its HLC timestamp at commit. */
data class CellDraft(val dataset: String, val row: String, val column: String, val value: SyncValue)
