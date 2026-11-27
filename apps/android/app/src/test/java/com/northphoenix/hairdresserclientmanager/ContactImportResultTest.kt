package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.state.ContactImportResult
import com.northphoenix.hairdresserclientmanager.state.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

class ContactImportResultTest {
    @Test
    fun `an import that added Clients saved them, also when others failed`() {
        assertEquals(Outcome.Saved, ContactImportResult(added = setOf(1, 2), failed = emptySet()).outcome)
        assertEquals(Outcome.Saved, ContactImportResult(added = setOf(1), failed = setOf(2, 3), reason = R.string.network_error).outcome)
        assertEquals(Outcome.Saved, ContactImportResult(added = setOf(1), failed = setOf(2), unanswered = true).outcome)
    }

    @Test
    fun `an import that added nobody saved nothing`() {
        // The server could not be reached, or it refused each contact: there is nothing to reload, and nothing was saved.
        assertEquals(Outcome.NothingSaved, ContactImportResult(added = emptySet(), failed = setOf(1, 2), reason = R.string.network_error).outcome)
        assertEquals(Outcome.NothingSaved, ContactImportResult(added = emptySet(), failed = setOf(1)).outcome)
    }

    @Test
    fun `an import whose only save was never answered may have saved it`() {
        assertEquals(Outcome.Unanswered, ContactImportResult(added = emptySet(), failed = setOf(1, 2), unanswered = true).outcome)
    }
}
