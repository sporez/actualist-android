package com.sporez.actualist.core.transactions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionRowPresentationTest {
    private fun row(
        id: String,
        amount: Long,
        payeeId: String? = null,
        payeeName: String? = null,
        isParent: Boolean = false,
        cleared: Boolean = false,
        reconciled: Boolean = false,
        children: List<TransactionRow> = emptyList(),
    ) = TransactionRow(
        id = id, accountId = "checking", accountName = "Checking", date = null, amount = amount,
        payeeId = payeeId, payeeName = payeeName, categoryId = null, categoryName = null, notes = null,
        cleared = cleared, reconciled = reconciled, isParent = isParent, isChild = false, parentId = null,
        transferId = null, transferAccountName = null, startingBalance = false, children = children,
    )

    @Test
    fun statusFollowsReconciledThenCleared() {
        assertEquals(RowStatus.RECONCILED, TransactionRowPresentation.status(row("a", -1, cleared = true, reconciled = true)))
        assertEquals(RowStatus.CLEARED, TransactionRowPresentation.status(row("a", -1, cleared = true)))
        assertEquals(RowStatus.UNCLEARED, TransactionRowPresentation.status(row("a", -1)))
    }

    @Test
    fun aSplitParentShowsItsMostCommonChildPayee() {
        val children = listOf(
            row("c1", -100, "p-farm", "Farm Stand"),
            row("c2", -200, "p-cafe", "Cafe"),
            row("c3", -300, "p-farm", "Farm Stand"),
        )
        val parent = row("p", -600, isParent = true, children = children)
        assertEquals(RowPayee("Farm Stand (+1 more)", false), TransactionRowPresentation.payee(parent))
        assertEquals(RowPayee("Farm Stand", false), TransactionRowPresentation.payee(parent.copy(children = listOf(children[0], children[2]))))
        assertEquals(RowPayee(TransactionRowPresentation.SPLIT_NO_PAYEE, true), TransactionRowPresentation.payee(parent.copy(children = emptyList())))
        assertEquals(RowPayee(TransactionRowPresentation.NO_PAYEE, true), TransactionRowPresentation.payee(row("x", -1)))
    }

    @Test
    fun splitRemainderReadsLeftOrOverForTheParentsDirection() {
        val parent = row("p", -1_000, isParent = true, children = listOf(row("c1", -400), row("c2", -100)))
        assertEquals(-500L, TransactionRowPresentation.splitRemaining(parent))
        assertEquals("$5.00 left", TransactionRowPresentation.splitStatusText(parent) { "$" + (it / 100) + ".00" })
        val over = parent.copy(children = listOf(row("c1", -900), row("c2", -300)))
        assertEquals("$2.00 over", TransactionRowPresentation.splitStatusText(over) { "$" + (it / 100) + ".00" })
        assertNull(TransactionRowPresentation.splitRemaining(parent.copy(children = listOf(row("c1", -600), row("c2", -400)))))
        assertNull(TransactionRowPresentation.splitRemaining(row("simple", -5)))
    }

    @Test
    fun theLeadingEmojiIsSplitFromTheCategoryName() {
        assertEquals(CategoryNameParts("🍔", "Dining"), TransactionRowPresentation.categoryNameParts("🍔 Dining"))
        assertEquals(CategoryNameParts(null, "Dining"), TransactionRowPresentation.categoryNameParts("  Dining "))
        assertFalse(TransactionRowPresentation.categoryNameParts("Groceries").emoji != null)
        assertTrue(TransactionRowPresentation.categoryNameParts("🍔").emoji != null)
        assertEquals("🍔", TransactionRowPresentation.categoryNameParts("🍔").name)
    }
}
