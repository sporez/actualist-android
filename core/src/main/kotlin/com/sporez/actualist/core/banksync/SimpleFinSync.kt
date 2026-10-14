package com.sporez.actualist.core.banksync

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.CellDraft
import com.sporez.actualist.core.db.Clock
import com.sporez.actualist.core.importer.BankSyncReconciliation.Candidate
import com.sporez.actualist.core.importer.ImportDays
import com.sporez.actualist.core.importer.ImportReconcileOptions
import com.sporez.actualist.core.importer.ImportReconcileReads
import com.sporez.actualist.core.importer.ImportReconciler
import com.sporez.actualist.core.importer.OpeningBalanceRequest
import com.sporez.actualist.core.sync.SyncValue
import com.sporez.actualist.core.transactions.AccountLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** An account with a remote bank link, as the Bank Sync screen lists it. `lastSync` is epoch milliseconds as text. */
data class SimpleFinLinkedAccount(
    val id: String,
    val name: String,
    val remoteAccountId: String,
    /** Kept verbatim: only `simpleFin` links are sync targets, other providers stay read-only. */
    val syncSource: String,
    val offBudget: Boolean,
    val lastSync: String?,
    val bankSyncStatus: String?,
) {
    val isSimpleFin: Boolean get() = syncSource == AccountLifecycle.SIMPLEFIN
}

/** What one account's sync did. A download-level provider error commits only [status] and counts as skipped. */
data class SimpleFinAccountResult(
    val localAccountId: String,
    val status: BankSyncStatus,
    val addedCount: Int = 0,
    val updatedCount: Int = 0,
    val openingBalanceAdded: Boolean = false,
    val insertedTransactionIds: List<String> = emptyList(),
    /** The provider error code of a failed account download (`ACCOUNT_MISSING`, `TIMED_OUT`, ...). */
    val errorCode: String? = null,
) {
    val skipped: Boolean get() = status != BankSyncStatus.OK
}

/** The run summary Actualist shows after a sync. Port of `BankSyncRunTally` wording. */
data class SimpleFinSyncSummary(val accounts: List<SimpleFinAccountResult>) {
    val added: Int get() = accounts.sumOf { it.addedCount }
    val updated: Int get() = accounts.sumOf { it.updatedCount }
    val openingBalancesAdded: Int get() = accounts.count { it.openingBalanceAdded }
    val skipped: Int get() = accounts.count { it.skipped }

    /** Accounts whose download failed, with the provider error code. */
    val errors: List<SimpleFinAccountResult> get() = accounts.filter { it.skipped }

    fun text(): String {
        val planned = accounts.size
        if (planned > 0 && skipped == planned) return "No accounts synced. $skipped skipped."
        val prefix = if (skipped > 0) "Synced ${planned - skipped} of $planned accounts. " else ""
        val suffix = when (skipped) {
            0 -> ""
            1 -> " · 1 account skipped"
            else -> " · $skipped accounts skipped"
        }
        return prefix + changesText() + suffix
    }

    private fun changesText(): String {
        val parts = ArrayList<String>()
        if (added > 0) parts.add(if (added == 1) "Added 1 transaction" else "Added $added transactions")
        if (updated > 0) parts.add(if (updated == 1) "Updated 1 match" else "Updated $updated matches")
        val openings = openingBalancesAdded
        if (openings > 0) parts.add(if (openings == 1) "Added 1 opening balance" else "Added $openings opening balances")
        return if (parts.isEmpty()) "Everything already matches." else parts.joinToString(" · ")
    }
}

/**
 * SimpleFIN bank sync over the Actual server's the `simplefin` routes routes, ported from Actualist's
 * `LocalFirstActualStore+BankSync` and `+BankSyncPlanning`: download, map provider rows to import candidates,
 * then reconcile through [ImportReconciler] with `ImportReconcileOptions.BANK_SYNC`.
 *
 * [token] is the Actual server session token (the same one the sync engine sends as `X-ACTUAL-TOKEN`); the
 * SimpleFIN setup token lives on the server. Every call is blocking, so run them off the main thread.
 */
