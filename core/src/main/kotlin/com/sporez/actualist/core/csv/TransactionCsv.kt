package com.sporez.actualist.core.csv

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.budget.Months
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.CellDraft
import com.sporez.actualist.core.transactions.TransactionDraft
import com.sporez.actualist.core.transactions.TransactionWrites
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** One exported row; split children follow their parent and share its family id. */
data class CsvExportRow(
    val id: String,
    val familyId: String,
    val accountName: String,
    val date: String,
    val payeeName: String,
    val notes: String?,
    val categoryGroupName: String,
    val categoryName: String,
    val amount: Long,
    val isParent: Boolean,
    val isChild: Boolean,
    val cleared: Boolean,
    val reconciled: Boolean,
)

/**
 * Actual's transaction CSV export, ported from Actualist `TransactionCSVEncoder` (pinned
 * Actual 26.9.0 / csv-stringify defaults): fixed headers, LF records with a final LF,
 * split markers in Notes, spreadsheet-formula guarding, and `Cleared`/`Not cleared`/
 * `Reconciled`.
 */
object CsvExport {
    private val headers = listOf("Account", "Date", "Payee", "Notes", "Category_Group", "Category", "Amount", "Split_Amount", "Cleared")
    private val formulaTriggers = setOf('=', '+', '-', '@', '\t', '\r')

    fun encode(rows: List<CsvExportRow>): String {
        val childrenByParent = rows.filter { it.isChild }.groupBy { it.familyId }
        val output = mutableListOf(headers.joinToString(",") { cell(it) })
        for (row in rows) {
            val siblings = childrenByParent[row.familyId].orEmpty()
            val childNumber = siblings.indexOfFirst { it.id == row.id } + 1
            val notes = when {
                row.isParent -> "(SPLIT INTO ${siblings.size}) ${row.notes ?: ""}"
                row.isChild -> "(SPLIT $childNumber OF ${siblings.size}) ${row.notes ?: ""}"
                else -> row.notes
            }
            val amount = if (row.isParent) "0" else actualAmount(row.amount)
            val splitAmount = if (row.isParent) actualAmount(row.amount) else "0"
            val cleared = if (row.reconciled) "Reconciled" else if (row.cleared) "Cleared" else "Not cleared"
            val fields = listOf(
                text(row.accountName), text(row.date), text(row.payeeName), text(notes ?: ""),
                text(row.categoryGroupName), text(row.categoryName), amount, splitAmount, cleared,
            )
            output += fields.joinToString(",") { cell(it) }
        }
        return output.joinToString("\n") + "\n"
    }

    fun filename(nowMillis: Long): String {
        val t = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMillis), ZoneOffset.UTC)
        return "Transactions-%04d%02d%02d-%02d%02d%02d.csv".format(java.util.Locale.ROOT, t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second)
    }

    private fun text(value: String) = if (value.isNotEmpty() && value[0] in formulaTriggers) "'$value" else value

    private fun cell(value: String) =
        if (value.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

    /** `NSDecimalNumber(minor / 100).stringValue`: no trailing zeros, no exponent. */
    fun actualAmount(minorUnits: Long): String {
        val value = BigDecimal(minorUnits).movePointLeft(2).stripTrailingZeros()
        return if (value.signum() == 0) "0" else value.toPlainString()
    }

    /** Rows for one account (or all), split parents followed by their children. */
    fun rows(database: BudgetDatabase, accountId: String? = null): List<CsvExportRow> {
        val sql = database.sql
        val args = mutableListOf<Any?>()
        val accountFilter = accountId?.let {
            args += it
            "AND t.acct = ?"
        } ?: ""
        val records = sql.query(
            """
            SELECT t.id AS id, t.parent_id AS parent_id, t.isParent AS is_parent, t.isChild AS is_child, a.name AS account_name,
                   t.date AS date, COALESCE(ta.name, p.name, '') AS payee_name, t.notes AS notes, COALESCE(g.name, '') AS group_name,
                   COALESCE(c.name, '') AS category_name, t.amount AS amount, t.cleared AS cleared, t.reconciled AS reconciled,
                   t.sort_order AS sort_order
            FROM transactions t
            JOIN accounts a ON a.id = t.acct
            LEFT JOIN transactions parent ON parent.id = t.parent_id
            LEFT JOIN payees p ON p.id = t.description
            LEFT JOIN accounts ta ON ta.id = p.transfer_acct
            LEFT JOIN categories c ON c.id = t.category
            LEFT JOIN category_groups g ON g.id = c.cat_group
            WHERE (t.tombstone IS NULL OR t.tombstone = 0) AND t.date IS NOT NULL
              AND (t.isChild = 0 OR t.isChild IS NULL OR (parent.id IS NOT NULL AND (parent.tombstone IS NULL OR parent.tombstone = 0)))
              $accountFilter
            ORDER BY t.date DESC, COALESCE(t.parent_id, t.id), t.isChild, t.sort_order DESC, t.id
            """.trimIndent(),
            *args.toTypedArray(),
        )
        return records.map { row ->
            val isChild = row.bool("is_child")
            CsvExportRow(
                id = row.string("id")!!,
                familyId = if (isChild) row.string("parent_id")!! else row.string("id")!!,
                accountName = row.string("account_name") ?: "",
                date = row.long("date")?.let(Months::dateId) ?: "",
                payeeName = row.string("payee_name") ?: "",
                notes = row.string("notes"),
                categoryGroupName = row.string("group_name") ?: "",
                categoryName = row.string("category_name") ?: "",
                amount = row.longOr0("amount"),
                isParent = row.bool("is_parent"),
                isChild = isChild,
                cleared = row.bool("cleared"),
                reconciled = row.bool("reconciled"),
            )
        }
    }
}

