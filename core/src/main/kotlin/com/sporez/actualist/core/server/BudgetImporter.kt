package com.sporez.actualist.core.server

import androidx.sqlite.SQLiteDriver
import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.db.Sql
import com.sporez.actualist.core.sync.ActualCrypto
import com.sporez.actualist.core.sync.EncryptedPayload
import com.sporez.actualist.core.sync.EncryptionContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import java.util.zip.ZipInputStream

/**
 * Installs a downloaded Actual budget file. Port of Actualist's download import
 * (`BudgetFileManager.importBudgetZip`, `UntrustedZipExtractor`,
 * `sanitizeUntrustedDatabase`):
 *
 * - an encrypted upload is decrypted with the budget key first;
 * - only `db.sqlite` is extracted (the archive root wins, otherwise exactly one nested
 *   copy), bounded in size and entry count, and never written by its entry name;
 * - the file is sanitized: triggers, non-`v_*` views, `actualist_*` tables and the
 *   kvcache rows are removed, so a foreign outbox or identity can never be reused.
 */
object BudgetImporter {
    const val MAXIMUM_ENTRIES = 2_000
    const val MAXIMUM_DATABASE_BYTES = 512L * 1024 * 1024

    fun decryptIfNeeded(bytes: ByteArray, remote: RemoteFile, encryption: EncryptionContext?): ByteArray {
        val meta = remote.encryptMeta ?: return bytes
        val key = encryption ?: throw LocalFirstException.EncryptedBudgetRequiresPassword()
        if (meta.algorithm != null && meta.algorithm != ActualCrypto.ALGORITHM) {
            throw LocalFirstException.UnsupportedEncryptionAlgorithm(meta.algorithm)
        }
        val iv = meta.iv?.let(::base64) ?: throw LocalFirstException.InvalidEncryptedPayload()
        val tag = meta.authTag?.let(::base64) ?: throw LocalFirstException.InvalidEncryptedPayload()
        return ActualCrypto.decrypt(EncryptedPayload(bytes, iv, tag), key.keyData)
    }

    /** Writes the archive's `db.sqlite` to [destination] and sanitizes it in place. */
    fun install(zipBytes: ByteArray, destination: File, driver: SQLiteDriver) {
        destination.parentFile?.mkdirs()
        val temp = File(destination.parentFile, destination.name + ".import")
        temp.delete()
        try {
            extractDatabase(zipBytes, temp)
            sanitize(temp, driver)
            if (destination.exists() && !destination.delete()) throw LocalFirstException.InvalidDownloadedBudget("could not replace the old file")
            File(destination.path + "-journal").delete()
            if (!temp.renameTo(destination)) throw LocalFirstException.InvalidDownloadedBudget("could not install the file")
        } finally {
            temp.delete()
            File(temp.path + "-journal").delete()
        }
    }