class SimpleFinSync(
    private val database: BudgetDatabase,
    private val transport: SimpleFinTransport,
    private val token: String,
    private val clock: Clock = Clock.SYSTEM,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val sql get() = database.sql
    private val reads = ImportReconcileReads(database)
    private val reconciler = ImportReconciler(database, clock, newId)

    private fun todayUtc(): LocalDate = Instant.ofEpochMilli(clock.nowMillis()).atZone(ZoneOffset.UTC).toLocalDate()

    // region Server

    /** Whether the server hosts SimpleFIN and has a setup token. */
    fun serverSupport(): SimpleFinServerSupport = transport.status(token)

    /** The SimpleFIN-side accounts a local account can be linked to. Throws [SimpleFinException.NotConfigured] when the server cannot sync. */
    fun remoteAccounts(): List<SimpleFinRemoteAccount> {
        if (serverSupport() != SimpleFinServerSupport.CONFIGURED) throw SimpleFinException.NotConfigured()
        return transport.accounts(token) ?: throw SimpleFinException.NotConfigured()
    }

    // endregion

    // region Link

    /** Accounts with a remote link, live only, by lowercased name. Other providers' links are included verbatim. */
    fun linkedAccounts(): List<SimpleFinLinkedAccount> {
        if (!database.tableExists("accounts")) return emptyList()
        val columns = database.columnSet("accounts")
        if ("account_id" !in columns || "account_sync_source" !in columns) return emptyList()
        val offBudget = if ("offbudget" in columns) "offbudget" else "0"
        val lastSync = if ("last_sync" in columns) "last_sync" else "NULL"
        val status = if ("bank_sync_status" in columns) "bank_sync_status" else "NULL"
        val rows = sql.query(
            """
            SELECT id, name, $offBudget AS offbudget, account_id, account_sync_source, $lastSync AS last_sync, $status AS bank_sync_status
            FROM accounts
            WHERE ${BudgetDatabase.livePredicate(columns)} AND account_id IS NOT NULL AND account_id != ''
            ORDER BY lower(name)
            """.trimIndent(),
        )
        return rows.mapNotNull { row ->
            val id = row.string("id") ?: return@mapNotNull null
            val remoteId = row.string("account_id") ?: return@mapNotNull null
            val source = row.string("account_sync_source") ?: return@mapNotNull null
            SimpleFinLinkedAccount(
                id = id,
                name = row.string("name") ?: "",
                remoteAccountId = remoteId,
                syncSource = source,
                offBudget = (row.long("offbudget") ?: 0L) != 0L,
                lastSync = row.string("last_sync"),
                bankSyncStatus = row.string("bank_sync_status"),
            )
        }
    }

    /**
     * loot-core `linkSimpleFinAccount`, applied locally as CRDT cells: `account_id`, `account_sync_source =
     * "simpleFin"` and `bank`, with the `banks` row found or created on `(bank_id, name)` (bank id from the
     * org domain, else the org id; name from the org name, else the institution). Like Actualist it does not
     * download anything; call [sync] afterwards.
     */
    fun link(localAccountId: String, remote: SimpleFinRemoteAccount) {
        val bankId = (remote.orgDomain ?: remote.orgId)?.takeIf { it.isNotEmpty() } ?: throw SimpleFinException.MissingBankId()
        val bankName = remote.orgName ?: remote.institution
        database.requiredColumns("accounts", listOf("account_id", "account_sync_source", "bank"))
        database.commitLocal {
            if (!database.liveRowExists("accounts", localAccountId)) throw LocalFirstException.InvalidLocalWrite("missing account")
            val drafts = ArrayList<CellDraft>()
            val existingBank = findBank(bankId, bankName)
            val bankRow = existingBank ?: newId()
            if (existingBank == null) {
                drafts.add(CellDraft("banks", bankRow, "bank_id", SyncValue.text(bankId)))
                if (bankName != null) drafts.add(CellDraft("banks", bankRow, "name", SyncValue.text(bankName)))
            }
            drafts.add(CellDraft("accounts", localAccountId, "account_id", SyncValue.text(remote.accountId)))
            drafts.add(CellDraft("accounts", localAccountId, "account_sync_source", SyncValue.text(AccountLifecycle.SIMPLEFIN)))
            drafts.add(CellDraft("accounts", localAccountId, "bank", SyncValue.text(bankRow)))
            drafts to Unit
        }
    }

    /** loot-core `unlinkAccount`: clears a SimpleFIN link's cells and leaves its transactions. */
    fun unlink(localAccountId: String) {
        val linked = linkedAccounts().firstOrNull { it.id == localAccountId } ?: throw SimpleFinException.NotLinked()
        if (!linked.isSimpleFin) throw SimpleFinException.NotSimpleFinLinked()
        AccountLifecycle(database, clock, newId).unlink(localAccountId)
    }

    /** `(bank_id, name)` find: the same pair reuses the row, the same bank id with another name is a distinct bank. */
    private fun findBank(bankId: String, name: String?): String? {
        if (!database.tableExists("banks")) return null
        val live = BudgetDatabase.livePredicate(database.columnSet("banks"))
        return if (name != null) {
            sql.queryString("SELECT id FROM banks WHERE bank_id = ? AND name = ? AND $live LIMIT 1", bankId, name)
        } else {
            sql.queryString("SELECT id FROM banks WHERE bank_id = ? AND name IS NULL AND $live LIMIT 1", bankId)
        }
    }

    // endregion

    // region Sync

    /** Downloads and applies one linked account. [today] bounds the lookback and the first-sync opening-balance day. */
    fun sync(localAccountId: String, today: LocalDate = todayUtc()): SimpleFinSyncSummary = syncAccounts(listOf(localAccountId), today)

    /** Syncs every open SimpleFIN-linked account in one batched request (one transactions call for the run). */
    fun syncAll(today: LocalDate = todayUtc()): SimpleFinSyncSummary {
        val closed = if (database.tableExists("accounts") && "closed" in database.columnSet("accounts")) {
            sql.query("SELECT id FROM accounts WHERE closed = 1").mapNotNull { it.string("id") }.toSet()
        } else {
            emptySet()
        }
        val ids = linkedAccounts().filter { it.isSimpleFin && it.id !in closed }.map { it.id }
        return syncAccounts(ids, today)
    }

    private class Prepared(
        val account: SimpleFinLinkedAccount,
        val download: SimpleFinAccountDownload,
        val candidates: List<Candidate>,
        val problems: List<BankSyncProblem>,
    )

    private fun syncAccounts(accountIds: List<String>, today: LocalDate): SimpleFinSyncSummary {
        if (accountIds.isEmpty()) return SimpleFinSyncSummary(emptyList())
        val linkedById = linkedAccounts().associateBy { it.id }
        val linked = accountIds.distinct().map { id ->
            val account = linkedById[id] ?: throw SimpleFinException.NotLinked()
            if (!account.isSimpleFin) throw SimpleFinException.NotSimpleFinLinked()
            account
        }

        if (transport.status(token) != SimpleFinServerSupport.CONFIGURED) throw SimpleFinException.NotConfigured()

        val currency = budgetCurrency()
        val todayDayId = ImportDays.dayId(today)
        val oldest = linked.map { reads.oldestLiveTransactionDayId(it.id, todayDayId) }
        val startDates = oldest.map { BankSyncAmounts.lookbackStartDate(it, today) }

        val response = try {
            transport.transactions(token, linked.map { it.remoteAccountId }, startDates) ?: throw SimpleFinException.NotConfigured()
        } catch (error: SimpleFinException) {
            recordFailure(error, linked)
            throw error
        }

        // Balance metadata improves first-sync opening-balance accuracy but never discards a valid batch.
        val needsBalances = oldest.any { it == null }
        val remoteById = LinkedHashMap<String, SimpleFinRemoteAccount>()
        if (needsBalances) {
            val remotes = try {
                transport.accounts(token).orEmpty()
            } catch (error: SimpleFinException) {
                emptyList()
            }
            for (remote in remotes) if (remote.accountId !in remoteById) remoteById[remote.accountId] = remote
        }

        val payeeIds = payeeIdsByName()
        val prepared = linked.map { account ->
            val download = resolvedDownload(account.remoteAccountId, response)
            if (download.hasError) {
                Prepared(account, download, emptyList(), emptyList())
            } else {
                prepare(account, download, currency, payeeIds)
            }
        }

        // Preflight every account before the first write, so a malformed row in a later account cannot leave
        // earlier accounts applied.
        val problems = prepared.flatMap { it.problems }
        if (problems.isNotEmpty()) throw SimpleFinException.UnresolvedProblems(problems)

        val results = ArrayList<SimpleFinAccountResult>()
        for ((index, item) in prepared.withIndex()) {
            val account = item.account
            val status = BankSyncStatus.fromErrorCode(item.download.errorCode)
            if (item.download.hasError) {
                commitCompletion(account.id, null, status, BalanceDisposition.Preserve)
                results.add(SimpleFinAccountResult(account.id, status, errorCode = item.download.errorCode))
                continue
            }
            val disposition = BankSyncBalancePlanning.disposition(item.download, remoteById[account.remoteAccountId], currency)
            val opening = if (oldest[index] == null && disposition is BalanceDisposition.Known) {
                OpeningBalanceRequest(disposition.minorUnits)
            } else {
                null
            }
            val applied = reconciler.applyCandidates(
                accountId = account.id,
                candidates = item.candidates,
                options = ImportReconcileOptions.BANK_SYNC,
                today = today,
                openingBalance = opening,
            )
            commitCompletion(account.id, clock.nowMillis(), status, disposition)
            results.add(
                SimpleFinAccountResult(
                    localAccountId = account.id,
                    status = status,
                    addedCount = applied.insertedCount,
                    updatedCount = applied.updatedCount,
                    openingBalanceAdded = applied.openingBalanceAdded,
                    insertedTransactionIds = applied.insertedIdsByAccount[account.id].orEmpty(),
                ),
            )
        }
        return SimpleFinSyncSummary(results)
    }

    /** Port of `resolvedDownload`: a missing account is `ACCOUNT_MISSING`; a whole-request error fills in where the account had none. */
    private fun resolvedDownload(remoteAccountId: String, response: SimpleFinTransactionsResponse): SimpleFinAccountDownload {
        var download = response.downloads[remoteAccountId]
        if (download == null && response.errorCode == null) {
            download = SimpleFinAccountDownload(emptyList(), errorCode = "ACCOUNT_MISSING")
        }
        if (response.hasWholeRequestError && download?.errorCode == null) {
            download = SimpleFinAccountDownload(
                transactions = download?.transactions.orEmpty(),
                currentBalance = download?.currentBalance,
                startingBalance = download?.startingBalance,
                errorType = response.errorType,
                errorCode = response.errorCode,
            )
        }
        return download ?: SimpleFinAccountDownload(emptyList(), errorCode = "ACCOUNT_MISSING")
    }

    /** Port of `prepareDownload`: provider rows to import candidates, with per-row problems that fail closed. */
    private fun prepare(
        account: SimpleFinLinkedAccount,
        download: SimpleFinAccountDownload,
        currency: BudgetCurrency,
        payeeIds: Map<String, String>,
    ): Prepared {
        val result = BankSyncCandidates.prepare(download, importPreferences(account.id), currency, payeeIds)
        return Prepared(account, download, result.candidates, result.problems)
    }

    // endregion

    // region Reads and metadata writes

    /** `sync-import-pending-*` and `sync-import-notes-*` default to true; custom mappings come back raw. */
    private fun importPreferences(accountId: String): BankSyncImportPreferences = BankSyncImportPreferences(
        importPending = (reads.preference("sync-import-pending-$accountId") ?: "true") == "true",
        importNotes = (reads.preference("sync-import-notes-$accountId") ?: "true") == "true",
        customMappingsJson = reads.preference("custom-sync-mappings-$accountId"),
    )

    /** The budget currency from `defaultCurrencyCode`; absent means currency-neutral at two decimals. */
    fun budgetCurrency(): BudgetCurrency = BudgetCurrency.catalog(reads.preference("defaultCurrencyCode") ?: "")

    /** Live non-transfer payees by lowercased name; the first row wins, like Actualist's lookup. */
    private fun payeeIdsByName(): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        if (!database.tableExists("payees")) return result
        val columns = database.columnSet("payees")
        val transfer = if ("transfer_acct" in columns) "transfer_acct" else "NULL"
        val rows = sql.query("SELECT id, name, $transfer AS transfer_acct FROM payees WHERE ${BudgetDatabase.livePredicate(columns)}")
        for (row in rows) {
            val id = row.string("id")
            if (id.isNullOrEmpty() || !row.string("transfer_acct").isNullOrEmpty()) continue
            val key = (row.string("name") ?: "").lowercase()
            if (key !in result) result[key] = id
        }
        return result
    }

    /**
     * Completion metadata after the transaction writes: status always, `last_sync` (epoch milliseconds as
     * text) and the balance only on success. A failed download preserves both, as Actualist does.
     */
    private fun commitCompletion(accountId: String, lastSyncMillis: Long?, status: BankSyncStatus, balance: BalanceDisposition) {
        database.commitLocal {
            val columns = database.columnSet("accounts")
            val drafts = ArrayList<CellDraft>()
            if ("bank_sync_status" in columns) drafts.add(CellDraft("accounts", accountId, "bank_sync_status", SyncValue.text(status.raw)))
            if (lastSyncMillis != null && "last_sync" in columns) {
                drafts.add(CellDraft("accounts", accountId, "last_sync", SyncValue.text(lastSyncMillis.toString())))
            }
            if ("balance_current" in columns) {
                when (balance) {
                    is BalanceDisposition.Known -> drafts.add(CellDraft("accounts", accountId, "balance_current", SyncValue.int(balance.minorUnits)))
                    BalanceDisposition.Clear -> drafts.add(CellDraft("accounts", accountId, "balance_current", SyncValue.Null))
                    BalanceDisposition.Preserve -> Unit
                }
            }
            drafts to Unit
        }
    }

    /** A failed provider call is shared status; setup errors (bad URL) are not recorded. Best effort. */
    private fun recordFailure(error: SimpleFinException, accounts: List<SimpleFinLinkedAccount>) {
        val status = when (error) {
            is SimpleFinException.InvalidUrl, is SimpleFinException.InsecureUrl -> return
            is SimpleFinException.TimedOut -> BankSyncStatus.TIMED_OUT
            else -> BankSyncStatus.FAILED
        }
        for (account in accounts) {
            try {
                commitCompletion(account.id, null, status, BalanceDisposition.Preserve)
            } catch (failure: LocalFirstException) {
                // A rolled-back status write has no durable effect; the caller still sees the provider failure.
            }
        }
    }

    // endregion
}
