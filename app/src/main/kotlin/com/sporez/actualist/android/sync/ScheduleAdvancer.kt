package com.sporez.actualist.android.sync

import android.content.Context
import com.sporez.actualist.core.schedules.ScheduleAdvancementResult
import com.sporez.actualist.core.schedules.ScheduleAutoPostRefusal
import com.sporez.actualist.core.schedules.ScheduleRunMarker
import com.sporez.actualist.core.schedules.Schedules
import com.sporez.actualist.core.store.BudgetSession
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs Actual's schedule advancement (post due automatic schedules, move paid ones to their next
 * date) the way Actual and Actualist do: after a successful sync, when a budget is opened, and from
 * the background worker. It never runs for the demo budget. The "last run" day is remembered per
 * budget, so a day's run happens once unless it was interrupted.
 */
object ScheduleAdvancer {
    private const val PREFS = "schedule_advancement"

    private val refusalsByBudget = ConcurrentHashMap<String, List<ScheduleAutoPostRefusal>>()

    /** The automatic posts the latest real run refused for [budgetId] (the Schedules screen filters out stale ones). */
    fun refusals(budgetId: String): List<ScheduleAutoPostRefusal> = refusalsByBudget[budgetId].orEmpty()

    /** Forgets remembered refusals, for example when the budget is closed or erased. */
    fun clear(budgetId: String) {
        refusalsByBudget.remove(budgetId)
    }

    /**
     * Advances [session]'s schedules for [today] under the session's write lock and tells the
     * screens to reload. Returns null when nothing ran (demo budget or a failure that left the
     * marker unset so the next sync retries). Callers do this only after a successful sync.
     */
    suspend fun advance(
        context: Context,
        session: BudgetSession,
        today: String = LocalDate.now().toString(),
    ): ScheduleAdvancementResult? {
        if (session.isDemo) return null
        val marker = PrefsMarker(context.applicationContext, session.budgetId)
        val result = try {
            session.automatically { Schedules(database, clock).advance(today, marker) }
        } catch (error: Exception) {
            return null
        }
        if (!result.skippedForToday) refusalsByBudget[session.budgetId] = result.refusals
        return result
    }

    /** True when the run changed rows that need to be uploaded. */
    fun changedData(result: ScheduleAdvancementResult?): Boolean = result != null && (result.receipts.isNotEmpty() || result.scheduleMutated)

    private class PrefsMarker(context: Context, private val budgetId: String) : ScheduleRunMarker {
        private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        override fun lastRunDay(): String? = prefs.getString(budgetId, null)

        override fun markRun(dayId: String) {
            prefs.edit().putString(budgetId, dayId).apply()
        }
    }
}
