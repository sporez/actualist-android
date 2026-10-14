package com.sporez.actualist.core.banksync

import com.sporez.actualist.core.importer.BankSyncOracleFixture
import com.sporez.actualist.core.importer.ImportDays
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

class BankSyncMappingTest {
    private val usd = BudgetCurrency.catalog("USD")
    private val neutral = BudgetCurrency.NONE
    private val jpy = BudgetCurrency.catalog("JPY")

    // region Amounts

    @Test
    fun decimalStringsBecomeMinorUnitsAtTheCurrencyScale() {
        assertEquals(-1500L, BankSyncAmounts.minorUnits("-15.00", usd))
        assertEquals(300L, BankSyncAmounts.minorUnits("+3", usd))
        assertEquals(1L, BankSyncAmounts.minorUnits("0.005", usd))
        assertEquals(1235L, BankSyncAmounts.minorUnits("12.345", usd))
        assertEquals(-1235L, BankSyncAmounts.minorUnits("-12.345", usd))
        assertEquals(0L, BankSyncAmounts.minorUnits("0.00", usd))
        assertEquals(1500L, BankSyncAmounts.minorUnits("1500", jpy))
        assertEquals(16L, BankSyncAmounts.minorUnits("15.5", jpy))
        assertEquals(123456789012L, BankSyncAmounts.minorUnits(" 1234567890.12 ", usd))
    }

    @Test
    fun junkIsNeverGuessedIntoAnAmount() {
        for (junk in listOf(null, "", " ", "-", "+", ".50", "1.2.3", "abc", "1,000.00", "1e3", "--5", "5-", "$5", "NaN", "- 5")) {
            assertNull("junk $junk", BankSyncAmounts.minorUnits(junk, usd))
        }
        assertNull(BankSyncAmounts.minorUnits("99999999999999999999.00", usd))
    }

    @Test
    fun aNeutralBudgetAcceptsSameScaleCodesAndAnExplicitOneNeedsAMatch() {
        assertTrue(neutral.accepts("eur"))
        assertFalse(neutral.accepts("JPY"))
        assertFalse(neutral.accepts("  "))
        assertTrue(usd.accepts(" usd "))
        assertFalse(usd.accepts("EUR"))
        assertTrue(jpy.accepts("JPY"))
        assertEquals(0, jpy.decimalPlaces)
    }

    @Test
    fun dayIdsAreUtcDaysAndLookbackIsEightyNineDaysBack() {
        // 2026-10-10T23:30:00Z is still the 10th in UTC.
        assertEquals("20261010", BankSyncAmounts.dayId(1_791_675_000L))
        assertEquals("20261011", BankSyncAmounts.dayId(1_791_678_600L))
        assertEquals(1_791_633_600L, SimpleFinDays.unixSeconds("2026-10-10"))
        assertNull(SimpleFinDays.unixSeconds("2026-02-30"))
        assertNull(SimpleFinDays.unixSeconds("2026-2-3"))
        val today = LocalDate.parse("2026-10-15")
        assertEquals("2026-07-18", BankSyncAmounts.lookbackStartDate(null, today))
        assertEquals("2026-07-18", BankSyncAmounts.lookbackStartDate("20200101", today))
        assertEquals("2026-09-01", BankSyncAmounts.lookbackStartDate("20260901", today))
        assertEquals("20260901", ImportDays.dayId(LocalDate.parse("2026-09-01")))
    }

    @Test
    fun errorCodesMapToTheDurableStatusVocabulary() {
        assertEquals("ok", BankSyncStatus.fromErrorCode(null).raw)
        assertEquals("timed-out", BankSyncStatus.fromErrorCode("timed_out").raw)
        assertEquals("account-missing", BankSyncStatus.fromErrorCode("ACCOUNT_MISSING").raw)
        assertEquals("attention-required", BankSyncStatus.fromErrorCode("ACCOUNT_NEEDS_ATTENTION").raw)
        assertEquals("rate-limit-exceeded", BankSyncStatus.fromErrorCode("RATE_LIMIT_EXCEEDED").raw)
        assertEquals("reauth-required", BankSyncStatus.fromErrorCode("INVALID_ACCESS_TOKEN").raw)
        assertEquals("reauth-required", BankSyncStatus.fromErrorCode("UNAUTHORIZED").raw)
        assertEquals("failed", BankSyncStatus.fromErrorCode("SOMETHING_ELSE").raw)
    }

