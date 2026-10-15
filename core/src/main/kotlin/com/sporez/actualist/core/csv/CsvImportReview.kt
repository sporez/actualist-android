package com.sporez.actualist.core.csv

import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.Clock
import com.sporez.actualist.core.importer.ImportDecisionKind
import com.sporez.actualist.core.importer.ImportReconcileOptions
import com.sporez.actualist.core.importer.ImportReconciler
import java.time.LocalDate

/** How one field of Actualist's CSV format is read from the file: a named column, or a position without a header row. */
data class CsvColumn(val field: String, val header: String?, val position: Int?, val required: Boolean, val found: Boolean)

/** One file row and what importing it would do (the reconcile's decision for it). */
data class CsvReviewRow(val index: Int, val row: CsvImportRow, val kind: ImportDecisionKind) {
    /** Only rows that would write something can be left out. */
    val selectable: Boolean get() = kind == ImportDecisionKind.ADDED || kind == ImportDecisionKind.UPDATED
}

data class CsvImportReview(
    val columns: List<CsvColumn>,
    val rows: List<CsvReviewRow>,
    val added: Int,
    val updated: Int,
    val unchanged: Int,
    val skipped: Int,
) {
    /** The rows a review starts with included. */
    val defaultIncluded: Set<Int> get() = rows.filter { it.selectable }.map { it.index }.toSet()
}

/**
 * Actualist's CSV import review: parses the file, previews the reconcile (nothing is written) so each
 * row is shown as new, an update of an existing row, or an already-present duplicate, and then imports
 * only the rows the user kept.
 */
class CsvImportReviewer(
    private val database: BudgetDatabase,
    private val clock: Clock = Clock.SYSTEM,
    private val today: LocalDate = LocalDate.now(),
) {
    fun review(accountId: String, csv: String, delimiter: Char = ',', hasHeaderRow: Boolean = true): CsvImportReview {
        if (csv.toByteArray().size > CsvImportMapper.MAXIMUM_BYTES) throw CsvImportException("The file is larger than 10 MB.")
        val table = CsvParser.parse(csv, delimiter, hasHeaderRow)
        val parsed = CsvImportMapper.map(table)
        val importer = CsvImporter(database, clock, today)
        val plan = ImportReconciler(database, clock).preview(accountId, importer.rows(parsed), ImportReconcileOptions.CSV, today)
        val rows = parsed.mapIndexed { index, row ->
            val kind = plan.decisions.firstOrNull { it.sourceIndex == index }?.kind ?: ImportDecisionKind.ADDED
            CsvReviewRow(index, row, kind)
        }
        return CsvImportReview(
            columns = columns(table),
            rows = rows,
            added = plan.addedCount,
            updated = plan.updatedCount,
            unchanged = plan.unchangedCount,
            skipped = plan.skippedCount,
        )
    }

    /** Imports the review's rows except the selectable ones not in [included], in one commit. */
    fun apply(accountId: String, review: CsvImportReview, included: Set<Int>): CsvImporter.Plan {
        val kept = review.rows.filter { !it.selectable || it.index in included }.map { it.row }
        val importer = CsvImporter(database, clock, today)
        val result = ImportReconciler(database, clock).apply(accountId, importer.rows(kept), ImportReconcileOptions.CSV, today)
        return CsvImporter.Plan(
            imported = result.insertedCount,
            updated = result.updatedCount,
            duplicates = result.plan.unchangedCount,
            skipped = result.plan.skippedCount,
        )
    }

    companion object {
        private val fields = listOf(
            Triple("Date", 0, true),
            Triple("Payee", 1, false),
            Triple("Notes", 2, false),
            Triple("Amount", 3, true),
            Triple("Category", -1, false),
            Triple("Cleared", -1, false),
            Triple("imported_id", -1, false),
        )

        fun columns(table: CsvParser.Table): List<CsvColumn> {
            val headers = table.headers
            return fields.mapNotNull { (field, position, required) ->
                if (headers != null) {
                    val header = headers.firstOrNull { it.equals(field, ignoreCase = true) }
                    CsvColumn(field, header, headers.indexOfFirst { it.equals(field, ignoreCase = true) }.takeIf { it >= 0 }, required, header != null)
                } else if (position >= 0) {
                    CsvColumn(field, null, position, required, true)
                } else {
                    null
                }
            }
        }
    }
}
