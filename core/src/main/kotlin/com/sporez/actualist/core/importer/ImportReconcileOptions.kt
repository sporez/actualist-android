package com.sporez.actualist.core.importer

/**
 * The options upstream's `reconcileTransactions` / `matchTransactions` expose
 * (`packages/loot-core/src/server/accounts/sync.ts`). Bank Sync and CSV import run the same
 * reconcile; only these options differ. Port of Actualist `ImportReconcileOptions`.
 */
data class ImportReconcileOptions(
    /**
     * The rows come from a bank provider: each carries its own `imported_id` and a provider payee name,
     * and the apply never writes a missing one. When false (CSV), a matched update writes the incoming
     * `imported_id` and `imported_payee` even when absent, clearing the stored value.
     */
    val isBankSyncAccount: Boolean,
    /** Upstream's fuzzy query only considers rows with no `imported_id` when the incoming row has one. */
    val strictIdChecking: Boolean,
    /**
     * Null reads the account's `sync-reimport-deleted-<id>` preference. True re-imports rows the user
     * deleted, so no deleted id is suppressed; false always suppresses them.
     */
    val reimportDeleted: Boolean?,
    /** An inserted row whose source did not say whether it is cleared takes this value. */
    val defaultCleared: Boolean,
    /** `normalizePayeeName`: how a new payee name is spelled. */
    val payeeNameNormalization: ImportPayeeNameNormalization,
    /**
     * A matched row takes the download's date. Null reads the account's `sync-update-dates-<id>`
     * preference, which defaults to off; only Bank Sync leaves it null, file import never moves dates.
     */
    val updateDates: Boolean? = false,
) {
    companion object {
        /** Bank Sync as shipped in Actualist 0.8.1. */
        val BANK_SYNC = ImportReconcileOptions(
            isBankSyncAccount = true,
            strictIdChecking = false,
            reimportDeleted = null,
            defaultCleared = true,
            payeeNameNormalization = ImportPayeeNameNormalization.ORIGINAL,
            updateDates = null,
        )

        /** Upstream `transactions-import` defaults. */
        val CSV = ImportReconcileOptions(
            isBankSyncAccount = false,
            strictIdChecking = true,
            reimportDeleted = null,
            defaultCleared = true,
            payeeNameNormalization = ImportPayeeNameNormalization.TITLE_CASE,
        )
    }
}