    // endregion

    // region Balance

    private val noError = SimpleFinAccountDownload(emptyList())

    @Test
    fun balanceEvidencePrefersTheDownloadThenTheRemoteAccountThenTheStartingBalance() {
        val remote = SimpleFinRemoteAccount("r", "Checking", balance = "50.00", currency = "USD")
        val withBalance = noError.copy(currentBalance = SimpleFinBalanceAmount("100.00", "USD"))
        assertEquals(BalanceDisposition.Known(10000), BankSyncBalancePlanning.disposition(withBalance, remote, usd))
        assertEquals(BalanceDisposition.Known(5000), BankSyncBalancePlanning.disposition(noError, remote, usd))
        assertEquals(BalanceDisposition.Known(777), BankSyncBalancePlanning.disposition(noError.copy(startingBalance = 777), null, usd))
        assertEquals(BalanceDisposition.Clear, BankSyncBalancePlanning.disposition(noError.copy(startingBalance = 777), null, jpy))
        assertEquals(BalanceDisposition.Clear, BankSyncBalancePlanning.disposition(noError, null, usd))
        // A balance in another currency, or with none, is cleared rather than trusted.
        assertEquals(BalanceDisposition.Clear, BankSyncBalancePlanning.disposition(noError.copy(currentBalance = SimpleFinBalanceAmount("1.00", "EUR")), null, usd))
        assertEquals(BalanceDisposition.Clear, BankSyncBalancePlanning.disposition(noError.copy(currentBalance = SimpleFinBalanceAmount("1.00", null)), null, usd))
        assertEquals(BalanceDisposition.Preserve, BankSyncBalancePlanning.disposition(noError.copy(errorCode = "TIMED_OUT"), remote, usd))
    }

    // endregion

    // region Field mapping

    private fun transaction(vararg raw: Pair<String, RawValue>, payee: String? = "Synthetic Market", notes: String? = null) = SimpleFinRemoteTransaction(
        id = "sf-1",
        dateUnixSeconds = 1_791_633_600L,
        amount = "-15.00",
        currency = "USD",
        payeeName = payee,
        notes = notes,
        booked = true,
        rawFields = RawFields(mapOf(*raw)),
    )

    @Test
    fun defaultMappingKeepsTheCanonicalFieldsAndEscapesNotes() {
        val resolved = BankSyncFieldMapping.resolve(transaction(notes = " groceries #food "), -1500, BankSyncFieldMapping.DEFAULTS, importNotes = true)!!
        assertEquals(1_791_633_600L, resolved.dateUnixSeconds)
        assertEquals("Synthetic Market", resolved.payeeName)
        assertEquals("groceries ##food", resolved.notes)
        assertNull(BankSyncFieldMapping.resolve(transaction(notes = "x"), -1500, BankSyncFieldMapping.DEFAULTS, importNotes = false)!!.notes)
        assertNull(BankSyncFieldMapping.resolve(transaction(notes = "   "), -1500, BankSyncFieldMapping.DEFAULTS, importNotes = true)!!.notes)
    }

