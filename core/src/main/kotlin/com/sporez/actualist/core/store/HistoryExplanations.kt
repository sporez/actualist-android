package com.sporez.actualist.core.store

/**
 * Why a History row offers no Undo, in Actualist's wording (`BudgetActionUndoOrder`): undo is strictly
 * newest-first, and an automatic post (a schedule posting itself) is never undoable.
 */
object HistoryExplanations {
    const val NEWEST_FIRST = "Undo the newest action before this one."
    const val AUTOMATIC = "This was posted automatically and can't be undone."

    /**
     * The reason the applied row at [index] (newest first) cannot be undone now, or null when it can be
     * (it is the newest applied row at [newestAppliedIndex]) or when it is already undone.
     */
    fun undoBlockedReason(index: Int, undone: Boolean, newestAppliedIndex: Int, automatic: Boolean = false): String? {
        if (undone) return null
        if (automatic) return AUTOMATIC
        return if (newestAppliedIndex >= 0 && index != newestAppliedIndex) NEWEST_FIRST else null
    }
}
