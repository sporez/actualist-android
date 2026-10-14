package com.sporez.actualist.core.csv

import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.demo.DemoBudget
import com.sporez.actualist.core.sync.SyncTimestamp
import com.sporez.actualist.core.transactions.AccountWrites
import com.sporez.actualist.core.transactions.LedgerReads
import com.sporez.actualist.core.transactions.TransactionQuery
import com.sporez.actualist.core.transactions.TransactionWrites
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Exporter cases ported from Actualist `TransactionCSVEncoderTests`; parser cases use its fixture inputs. */
class CsvTest {
    private fun row(
        id: String,
        family: String? = null,
        account: String = "Checking",
        payee: String = "Shop",
        notes: String? = null,
        group: String = "Food",
        category: String = "Groceries",
        date: String = "2026-09-01",
        amount: Long = -1000,
        cleared: Boolean = false,
        reconciled: Boolean = false,
        parent: Boolean = false,
        child: Boolean = false,
    ) = CsvExportRow(id, family ?: id, account, date, payee, notes, group, category, amount, parent, child, cleared, reconciled)

    @Test
    fun writesPinnedActualHeaderRowsLfAndFinalNewline() {
        val csv = CsvExport.encode(listOf(row("one", amount = -12345, cleared = true)))
        assertEquals(
            "Account,Date,Payee,Notes,Category_Group,Category,Amount,Split_Amount,Cleared\nChecking,2026-09-01,Shop,,Food,Groceries,-123.45,0,Cleared\n",
            csv,
        )
        assertEquals("Transactions-20260928-000000.csv", CsvExport.filename(SyncTimestamp.wallTimeMillis("2026-09-28T00:00:00.000Z")!!))
    }

    @Test
    fun quotesControlsAndGuardsFormulasButNotNegativeNumbers() {
        val csv = CsvExport.encode(listOf(row("one", payee = "=shop,\"x\"", notes = "line 1\r\nline 2", amount = -2500)))
        assertTrue(csv.contains("\"'=shop,\"\"x\"\"\",\"line 1\r\nline 2\""))
        assertTrue(csv.contains(",-25,0,Not cleared\n"))
        for (trigger in listOf("=", "+", "-", "@", "\t", "\r", "\r\n")) {
            val text = CsvExport.encode(listOf(row("t", account = "${trigger}input")))
            assertTrue(trigger, text.contains("'${trigger}input"))
        }
        val dated = CsvExport.encode(listOf(row("date", date = "=2026-09-01")))
        assertTrue(dated.contains(",'=2026-09-01,"))
    }

    @Test
    fun writesSplitMarkersReconciledStatusAndExtremeAmounts() {
        val csv = CsvExport.encode(
            listOf(
                row("parent", family = "parent", notes = "envelope", amount = -5000, parent = true),
                row("child-a", family = "parent", amount = -3000, child = true),
                row("child-b", family = "parent", amount = -2000, reconciled = true, child = true),
            ),
        )
        assertTrue(csv.contains("(SPLIT INTO 2) envelope"))
        assertTrue(csv.contains("(SPLIT 1 OF 2) "))
        assertTrue(csv.contains("(SPLIT 2 OF 2) ,Food,Groceries,-20,0,Reconciled"))
        assertTrue(csv.contains(",0,-50,Not cleared"))
        val extreme = CsvExport.encode(listOf(row("empty", account = "", payee = "", group = "", category = "", amount = Long.MIN_VALUE)))
        assertTrue(extreme.contains(",2026-09-01,,,,,-92233720368547758.08,0,Not cleared\n"))
    }

