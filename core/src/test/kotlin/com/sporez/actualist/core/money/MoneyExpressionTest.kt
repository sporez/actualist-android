package com.sporez.actualist.core.money

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyExpressionTest {
    @Test
    fun plainAmountsMatchMoneyParse() {
        for (text in listOf("12", "12.5", "$1,234.56", "-4.99", "(4.99)", "", "abc", "1.234")) {
            assertEquals(text, Money.parse(text), MoneyExpression.evaluate(text))
        }
    }

    @Test
    fun addsAndSubtracts() {
        assertEquals(15550L, MoneyExpression.evaluate("120+35.5"))
        assertEquals(8775L, MoneyExpression.evaluate("100 - 12.25"))
        assertEquals(9000L, MoneyExpression.evaluate("100 - 12 + 2"))
        assertEquals(-200L, MoneyExpression.evaluate("-5+3"))
        assertEquals(800L, MoneyExpression.evaluate("5--3"))
        assertEquals(500L, MoneyExpression.evaluate("(4.99)+9.99"))
    }

    @Test
    fun trailingEqualsIsIgnored() {
        assertEquals(15550L, MoneyExpression.evaluate("120+35.5="))
        assertEquals(1250L, MoneyExpression.evaluate("12.5="))
    }

    @Test
    fun invalidExpressionsAreNull() {
        assertNull(MoneyExpression.evaluate("12+"))
        assertNull(MoneyExpression.evaluate("+"))
        assertNull(MoneyExpression.evaluate("1+x"))
        assertNull(MoneyExpression.evaluate("1.001+1"))
        assertNull(MoneyExpression.evaluate("(1+2)"))
    }

    @Test
    fun overflowAndMaximumAreRefused() {
        assertNull(MoneyExpression.evaluate("999999999999+999999999999"))
    }

    @Test
    fun isExpressionDetectsOperators() {
        assertTrue(MoneyExpression.isExpression("1+2"))
        assertFalse(MoneyExpression.isExpression("-5"))
        assertFalse(MoneyExpression.isExpression("12.5"))
    }
}