    fun extractDatabase(zipBytes: ByteArray, target: File) {
        var rootFound = false
        var nestedCount = 0
        var entries = 0
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            while (true) {
                val entry = try {
                    zip.nextEntry
                } catch (error: Exception) {
                    throw LocalFirstException.InvalidDownloadedBudget("unreadable archive")
                } ?: break
                if (++entries > MAXIMUM_ENTRIES) throw LocalFirstException.InvalidDownloadedBudget("too many entries")
                if (entry.isDirectory) continue
                val name = entry.name.replace('\\', '/')
                if (name.substringAfterLast('/') != "db.sqlite") continue
                val isRoot = !name.contains('/')
                if (!isRoot) nestedCount++
                // The root copy wins; a nested copy is used only when it is the only one.
                if (rootFound || (!isRoot && nestedCount > 1)) continue
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAXIMUM_DATABASE_BYTES) throw LocalFirstException.InvalidDownloadedBudget("database too large")
                        out.write(buffer, 0, read)
                    }
                }
                if (isRoot) rootFound = true
            }
        }
        if (!rootFound && nestedCount != 1) {
            target.delete()
            throw LocalFirstException.InvalidDownloadedBudget(if (nestedCount > 1) "several databases" else "no database in the archive")
        }
    }

    fun sanitize(file: File, driver: SQLiteDriver) {
        val sql = try {
            Sql(driver.open(file.absolutePath))
        } catch (error: Exception) {
            throw LocalFirstException.InvalidDownloadedBudget("not a database")
        }
        try {
            sql.execute("PRAGMA trusted_schema = OFF")
            sql.execute("PRAGMA journal_mode = DELETE")
            sql.transaction {
                for (row in sql.query("SELECT name FROM sqlite_master WHERE type = 'trigger'")) {
                    sql.execute("DROP TRIGGER IF EXISTS ${Sql.quoted(row.string("name")!!)}")
                }
                for (row in sql.query("SELECT name FROM sqlite_master WHERE type = 'view' AND name NOT LIKE 'v\\_%' ESCAPE '\\'")) {
                    sql.execute("DROP VIEW IF EXISTS ${Sql.quoted(row.string("name")!!)}")
                }
                fun local(name: String): Boolean {
                    val lowered = name.lowercase()
                    return lowered.startsWith("actualist_") || lowered == "kvcache" || lowered == "kvcache_key"
                }
                for (row in sql.query("SELECT name FROM sqlite_master WHERE type = 'table'")) {
                    val name = row.string("name")!!
                    if (local(name)) sql.execute("DROP TABLE IF EXISTS ${Sql.quoted(name)}")
                }
                for (row in sql.query("SELECT name FROM sqlite_master WHERE type = 'index'")) {
                    val name = row.string("name") ?: continue
                    if (local(name)) sql.execute("DROP INDEX IF EXISTS ${Sql.quoted(name)}")
                }
                sql.execute("CREATE TABLE kvcache (key TEXT PRIMARY KEY, value TEXT)")
                sql.execute("CREATE TABLE kvcache_key (id INTEGER PRIMARY KEY, key REAL)")
                if (sql.queryOne("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'messages_crdt'") == null) {
                    throw LocalFirstException.InvalidDownloadedBudget("missing messages_crdt")
                }
            }
            sql.execute("VACUUM")
        } catch (error: LocalFirstException) {
            throw error
        } catch (error: Exception) {
            throw LocalFirstException.InvalidDownloadedBudget("unreadable database")
        } finally {
            sql.close()
        }
    }

    /**
     * Derives the budget key from the password and proves it against the server's test
     * payload (`{"value": base64, "meta": {"keyId","algorithm","iv","authTag"}}`).
     */
    fun validateUserKey(key: UserKey, password: String): EncryptionContext {
        val test = key.test ?: throw LocalFirstException.InvalidEncryptionKey()
        val keyData = ActualCrypto.deriveKey(password, key.salt)
        val payload = try {
            Json.parseToJsonElement(test) as JsonObject
        } catch (error: Exception) {
            throw LocalFirstException.InvalidEncryptedPayload()
        }
        val meta = payload["meta"] as? JsonObject ?: throw LocalFirstException.InvalidEncryptedPayload()
        fun m(name: String) = (meta[name] as? JsonPrimitive)?.contentOrNull
        val algorithm = m("algorithm")
        if (algorithm != ActualCrypto.ALGORITHM) throw LocalFirstException.UnsupportedEncryptionAlgorithm(algorithm ?: "")
        val value = (payload["value"] as? JsonPrimitive)?.contentOrNull ?: throw LocalFirstException.InvalidEncryptedPayload()
        val iv = m("iv") ?: throw LocalFirstException.InvalidEncryptedPayload()
        val tag = m("authTag") ?: throw LocalFirstException.InvalidEncryptedPayload()
        ActualCrypto.decrypt(EncryptedPayload(base64(value), base64(iv), base64(tag)), keyData)
        return EncryptionContext(key.id, keyData)
    }

    private fun base64(text: String): ByteArray = try {
        Base64.getDecoder().decode(text)
    } catch (error: IllegalArgumentException) {
        throw LocalFirstException.InvalidEncryptedPayload()
    }
}
