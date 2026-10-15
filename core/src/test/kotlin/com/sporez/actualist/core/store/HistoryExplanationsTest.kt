package com.sporez.actualist.core.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryExplanationsTest {
    @Test
    fun theNewestAppliedRowCanBeUndone() {
        assertNull(HistoryExplanations.undoBlockedReason(index = 0, undone = false, newestAppliedIndex = 0))
    }

    @Test
    fun olderAppliedRowsAskForTheNewestActionFirst() {
        assertEquals(
            "Undo the newest action before this one.",
            HistoryExplanations.undoBlockedReason(index = 1, undone = false, newestAppliedIndex = 0),
        )
    }

    @Test
    fun undoneRowsNeedNoExplanation() {
        assertNull(HistoryExplanations.undoBlockedReason(index = 2, undone = true, newestAppliedIndex = 0))
    }

    @Test
    fun automaticPostsCannotBeUndone() {
        assertEquals(
            "This was posted automatically and can't be undone.",
            HistoryExplanations.undoBlockedReason(index = 0, undone = false, newestAppliedIndex = 0, automatic = true),
        )
    }

    @Test
    fun nothingAppliedMeansNothingToExplain() {
        assertNull(HistoryExplanations.undoBlockedReason(index = 0, undone = false, newestAppliedIndex = -1))
    }
}
