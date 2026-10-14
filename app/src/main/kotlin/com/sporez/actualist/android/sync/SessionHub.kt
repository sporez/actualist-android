package com.sporez.actualist.android.sync

import com.sporez.actualist.core.store.BudgetSession

/**
 * Process-wide owner of open budget sessions, reference counted, so the UI and background sync
 * share one [BudgetSession] (one SQLite connection, writes serialized by its lock) instead of
 * opening a second connection to the same file.
 */
class SessionHub {
    private class Held(val session: BudgetSession, var count: Int)

    private val held = HashMap<String, Held>()

    /** The open session for [budgetId], or a new one from [open]. Pair every call with [release]. */
    @Synchronized
    fun acquire(budgetId: String, open: () -> BudgetSession): BudgetSession {
        held[budgetId]?.let { existing ->
            existing.count += 1
            return existing.session
        }
        val session = open()
        held[budgetId] = Held(session, 1)
        return session
    }

    /** Closes the session once its last holder releases it. */
    @Synchronized
    fun release(session: BudgetSession) {
        val entry = held[session.budgetId]
        if (entry == null || entry.session !== session) {
            // Not held (already closed by closeAll, or never acquired here): closing twice is harmless.
            runCatching { session.close() }
            return
        }
        entry.count -= 1
        if (entry.count == 0) {
            held.remove(session.budgetId)
            session.close()
        }
    }

    /**
     * Closes every open session, whoever holds it. For resetting the app's data (tests delete the
     * budget files): a session left open by an activity that never finished would otherwise keep
     * serving a file that no longer exists.
     */
    @Synchronized
    fun closeAll() {
        for (entry in held.values) runCatching { entry.session.close() }
        held.clear()
    }
}
