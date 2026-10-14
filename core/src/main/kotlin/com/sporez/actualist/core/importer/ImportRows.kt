package com.sporez.actualist.core.importer

import com.sporez.actualist.core.importer.BankSyncReconciliation.Candidate
import java.time.LocalDate

/**
 * One incoming row of a file import, after parsing: what upstream's `transactions-import` hands to
 * `normalizeTransactions`. Amounts are integer minor units.
 */
data class ImportRow(
    val date: LocalDate,
    val amount: Long,
    /** The payee text as read; it is trimmed and normalized when the candidate is built. */
    val payeeName: String = "",
    /** The payee cell was present and exactly empty: the imported payee is then stored as '' (as upstream does). */
    val payeeCellWasEmpty: Boolean = false,
    /** An existing payee id, used instead of resolving [payeeName] by name. */
    val payeeId: String? = null,
    /** Overrides the imported payee, which otherwise is the normalized payee name. */
    val importedPayee: String? = null,
    val notes: String? = null,
    /** A category named in the file, resolved case-insensitively against live categories. */
    val categoryName: String? = null,
    /** An existing category id, used instead of resolving [categoryName]. */
    val categoryId: String? = null,
    /** The row's `imported_id` (`financial_id`). Empty counts as none. */
    val importedId: String? = null,
    /** Null when the file never said whether the row is cleared; an insert then takes `defaultCleared`. */
    val cleared: Boolean? = null,
    /** The row's line in the source file, for the caller's review. */
    val sourceLine: Int = 0,
)

/**
 * Maps parsed rows onto the shared reconciler's candidates, the same shape upstream's `normalizeTransactions`
 * hands to `matchTransactions`. Port of Actualist `TransactionCSVImportCandidates`. Pure: rule projection,
 * matching and writes happen downstream.
 */
object ImportCandidates {
    fun candidates(rows: List<ImportRow>, lookup: ImportLookup, options: ImportReconcileOptions): List<Candidate> =
        rows.map { candidate(it, lookup, options) }

    fun candidate(row: ImportRow, lookup: ImportLookup, options: ImportReconcileOptions): Candidate {
        // `normalizeTransactions`: the payee name is trimmed and normalized, an empty one is a null payee, and
        // imported_payee is that same text ('' for an exactly empty cell, as upstream stores it).
        val trimmed = row.payeeName.trim()
        val payeeName = if (trimmed.isEmpty()) null else options.payeeNameNormalization.normalize(trimmed)
        return Candidate(
            financialId = row.importedId?.takeIf { it.isNotEmpty() },
            // Calendar day to YYYYMMDD: no instant, no time zone.
            dayId = ImportDays.dayId(row.date),
            amount = row.amount,
            payeeId = row.payeeId ?: payeeName?.let { lookup.payeeIdByName[it.lowercase()] },
            payeeName = payeeName,
            notes = row.notes,
            categoryId = row.categoryId ?: row.categoryName?.let { lookup.categoryIdByName[it.lowercase()] },
            cleared = row.cleared ?: false,
            importedPayee = row.importedPayee ?: payeeName ?: (if (row.payeeCellWasEmpty) "" else null),
            clearedIsExplicit = row.cleared != null,
        )
    }
}
