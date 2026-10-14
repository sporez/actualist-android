package com.sporez.actualist.core.notes

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.db.CellDraft
import com.sporez.actualist.core.demo.DemoBudget
import com.sporez.actualist.core.sync.SyncValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class EntityNotesTest {
    @Test
    fun userTextAndDirectivesStaySeparate() {
        val body = NoteBody("Weekly shop\r\n#template 400\n  #goal 1000")
        assertEquals("Weekly shop", body.userBody)
        assertEquals(listOf("#template 400", "  #goal 1000"), body.reservedLines)
        assertEquals("Big shop\n#template 400\n  #goal 1000", body.persistedNote("Big shop"))
        assertEquals("#template 400\n  #goal 1000", body.persistedNote("  "))
        assertNull(NoteBody("old").persistedNote(""))
        assertNull(NoteBody("#template 10").displayText)
    }

    @Test
    fun accountNotesUseActualsNoteIdAndKeepDirectives() {
        val clock = TestClock()
        DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, LocalDate.of(2026, 10, 15), clock).use { db ->
            val notes = EntityNotes(db)
            val written = notes.write(NoteTarget.Account("checking"), "Synthetic joint account")
            assertEquals(listOf("account-checking"), written.map { it.row })
            assertEquals("Synthetic joint account", notes.read(NoteTarget.Account("checking")))
            assertTrue(notes.write(NoteTarget.Account("checking"), "Synthetic joint account").isEmpty())

            db.commitLocal(listOf(CellDraft("notes", "groceries", "note", SyncValue.text("Shop list\n#template 300"))))
            notes.write(NoteTarget.Category("groceries"), "New list")
            assertEquals("New list\n#template 300", db.sql.queryString("SELECT note FROM notes WHERE id = 'groceries'"))
            assertEquals("New list", notes.read(NoteTarget.Category("groceries")))

            assertThrows(LocalFirstException.InvalidLocalWrite::class.java) { notes.write(NoteTarget.Account("missing"), "x") }
        }
    }
}