/**
 * CSV parsing, ported from Actualist `TransactionCSVParser` (pinned Actual `csv-parse`
 * options: BOM stripped, `"` quoting with `""` escapes, unquoted fields trimmed, relaxed
 * column counts, blank lines skipped).
 */
object CsvParser {
    data class Table(val headers: List<String>?, val rows: List<List<String>>) {
        fun value(header: String, row: List<String>): String? {
            val index = headers?.indexOfFirst { it.equals(header, ignoreCase = true) } ?: return null
            return if (index in row.indices) row[index] else null
        }

        fun value(index: Int, row: List<String>): String? = row.getOrNull(index)
    }

    fun parse(input: String, delimiter: Char = ',', hasHeaderRow: Boolean = true): Table {
        val text = input.removePrefix("﻿")
        val rows = mutableListOf<List<String>>()
        var fields = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var inQuotes = false
        fun endField() {
            fields += if (quoted) field.toString() else field.toString().trim()
            field.clear()
            quoted = false
        }
        fun endRecord() {
            endField()
            if (!(fields.size == 1 && fields[0].isEmpty())) rows += fields
            fields = mutableListOf()
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        field.append('"')
                        i += 2
                    } else {
                        inQuotes = false
                        i++
                    }
                } else {
                    field.append(c)
                    i++
                }
                continue
            }
            when {
                c == '"' && field.isEmpty() && !quoted -> {
                    inQuotes = true
                    quoted = true
                    i++
                }
                c == delimiter -> {
                    endField()
                    i++
                }
                c == '\n' -> {
                    endRecord()
                    i++
                }
                c == '\r' && i + 1 < text.length && text[i + 1] == '\n' -> {
                    endRecord()
                    i += 2
                }
                else -> {
                    field.append(c)
                    i++
                }
            }
        }
        if (field.isNotEmpty() || quoted || fields.isNotEmpty()) endRecord()
        if (!hasHeaderRow) return Table(null, rows)
        val headerRow = rows.firstOrNull() ?: return Table(emptyList(), emptyList())
        return Table(headerRow, rows.drop(1).map { if (it.size > headerRow.size) it.take(headerRow.size) else it })
    }
}

data class CsvImportRow(
    val line: Int,
    val date: LocalDate,
    val amount: Long,
    val payeeName: String,
    val notes: String?,
    val categoryName: String?,
    val cleared: Boolean?,
    val importedId: String?,
)

class CsvImportException(message: String) : Exception(message)

/** Maps parsed rows, all-or-nothing, as Actualist's `TransactionCSVImportMapper`. */
object CsvImportMapper {
    const val MAXIMUM_BYTES = 10 * 1024 * 1024
    const val MAXIMUM_ROWS = 50_000

    fun map(table: CsvParser.Table): List<CsvImportRow> {
        if (table.rows.size > MAXIMUM_ROWS) throw CsvImportException("The file has more than $MAXIMUM_ROWS rows.")
        return table.rows.mapIndexed { index, fields ->
            val line = index + 1
            fun field(header: String, position: Int) = if (table.headers != null) table.value(header, fields) else table.value(position, fields)
            val dateText = field("Date", 0)?.trim().orEmpty()
            if (dateText.isEmpty()) throw CsvImportException("Row $line could not be read because a date is required.")
            val date = strictIsoDate(dateText) ?: throw CsvImportException("Row $line could not be read because the date \"$dateText\" is not an ISO yyyy-mm-dd date.")
            val amountText = field("Amount", 3)?.trim().orEmpty()
            val amount = if (amountText.isEmpty()) 0L else amountMinorUnits(amountText)
                ?: throw CsvImportException("Row $line could not be read because the amount \"$amountText\" is not a decimal number.")
            if (amount == 0L) throw CsvImportException("Row $line could not be read because the amount is zero.")
            CsvImportRow(
                line = line,
                date = date,
                amount = amount,
                payeeName = field("Payee", 1)?.trim().orEmpty(),
                notes = field("Notes", 2)?.takeIf { it.isNotEmpty() },
                categoryName = table.value("Category", fields)?.takeIf { it.isNotEmpty() },
                cleared = table.value("Cleared", fields)?.let(::clearedValue),
                importedId = table.value("imported_id", fields)?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
    }

    fun strictIsoDate(text: String): LocalDate? {
        if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(text)) return null
        return try {
            LocalDate.parse(text)
        } catch (error: Exception) {
            null
        }
    }

    /** Decimal text with an optional sign and at most two fraction digits. */
    fun amountMinorUnits(raw: String): Long? {
        val match = Regex("^([+-]?)(\\d+)(?:\\.(\\d{0,2}))?$").matchEntire(raw.trim()) ?: return null
        val (sign, whole, fraction) = match.destructured
        val wholeValue = whole.toLongOrNull() ?: return null
        if (wholeValue > (Long.MAX_VALUE - 99) / 100) return null
        val cents = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * 10
            else -> fraction.toLong()
        }
        val value = wholeValue * 100 + cents
        return if (sign == "-") -value else value
    }

