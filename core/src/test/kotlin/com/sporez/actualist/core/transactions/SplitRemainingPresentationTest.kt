package com.sporez.actualist.core.transactions

import org.junit.Assert.assertEquals
import org.junit.Test

class SplitRemainingPresentationTest {
    @Test
    fun anExpenseWithEntriesTooSmallReadsAsLeft() {
        // Parent -100.00, entries -60.00: parent minus entries is -40.00, shown as 40.00 left.
        assertEquals("$40.00 left", SplitRemainingPresentation.statusText(remaining = -4000, parentSignedAmount = -10000))
    }

    @Test
    fun anExpenseWithEntriesTooLargeReadsAsOver() {
        assertEquals("$5.00 over", SplitRemainingPresentation.statusText(remaining = 500, parentSignedAmount = -10000))
    }

    @Test
    fun anIncomeSplitKeepsTheSign() {
        assertEquals("$25.00 left", SplitRemainingPresentation.statusText(remaining = 2500, parentSignedAmount = 10000))
        assertEquals("$25.00 over", SplitRemainingPresentation.statusText(remaining = -2500, parentSignedAmount = 10000))
    }

    @Test
    fun balancedReadsAsRemaining() {
        assertEquals("$0.00 Remaining", SplitRemainingPresentation.statusText(remaining = 0, parentSignedAmount = -10000))
    }
}