    @Test
    fun customMappingPicksTheSideByAmountSignAndReadsExtraKeys() {
        val mappings = BankSyncFieldMapping.parse(
            """{"payment":{"date":"date","payee":"creditorName","notes":"remittance"},"deposit":{"date":"posted","payee":"debtorName","notes":"remittance"}}""",
        )
        val raw = arrayOf(
            "creditorName" to RawValue.Text("Creditor One"),
            "debtorName" to RawValue.Text("Debtor Two"),
            "remittance" to RawValue.Text("invoice 7"),
            "posted" to RawValue.Integer(1_791_720_000L),
            "extra" to RawValue.Obj(mapOf("memo" to RawValue.Text("flattened"))),
        )
        val payment = BankSyncFieldMapping.resolve(transaction(*raw), -1500, mappings, importNotes = true)!!
        assertEquals("Creditor One", payment.payeeName)
        assertEquals("invoice 7", payment.notes)
        assertEquals(1_791_633_600L, payment.dateUnixSeconds)
        val deposit = BankSyncFieldMapping.resolve(transaction(*raw), 2500, mappings, importNotes = true)!!
        assertEquals("Debtor Two", deposit.payeeName)
        assertEquals(1_791_720_000L, deposit.dateUnixSeconds)
        // A zero amount is a payment, as upstream's `amount <= 0`.
        assertEquals("Creditor One", BankSyncFieldMapping.resolve(transaction(*raw), 0, mappings, importNotes = true)!!.payeeName)
        // `extra` keys are mappable, and a missing mapped source falls back to the canonical payee.
        val viaExtra = BankSyncFieldMapping.Mappings(mapOf("payee" to "memo"), null)
        assertEquals("flattened", BankSyncFieldMapping.resolve(transaction(*raw), -1, viaExtra, importNotes = true)!!.payeeName)
        val missing = BankSyncFieldMapping.Mappings(mapOf("payee" to "nope"), null)
        assertEquals("Synthetic Market", BankSyncFieldMapping.resolve(transaction(*raw), -1, missing, importNotes = true)!!.payeeName)
    }

    @Test
    fun aMissingMappingSideAndInvalidMappingJsonFailClosed() {
        val paymentOnly = BankSyncFieldMapping.parse("""{"payment":{"payee":"payeeName"}}""")
        assertNull(BankSyncFieldMapping.resolve(transaction(), 2500, paymentOnly, importNotes = true))
        for (bad in listOf("not json", "[]", """{"payment":[]}""", """{"payment":{"payee":5}}""", """{"payment":null}""")) {
            try {
                BankSyncFieldMapping.parse(bad)
                fail("accepted $bad")
            } catch (expected: BankSyncFieldMapping.ParseException) {
                // fails closed
            }
        }
        val download = SimpleFinAccountDownload(listOf(transaction()))
        val result = BankSyncCandidates.prepare(download, BankSyncImportPreferences(customMappingsJson = "nope"), usd, emptyMap())
        assertEquals(listOf(BankSyncProblem.INVALID_CUSTOM_MAPPING), result.problems)
        assertTrue(result.candidates.isEmpty())
    }

    // endregion

    // region Candidates

    @Test
    fun rowProblemsAreReportedPerRow() {
        fun tx(id: String? = "sf", currency: String? = "USD", amount: String? = "-1.00", payee: String? = "P", booked: Boolean? = true, seconds: Long? = 1_791_633_600L) =
            SimpleFinRemoteTransaction(id, seconds, amount, currency, payee, null, booked)

        fun messages(transaction: SimpleFinRemoteTransaction, budget: BudgetCurrency = usd) =
            BankSyncCandidates.prepare(SimpleFinAccountDownload(listOf(transaction)), BankSyncImportPreferences(), budget, emptyMap()).problems.map { it.message }

        assertEquals(listOf("Missing transaction ID"), messages(tx(id = "  ")))
        assertEquals(listOf("Missing transaction currency"), messages(tx(currency = null)))
        assertEquals(listOf("Currency mismatch (EUR bank transaction, USD budget)"), messages(tx(currency = "eur")))
        assertEquals(listOf("Currency mismatch (EUR bank transaction, none budget)"), messages(tx(currency = "EUR", amount = "1.00"), BudgetCurrency.catalog("JPY").copy(code = "")))
        assertEquals(listOf("Unreadable amount"), messages(tx(amount = "1,00")))
        assertEquals(listOf("Unreadable date"), messages(tx(seconds = null)))
        assertEquals(listOf("Missing payee"), messages(tx(payee = "  ")))
        assertTrue(messages(tx(booked = null)).isEmpty())
    }

