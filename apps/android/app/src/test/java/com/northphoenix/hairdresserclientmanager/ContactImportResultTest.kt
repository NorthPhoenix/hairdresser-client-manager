package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.state.ContactImportResult
import com.northphoenix.hairdresserclientmanager.state.ChangeOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

class ContactImportResultTest {
    @Test
    fun `an import that added Clients saved them, also when others failed`() {
        assertEquals(ChangeOutcome.Saved, ContactImportResult(added = setOf(1, 2), failed = emptySet()).outcome)
        assertEquals(ChangeOutcome.Saved, ContactImportResult(added = setOf(1), failed = setOf(2, 3), reason = R.string.network_error).outcome)
        assertEquals(ChangeOutcome.Saved, ContactImportResult(added = setOf(1), failed = setOf(2), unsure = true).outcome)
    }

    @Test
    fun `an import that added nobody saved nothing`() {
        // The server could not be reached, or it refused each contact: there is nothing to reload, and nothing was saved.
        assertEquals(ChangeOutcome.NothingSaved, ContactImportResult(added = emptySet(), failed = setOf(1, 2), reason = R.string.network_error).outcome)
        assertEquals(ChangeOutcome.NothingSaved, ContactImportResult(added = emptySet(), failed = setOf(1)).outcome)
    }

    @Test
    fun `an import that added nobody, but one of whose contacts may be a Client, is unsure`() {
        // A save sent and never answered, or refused as one the server had already made: the Client list is loaded again.
        assertEquals(ChangeOutcome.Unsure, ContactImportResult(added = emptySet(), failed = setOf(1, 2), unsure = true).outcome)
    }
}
