package com.sporez.actualist.core.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** One result row, keyed by column name, with Actual's flexible value readers. */
class SqlRow(private val values: Map<String, Any?>) {
    operator fun get(name: String): Any? = values[name]

    val columns: Set<String> get() = values.keys

    fun string(name: String): String? = when (val value = values[name]) {
        null -> null
        is String -> value
        is Long -> value.toString()
        is Double -> if (value == Math.rint(value)) value.toLong().toString() else value.toString()
        is ByteArray -> value.toString(Charsets.UTF_8)
        else -> value.toString()
    }

    fun long(name: String): Long? = when (val value = values[name]) {
        null -> null
        is Long -> value
        is Double -> value.toLong()
        is String -> value.toLongOrNull() ?: value.toDoubleOrNull()?.toLong()
        else -> null
    }

    fun longOr0(name: String): Long = long(name) ?: 0L

    fun double(name: String): Double? = when (val value = values[name]) {
        null -> null
        is Long -> value.toDouble()
        is Double -> value
        is String -> value.toDoubleOrNull()
        else -> null
    }

    /** Actualist `flexibleBool`: integers are nonzero, strings are `1`, `true` or `yes`. */
    fun bool(name: String): Boolean = when (val value = values[name]) {
        null -> false
        is Long -> value != 0L
        is Double -> value != 0.0
        is String -> value.lowercase() in setOf("1", "true", "yes")
        else -> false
    }

    override fun toString(): String = "SqlRow($values)"
}

/**
 * A thin, lock-guarded wrapper over one `androidx.sqlite` connection. The same code runs
 * in JVM unit tests and on Android, both through the bundled SQLite driver.
 *
 * Every public method takes the connection lock, so callers on any thread see whole
 * statements and whole transactions.
 */
class Sql(private val connection: SQLiteConnection) {
    private val lock = ReentrantLock()
    private var transactionDepth = 0

    fun <T> locked(body: () -> T): T = lock.withLock(body)

    fun execute(sql: String, vararg args: Any?) = locked {
        connection.prepare(sql).use { statement ->
            bind(statement, args)
            while (statement.step()) {
                // Drain any rows a statement with side effects returns.
            }
        }
    }

    fun query(sql: String, vararg args: Any?): List<SqlRow> = locked {
        connection.prepare(sql).use { statement ->
            bind(statement, args)
            val rows = mutableListOf<SqlRow>()
            val names = statement.getColumnNames()
            while (statement.step()) {
                val values = HashMap<String, Any?>(names.size * 2)
                for (index in names.indices) {
                    values[names[index]] = readColumn(statement, index)
                }
                rows += SqlRow(values)
            }
            rows
        }
    }

    fun queryOne(sql: String, vararg args: Any?): SqlRow? = query(sql, *args).firstOrNull()

    fun queryLong(sql: String, vararg args: Any?): Long? = locked {
        connection.prepare(sql).use { statement ->
            bind(statement, args)
            if (statement.step() && !statement.isNull(0)) readColumn(statement, 0).let { (it as? Long) ?: (it as? Double)?.toLong() ?: (it as? String)?.toLongOrNull() } else null
        }
    }

    fun queryString(sql: String, vararg args: Any?): String? = locked {
        connection.prepare(sql).use { statement ->
            bind(statement, args)
            if (statement.step() && !statement.isNull(0)) statement.getText(0) else null
        }
    }

    /** Runs [body] in one SQLite transaction. Nested calls join the outer transaction. */
    fun <T> transaction(body: () -> T): T = locked {
        if (transactionDepth > 0) {
            transactionDepth++
            try {
                return@locked body()
            } finally {
                transactionDepth--
            }
        }
        execute("BEGIN IMMEDIATE")
        transactionDepth = 1
        try {
            val result = body()
            execute("COMMIT")
            result
        } catch (error: Throwable) {
            try {
                execute("ROLLBACK")
            } catch (rollbackError: Throwable) {
                error.addSuppressed(rollbackError)
            }
            throw error
        } finally {
            transactionDepth = 0
        }
    }

    val inTransaction: Boolean get() = locked { transactionDepth > 0 }

    /** Runs a multi-statement script, statement by statement. */
    fun executeScript(script: String) = locked {
        for (statement in splitStatements(script)) execute(statement)
    }

    fun close() = locked { connection.close() }

    private fun bind(statement: SQLiteStatement, args: Array<out Any?>) {
        args.forEachIndexed { offset, value ->
            val index = offset + 1
            when (value) {
                null -> statement.bindNull(index)
                is String -> statement.bindText(index, value)
                is Long -> statement.bindLong(index, value)
                is Int -> statement.bindLong(index, value.toLong())
                is Boolean -> statement.bindLong(index, if (value) 1 else 0)
                is Double -> statement.bindDouble(index, value)
                is Float -> statement.bindDouble(index, value.toDouble())
                is ByteArray -> statement.bindBlob(index, value)
                else -> throw IllegalArgumentException("Unsupported SQL argument type ${value::class}")
            }
        }
    }

    private fun readColumn(statement: SQLiteStatement, index: Int): Any? = when (statement.getColumnType(index)) {
        SQLITE_INTEGER -> statement.getLong(index)
        SQLITE_FLOAT -> statement.getDouble(index)
        SQLITE_TEXT -> statement.getText(index)
        SQLITE_BLOB -> statement.getBlob(index)
        else -> null
    }

    companion object {
        private const val SQLITE_INTEGER = 1
        private const val SQLITE_FLOAT = 2
        private const val SQLITE_TEXT = 3
        private const val SQLITE_BLOB = 4

        fun quoted(identifier: String): String = "\"" + identifier.replace("\"", "\"\"") + "\""

        /** Splits a script on top-level semicolons, skipping quoted text and `--` comments. */
        fun splitStatements(script: String): List<String> {
            val statements = mutableListOf<String>()
            val current = StringBuilder()
            var index = 0
            var quote: Char? = null
            while (index < script.length) {
                val char = script[index]
                if (quote != null) {
                    current.append(char)
                    if (char == quote) quote = null
                } else if (char == '-' && index + 1 < script.length && script[index + 1] == '-') {
                    while (index < script.length && script[index] != '\n') index++
                    continue
                } else if (char == '\'' || char == '"') {
                    quote = char
                    current.append(char)
                } else if (char == ';') {
                    current.toString().trim().takeIf { it.isNotEmpty() }?.let(statements::add)
                    current.clear()
                } else {
                    current.append(char)
                }
                index++
            }
            current.toString().trim().takeIf { it.isNotEmpty() }?.let(statements::add)
            return statements
        }
    }
}