    @Test
    fun pendingRowsStayPendingAndAreSkippedWhenImportPendingIsOff() {
        val pending = SimpleFinRemoteTransaction("p", 1_791_633_600L, "-1.00", "USD", " Cafe ", null, null)
        val booked = SimpleFinRemoteTransaction("b", 1_791_633_600L, "-2.00", "USD", "Cafe", null, true)
        val download = SimpleFinAccountDownload(listOf(pending, booked))
        val all = BankSyncCandidates.prepare(download, BankSyncImportPreferences(), usd, mapOf("cafe" to "pay-cafe"))
        assertEquals(listOf(false, true), all.candidates.map { it.cleared })
        assertEquals(listOf("Cafe", "Cafe"), all.candidates.map { it.payeeName })
        assertEquals(listOf("pay-cafe", "pay-cafe"), all.candidates.map { it.payeeId })
        assertEquals(listOf("Cafe", "Cafe"), all.candidates.map { it.importedPayee })
        assertEquals(listOf("p", "b"), all.candidates.map { it.financialId })
        assertEquals(listOf("20261010", "20261010"), all.candidates.map { it.dayId })
        val skipping = BankSyncCandidates.prepare(download, BankSyncImportPreferences(importPending = false), usd, emptyMap())
        assertEquals(listOf("b"), skipping.candidates.map { it.financialId })
    }

    // endregion

    // region Oracle replay of the provider-side cases

    /**
     * The BankSync oracle cases the importer replay takes as oracle-only because they test the provider-side
     * mapping. They are replayed here from the recorded provider rows to the recorded candidates. Payee ids and
     * the provider `category` are not compared: Actualist resolves payees at apply time and never imports a
     * provider category.
     */
    @Test
    fun theProviderSideOracleCasesProduceTheRecordedCandidates() {
        val replayed = listOf(
            "named-import-pending-off",
            "named-import-notes-off",
            "named-import-notes-hash-escape",
            "named-custom-mapping",
            "named-provider-zero-amount",
        )
        for (id in replayed) {
            val entry = BankSyncOracleFixture.entry(id)
            val setup = entry["setup"]!!.jsonObject
            val prefs = (setup["prefs"] as? JsonObject) ?: JsonObject(emptyMap())
            fun pref(prefix: String) = prefs["$prefix-acc-checking"]?.jsonPrimitive?.content
            val preferences = BankSyncImportPreferences(
                importPending = (pref("sync-import-pending") ?: "true") == "true",
                importNotes = (pref("sync-import-notes") ?: "true") == "true",
                customMappingsJson = pref("custom-sync-mappings"),
            )
            // The upstream rows carry no currency; the Actualist wire adds the budget's.
            val rows = entry["call"]!!.jsonObject["transactions"]!!.jsonArray.map { row ->
                val obj = row.jsonObject
                val amount = obj["transactionAmount"]!!.jsonObject
                JsonObject(obj + ("transactionAmount" to JsonObject(amount + ("currency" to JsonPrimitive("USD")))))
            }
            val wire = JsonObject(
                mapOf("acc" to JsonObject(mapOf("transactions" to JsonObject(mapOf("all" to JsonArray(rows)))))),
            )
            val download = SimpleFinWire.transactions(wire).downloads.getValue("acc")
            val result = BankSyncCandidates.prepare(download, preferences, usd, emptyMap())
            assertTrue("$id problems ${result.problems}", result.problems.isEmpty())
            val recorded = entry["candidates"]!!.jsonArray.map { it.jsonObject }
            assertEquals("$id count", recorded.size, result.candidates.size)
            for ((index, expected) in recorded.withIndex()) {
                val actual = result.candidates[index]
                assertEquals("$id date", expected["date"]!!.jsonPrimitive.content, ImportDays.iso(actual.dayId))
                assertEquals("$id amount", expected["amount"]!!.jsonPrimitive.content.toLong(), actual.amount)
                assertEquals("$id imported_id", expected["imported_id"]!!.jsonPrimitive.content, actual.financialId)
                assertEquals("$id imported_payee", expected["imported_payee"]!!.jsonPrimitive.content, actual.importedPayee)
                val notes = expected["notes"]!!.let { if (it is JsonPrimitive && it.isString) it.content else null }
                assertEquals("$id notes", notes, actual.notes)
                assertEquals("$id cleared", expected["cleared"]!!.jsonPrimitive.content == "true", actual.cleared)
            }
        }
    }

    // endregion
}
