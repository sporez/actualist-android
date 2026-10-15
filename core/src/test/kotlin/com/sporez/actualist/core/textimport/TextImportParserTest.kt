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

    private fun assertShape(text: String, amount: Long, direction: TextImportDirection, payee: String?, account: String?, destination: String?, category: String?) {
        val result = parse(text)
        assertEquals(text, amount, result.amountMinorUnits)
        assertEquals(text, direction, result.direction)
        assertEquals(text, payee, result.payeeText)
        assertEquals(text, account, result.accountText)
        assertEquals(text, destination, result.destinationAccountText)
        assertEquals(text, category, result.categoryText)
    }

    /** The accepted shapes from Actualist's ShortcutTextImportParserTests. */
    @Test
    fun actualistAcceptedShapes() {
        assertShape("\$12.50 coffee", 1250, TextImportDirection.SPEND, "coffee", null, null, null)
        assertShape("12.50 at Starbucks", 1250, TextImportDirection.SPEND, "Starbucks", null, null, null)
        assertShape("Coffee 12.50", 1250, TextImportDirection.SPEND, "Coffee", null, null, null)
        assertShape("spent 12.50 on coffee in Checking", 1250, TextImportDirection.SPEND, "coffee", "Checking", null, null)
        assertShape("received 200 paycheck", 20000, TextImportDirection.INFLOW, "paycheck", null, null, null)
        assertShape("12.50 coffee Groceries Checking", 1250, TextImportDirection.SPEND, "coffee", "Checking", null, "Groceries")
        assertShape("transfer 50 from Checking to Savings", 5000, TextImportDirection.TRANSFER, null, "Checking", "Savings", null)
    }

    @Test
    fun actualistCurrencySymbolsAndCaseAreOptional() {
        val result = parse("SPENT \$12.5 ON Coffee")
        assertEquals(1250L, result.amountMinorUnits)
        assertEquals(TextImportDirection.SPEND, result.direction)
        assertEquals("Coffee", result.payeeText)
    }

    @Test
    fun actualistDatesAreNotTreatedAsAmounts() {
        val result = parse("2026-04-01 coffee 12.50")
        assertEquals(1250L, result.amountMinorUnits)
        assertEquals("coffee", result.payeeText)
    }

    @Test
    fun actualistMultiWordPayeeAfterAtIsPreserved() {
        val result = parse("12.50 at Starbucks Reserve")
        assertEquals(1250L, result.amountMinorUnits)
        assertEquals("Starbucks Reserve", result.payeeText)
        assertNull(result.categoryText)
    }

    @Test
    fun actualistInflowKeywordsAndLeftoverAccount() {
        assertEquals(TextImportDirection.INFLOW, parse("got 15 paycheck").direction)
        assertEquals(1500L, parse("got 15 paycheck").amountMinorUnits)
        assertEquals(TextImportDirection.INFLOW, parse("inflow 20 bonus").direction)
        assertEquals(TextImportDirection.SPEND, parse("spend 8 snacks").direction)
        assertEquals(125050L, parse("\$1,250.50 coffee").amountMinorUnits)
        val leftover = parse("spent 9 on coffee in Checking Extra")
        assertEquals("coffee", leftover.payeeText)
        assertEquals("Checking Extra", leftover.accountText)
        assertNull(TextImportParser.parse("coffee at Starbucks"))
        assertNull(TextImportParser.parse("transfer nope from Checking to Savings"))
    }
}
