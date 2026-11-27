package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.ApiException
import com.northphoenix.hairdresserclientmanager.state.ChangeOutcome
import com.northphoenix.hairdresserclientmanager.state.ContactImportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `a contact whose save was never answered, or was refused as already made, may be a Client`() {
        assertTrue(ContactImportResult.mayBeClient(ApiException("No reply from the server.", code = ApiException.NO_REPLY)))
        // An earlier try was saved although its reply never came (ADR 0015).
        assertTrue(ContactImportResult.mayBeClient(ApiException("This request was already carried out with other values.", code = "CONFLICT")))
    }

    @Test
    fun `a contact that was never sent, or was refused, is no Client`() {
        assertFalse(ContactImportResult.mayBeClient(ApiException("No connection.", isNetwork = true)))
        assertFalse(ContactImportResult.mayBeClient(ApiException("Unauthorized", code = "UNAUTHORIZED")))
        assertFalse(ContactImportResult.mayBeClient(ApiException("Name is too long.", code = "BAD_REQUEST")))
        assertFalse(ContactImportResult.mayBeClient(ApiException("Internal server error.", code = "INTERNAL_SERVER_ERROR")))
    }
}
