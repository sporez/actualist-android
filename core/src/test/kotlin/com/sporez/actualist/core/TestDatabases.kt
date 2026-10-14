package com.sporez.actualist.core

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.Clock
import com.sporez.actualist.core.sync.SyncTimestamp
import java.io.File
import java.nio.file.Files

/** Mutable fixed clock for deterministic tests. */
class TestClock(var millis: Long = SyncTimestamp.wallTimeMillis("2026-10-15T12:00:00.000Z")!!) : Clock {
    override fun nowMillis(): Long = millis
    fun advance(ms: Long) {
        millis += ms
    }
}

object TestDatabases {
    val driver = BundledSQLiteDriver()

    fun tempFile(): File {
        val dir = Files.createTempDirectory("actualist-test").toFile()
        dir.deleteOnExit()
        return File(dir, "db.sqlite")
    }

    fun starter(nodeId: String? = "node1", clock: Clock = TestClock(), file: File = tempFile()): BudgetDatabase =
        BudgetDatabase.createStarter(driver, file.absolutePath, nodeId, clock)

    fun reopen(file: File, nodeId: String? = "node1", clock: Clock = TestClock()): BudgetDatabase =
        BudgetDatabase.open(driver, file.absolutePath, nodeId, clock)
}