    /** The exporter writes exactly these spellings; anything else carries no cleared state. */
    fun clearedValue(raw: String): Boolean? = when (raw.trim().lowercase()) {
        "cleared", "reconciled" -> true
        "not cleared" -> false
        else -> null
    }
}

/**
 * Plans a CSV import into one account. Each row becomes a simple transaction with the
 * payee text as imported payee (payees and categories matched by name, payees created when
 * missing). A row is skipped as a duplicate when the account already holds a live row with
 * the same date, amount and imported payee, or the same imported id.
 *
 * Partial port: Actualist runs the full import-reconcile matcher (fuzzy matching, rules,
 * review) that this port does not have.
 */
class CsvImporter(private val database: BudgetDatabase, private val writes: TransactionWrites) {
    data class Plan(val drafts: List<CellDraft>, val imported: Int, val duplicates: Int)

    fun plan(accountId: String, rows: List<CsvImportRow>): Plan {
        val sql = database.sql
        if (!database.liveRowExists("accounts", accountId)) throw LocalFirstException.InvalidLocalWrite("missing account")
        val offBudget = writes.accountOffBudget(accountId)
        val categoriesByName = sql.query("SELECT id, name FROM categories WHERE (tombstone IS NULL OR tombstone = 0)")
            .associate { (it.string("name") ?: "").lowercase() to it.string("id")!! }
        val existing = sql.query(
            "SELECT date, amount, imported_description, financial_id FROM transactions WHERE acct = ? AND (tombstone IS NULL OR tombstone = 0)",
            accountId,
        )
        val seen = existing.map { Triple(it.long("date"), it.longOr0("amount"), it.string("imported_description")?.lowercase()) }.toMutableSet()
        val importedIds = existing.mapNotNull { it.string("financial_id") }.toMutableSet()
        val drafts = mutableListOf<CellDraft>()
        val createdPayees = HashMap<String, String>()
        var imported = 0
        var duplicates = 0
        for (row in rows) {
            val key = Triple(Months.packedDate(row.date).toLong(), row.amount, row.payeeName.ifEmpty { null }?.lowercase())
            if ((row.importedId != null && row.importedId in importedIds) || key in seen) {
                duplicates++
                continue
            }
            seen += key
            row.importedId?.let { importedIds += it }
            var payeeId: String? = null
            if (row.payeeName.isNotBlank()) {
                val known = createdPayees[row.payeeName.lowercase()]
                if (known != null) {
                    payeeId = known
                } else {
                    val (id, payeeDrafts) = writes.resolveOrCreatePayeeDrafts(null, row.payeeName)
                    drafts += payeeDrafts
                    createdPayees[row.payeeName.lowercase()] = id
                    payeeId = id
                }
            }
            val draft = TransactionDraft(
                accountId = accountId,
                date = row.date,
                amount = row.amount,
                categoryId = if (offBudget) null else row.categoryName?.let { categoriesByName[it.lowercase()] },
                notes = row.notes,
                cleared = row.cleared ?: true,
                importedPayee = row.payeeName.ifEmpty { null },
                importedId = row.importedId,
            )
            drafts += writes.simpleDrafts(draft, java.util.UUID.randomUUID().toString(), payeeId)
            imported++
        }
        return Plan(drafts, imported, duplicates)
    }

    /** Parses, maps and commits a file in one transaction; nothing is written on any error. */
    fun import(accountId: String, csv: String, delimiter: Char = ',', hasHeaderRow: Boolean = true): Plan {
        if (csv.toByteArray().size > CsvImportMapper.MAXIMUM_BYTES) throw CsvImportException("The file is larger than 10 MB.")
        val rows = CsvImportMapper.map(CsvParser.parse(csv, delimiter, hasHeaderRow))
        return database.commitLocal {
            val plan = plan(accountId, rows)
            plan.drafts to plan
        }.outcome
    }
}
