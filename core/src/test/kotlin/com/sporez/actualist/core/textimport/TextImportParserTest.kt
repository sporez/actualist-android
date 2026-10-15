package com.sporez.actualist.core.textimport

import com.sporez.actualist.core.transactions.Account
import com.sporez.actualist.core.transactions.CategoryOption
import com.sporez.actualist.core.transactions.Payee
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class TextImportParserTest {
    private fun parse(text: String): TextImportParse = TextImportParser.parse(text) ?: throw AssertionError("No parse for: " + text)

    @Test
    fun spendWithPayeeAndAccount() {
        val result = parse("Spent \$12.50 on Fresh Market in Checking")
        assertEquals(1250L, result.amountMinorUnits)
        assertEquals(TextImportDirection.SPEND, result.direction)
        assertEquals("Fresh Market", result.payeeText)
        assertEquals("Checking", result.accountText)
        assertNull(result.categoryText)
    }

    @Test
    fun receivedIsInflow() {
        val result = parse("received 1,200 at Employer")
        assertEquals(120000L, result.amountMinorUnits)
        assertEquals(TextImportDirection.INFLOW, result.direction)
        assertEquals("Employer", result.payeeText)
    }

    @Test
    fun bareAmountDefaultsToSpendAndFirstWordIsThePayee() {
        val result = parse("\$4.99 Netflix Entertainment Visa")
        assertEquals(499L, result.amountMinorUnits)
        assertEquals(TextImportDirection.SPEND, result.direction)
        assertEquals("Netflix", result.payeeText)
        assertEquals("Entertainment", result.categoryText)
        assertEquals("Visa", result.accountText)
    }

    @Test
    fun transferNamesBothAccounts() {
        val result = parse("Transfer \$50 from Checking to Savings")
        assertEquals(TextImportDirection.TRANSFER, result.direction)
        assertEquals(5000L, result.amountMinorUnits)
        assertEquals("Checking", result.accountText)
        assertEquals("Savings", result.destinationAccountText)
    }

    @Test
    fun isoDateIsExtractedAndNotTakenAsTheAmount() {
        val result = parse("Card purchase 2026-03-07 \$8.25 at Corner Cafe")
        assertEquals(825L, result.amountMinorUnits)
        assertEquals(LocalDate.of(2026, 3, 7), result.date)
        assertEquals("Corner Cafe", result.payeeText)
    }

    @Test
    fun noAmountReturnsNull() {
        assertNull(TextImportParser.parse("hello there"))
        assertNull(TextImportParser.parse("   "))
    }

    @Test
    fun resolverMatchesNamesIgnoringCase() {
        val accounts = listOf(Account("a1", "Checking", false, false, 0, 0, 1.0), Account("a2", "Savings", false, false, 0, 0, 2.0))
        val payees = listOf(Payee("p1", "Fresh Market", null), Payee("p2", "Transfer : Savings", "a2"))
        val categories = listOf(CategoryOption("c1", "Groceries", "Food", false, false))
        val draft = TextImportResolver.resolve(parse("spent 12.50 on fresh market in checking"), accounts, payees, categories)
        assertEquals("a1", draft.accountId)
        assertEquals("p1", draft.payeeId)
        assertEquals("", draft.newPayeeName)
        assertEquals(true, draft.outflow)

        val unknown = TextImportResolver.resolve(parse("spent 3 on Nowhere Shop"), accounts, payees, categories)
        assertNull(unknown.payeeId)
        assertEquals("Nowhere Shop", unknown.newPayeeName)

        val moved = TextImportResolver.resolve(parse("transfer 50 from checking to savings"), accounts, payees, categories)
        assertEquals("a1", moved.accountId)
        assertEquals("p2", moved.payeeId)
    }
}