    @Test
    fun parsesActualistsCsvFixtureInputs() {
        val fixture = Json.parseToJsonElement(javaClass.getResourceAsStream("/fixtures/csv/import-cases.json")!!.bufferedReader().readText()).jsonObject
        val cases = fixture["cases"]!!.jsonArray.associate { it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject }
        fun parse(id: String): CsvParser.Table {
            val case = cases.getValue(id)
            val options = case["options"]!!.jsonObject
            return CsvParser.parse(
                case["csv"]!!.jsonPrimitive.content,
                options["delimiter"]!!.jsonPrimitive.content.single(),
                options["hasHeaderRow"]!!.jsonPrimitive.content.toBoolean(),
            )
        }
        assertEquals(8, cases.size)
        parse("utf8-bom-header-comma").let {
            assertEquals(listOf("Date", "Payee", "Notes", "Amount"), it.headers)
            assertEquals(listOf(listOf("2026-09-27", "Sample Market", "Groceries", "12.34")), it.rows)
        }
        parse("quoted-delimiter-and-escaped-quote").let { assertEquals(listOf("2026-09-27", "Market, North", "Said \"hello\"", "12.34"), it.rows.single()) }
        parse("quoted-embedded-newline").let { assertEquals("First line\nSecond line", it.rows.single()[2]) }
        parse("tab-delimiter").let { assertEquals(listOf("2026-09-27", "Sample Store", "12.34"), it.rows.single()) }
        parse("relaxed-short-row").let {
            assertEquals(listOf("2026-09-28", "Second Store"), it.rows[1])
            assertNull(it.value("Amount", it.rows[1]))
        }
        parse("relaxed-extra-column").let { assertEquals(listOf("2026-09-27", "Sample Store", "12.34"), it.rows.single()) }
        parse("blank-lines").let { assertEquals(1, it.rows.size) }
        parse("headerless-configured-columns").let {
            assertNull(it.headers)
            val mapped = CsvImportMapper.map(it).single()
            assertEquals(1234L, mapped.amount)
            assertEquals("Sample Store", mapped.payeeName)
            assertEquals("synthetic note", mapped.notes)
        }
    }

    @Test
    fun mappingIsStrictAndAllOrNothing() {
        assertEquals(-499L, CsvImportMapper.amountMinorUnits("-4.99"))
        assertEquals(1250L, CsvImportMapper.amountMinorUnits("+12.5"))
        assertNull(CsvImportMapper.amountMinorUnits("1.234"))
        assertNull(CsvImportMapper.amountMinorUnits("1,234.00"))
        assertNull(CsvImportMapper.strictIsoDate("2026-02-30"))
        assertNull(CsvImportMapper.strictIsoDate("09/27/2026"))
        val bad = CsvParser.parse("Date,Payee,Amount\n2026-09-27,A,1.00\n2026-09-28,B,abc\n")
        val error = assertThrows(CsvImportException::class.java) { CsvImportMapper.map(bad) }
        assertTrue(error.message!!.contains("Row 2"))
        assertThrows(CsvImportException::class.java) { CsvImportMapper.map(CsvParser.parse("Date,Payee,Amount\n2026-09-27,A,0\n")) }
    }

    @Test
    fun exportThenImportRoundTripsAndSkipsDuplicates() {
        val clock = TestClock()
        DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, LocalDate.of(2026, 10, 15), clock).use { database ->
            val export = CsvExport.rows(database, "credit")
            val csv = CsvExport.encode(export)
            assertTrue(csv.startsWith("Account,Date,Payee"))
            val target = AccountWrites(database, clock).create("Imported Copy", offBudget = false, openingBalance = 0, date = LocalDate.of(2026, 10, 15)).accountId
            val importer = CsvImporter(database, TransactionWrites(database, clock))
            val first = importer.import(target, csv)
            assertEquals(export.size, first.imported)
            assertEquals(0, first.duplicates)
            val ledger = LedgerReads(database)
            val accounts = ledger.accounts().associateBy { it.id }
            assertEquals(accounts.getValue("credit").balance, accounts.getValue(target).balance)
            // Categories resolve by name, so imported spending lands in the same categories.
            val imported = ledger.transactions(TransactionQuery(accountId = target, limit = 10_000))
            assertTrue(imported.count { it.categoryName != null } > imported.size / 2)
            // Importing the same file again writes nothing.
            val second = importer.import(target, csv)
            assertEquals(0, second.imported)
            assertEquals(export.size, second.duplicates)
            assertEquals(accounts.getValue(target).balance, ledger.accounts().first { it.id == target }.balance)
        }
    }
}
