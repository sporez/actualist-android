package com.sporez.actualist.core.money

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyFormatTest {
    @After
    fun resetCurrent() {
        MoneyFormat.current = MoneyFormat.DEFAULT
    }

    private fun format(vararg preferences: Pair<String, String>): MoneyFormat {
        val map = preferences.toMap()
        return MoneyFormat.fromPreferences { map[it] }
    }

    @Test
    fun defaultIsDollarsAndKeepsTheOldBehaviour() {
        assertEquals("$1,234.56", Money.format(123_456))
        assertEquals("-$0.05", Money.format(-5))
        assertEquals("1234.56", Money.format(123_456, symbol = "", grouping = false))
        assertEquals(123_456L, Money.parse("$1,234.56"))
        assertEquals(-499L, Money.parse("(4.99)"))
        assertNull(Money.parse("1.234"))
    }

    @Test
    fun euroUsesCommaDecimalAndSymbolAfter() {
        val euro = format("defaultCurrencyCode" to "EUR")
        assertEquals("1.234,56 €", euro.format(123_456))
        assertEquals("-0,05 €", euro.format(-5))
        assertEquals("1234,56", euro.editText(123_456))
        assertEquals(123_456L, euro.parse("1.234,56 €"))
        assertEquals(1_250L, euro.parse("12,5"))
        assertEquals(1_250L, euro.parse("12.50"))
        assertEquals(150_000L, euro.parse("1.500"))
    }

    @Test
    fun poundIsSymbolFirst() {
        val pound = format("defaultCurrencyCode" to "GBP")
        assertEquals("£1,234.56", pound.format(123_456))
        assertEquals(123_456L, pound.parse("£1,234.56"))
    }

    @Test
    fun yenHasNoFraction() {
        val yen = format("defaultCurrencyCode" to "JPY")
        assertEquals("¥1,235", yen.format(1_235))
        assertEquals("1235", yen.editText(1_235))
        assertEquals(1_235L, yen.parse("1,235"))
        assertNull(yen.parse("12.5"))
    }

    @Test
    fun francUsesApostropheGrouping() {
        val franc = format("defaultCurrencyCode" to "CHF")
        assertEquals("Fr.1’234.56", franc.format(123_456))
        assertEquals(123_456L, franc.parse("Fr. 1'234.56"))
    }

    @Test
    fun explicitNumberFormatsOverrideTheCurrency() {
        val none = "defaultCurrencyCode" to ""
        assertEquals("1,234,567.89", format(none, "numberFormat" to "comma-dot").format(123_456_789))
        assertEquals("1.234.567,89", format(none, "numberFormat" to "dot-comma").format(123_456_789))
        assertEquals("1 234 567,89", format(none, "numberFormat" to "space-comma").format(123_456_789))
        assertEquals("1’234’567.89", format(none, "numberFormat" to "apostrophe-dot").format(123_456_789))
        assertEquals("12,34,567.89", format(none, "numberFormat" to "comma-dot-in").format(123_456_789))
        assertEquals(123_456_789L, format(none, "numberFormat" to "space-comma").parse("1 234 567,89"))
        assertEquals(123_456_789L, format(none, "numberFormat" to "comma-dot-in").parse("12,34,567.89"))
    }

    @Test
    fun hideFractionRoundsDisplayOnly() {
        val hidden = format("hideFraction" to "true")
        assertEquals("$1,235", hidden.format(123_456))
        assertEquals("-$1,235", hidden.format(-123_456))
        assertEquals("1235", hidden.editText(123_456))
        assertEquals(123_500L, hidden.parse("1235"))
    }

    @Test
    fun symbolPositionAndSpacingPreferencesWin() {
        val euro = format("defaultCurrencyCode" to "EUR", "currencySymbolPosition" to "before", "currencySpaceBetweenAmountAndSymbol" to "false")
        assertEquals("€1.234,56", euro.format(123_456))
    }

    @Test
    fun moneyFollowsTheCurrentFormat() {
        MoneyFormat.current = format("defaultCurrencyCode" to "EUR")
        assertEquals("1,00 €", Money.format(100))
        assertEquals(1_050L, Money.parse("10,5"))
        assertEquals("10,50", Money.format(1_050, symbol = "", grouping = false))
    }
}
