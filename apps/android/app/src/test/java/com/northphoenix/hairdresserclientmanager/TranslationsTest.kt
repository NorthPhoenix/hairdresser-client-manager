package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.auth.AuthState
import com.northphoenix.hairdresserclientmanager.auth.SessionCheck
import com.northphoenix.hairdresserclientmanager.data.ApiException
import com.northphoenix.hairdresserclientmanager.data.isConnectionFailure
import com.northphoenix.hairdresserclientmanager.domain.Amount
import com.northphoenix.hairdresserclientmanager.i18n.messageRes
import com.northphoenix.hairdresserclientmanager.i18n.refusalRes
import com.northphoenix.hairdresserclientmanager.i18n.refusedSignInMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.xml.parsers.DocumentBuilderFactory

/** Guards the two string files: every translatable English string needs a matching Russian one. */
class TranslationsTest {
    private class Entry(val text: String, val translatable: Boolean)

    private fun strings(directory: String): Map<String, Entry> {
        // Unit tests run from the module directory.
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/$directory/strings.xml"))
        val nodes = document.getElementsByTagName("string")

        return (0 until nodes.length).associate { index ->
            val node = nodes.item(index)
            val attributes = node.attributes
            attributes.getNamedItem("name").nodeValue to
                Entry(node.textContent, attributes.getNamedItem("translatable")?.nodeValue != "false")
        }
    }

    private val english = strings("values")
    private val russian = strings("values-ru")
    private val placeholder = Regex("%\\d+\\$[sd]")

    @Test
    fun `every translatable string has a Russian translation and nothing else does`() {
        assertEquals(english.filterValues { it.translatable }.keys.sorted(), russian.keys.sorted())
    }

    @Test
    fun `no string is blank`() {
        for ((name, entry) in english + russian.mapKeys { "ru:" + it.key }) {
            assertTrue(name, entry.text.isNotBlank())
        }
    }

    @Test
    fun `translations keep the same placeholders`() {
        for ((name, translation) in russian) {
            assertEquals(name, placeholder.findAll(english.getValue(name).text).map { it.value }.sorted().toList(), placeholder.findAll(translation.text).map { it.value }.sorted().toList())
        }
    }

    @Test
    fun `Russian copy has no leftover English product terms`() {
        // Technical proper nouns are fine; the product's own vocabulary must be translated.
        val leftovers = Regex("Client|Appointment|Profile Share|Share Image|Stylist|Scheduled|Completed|Canceled|No-show|Service")

        for ((name, translation) in russian) {
            assertFalse("$name: ${translation.text}", leftovers.containsMatchIn(translation.text))
        }
    }

    @Test
    fun `api failures map to localized messages instead of raw English`() {
        assertEquals(R.string.network_error, ApiException("x", isNetwork = true).messageRes())
        assertEquals(R.string.error_client_has_services, ApiException("Client has Services attached to this Appointment.", code = "BAD_REQUEST").messageRes())
        assertEquals(R.string.error_not_found, ApiException("Client not found.", code = "NOT_FOUND").messageRes())
        assertEquals(R.string.error_invalid_input, ApiException("Too small", code = "BAD_REQUEST").messageRes())
        // Whether the session has ended is for the sign-in provider to say, so a refused token does not claim it.
        assertEquals(R.string.error_sign_in_rejected, ApiException("Authentication required.", code = "UNAUTHORIZED").messageRes())
        // A change that was sent but never answered is not "can't reach the server".
        assertEquals(R.string.error_outcome_unknown, ApiException("No reply from the server.", code = ApiException.NO_REPLY).messageRes())
        assertEquals(R.string.error_generic, ApiException("boom").messageRes())
        assertEquals(R.string.error_appointment_total_too_large, ApiException("Appointment total is too large.", code = "BAD_REQUEST").messageRes())
        assertEquals(R.string.client_email_invalid, ApiException("Email is not valid.", code = "BAD_REQUEST").messageRes())
    }

    @Test
    fun `every reason an amount is refused for has a message, and only an amount that can be saved has none`() {
        assertNull(Amount.Valid(8500).refusalRes())
        assertEquals(R.string.amount_required, Amount.Empty.refusalRes())
        assertEquals(R.string.amount_unreadable, Amount.Unreadable.refusalRes())
        assertEquals(R.string.amount_negative, Amount.Negative.refusalRes())
        assertEquals(R.string.amount_too_large, Amount.TooLarge.refusalRes())
        // The one message with an argument: the largest amount, as the app writes amounts.
        assertEquals(listOf("%1\$s"), placeholder.findAll(english.getValue("amount_too_large").text).map { it.value }.toList())
    }

    @Test
    fun `a refused sign-in is explained by what the sign-in provider says about the session`() {
        // The provider could not issue a token, so the request went out without one. Nothing has ended.
        assertEquals(R.string.error_sign_in_unreachable, SessionCheck.UNREACHABLE.refusedSignInMessage())
        assertEquals(R.string.error_sign_in_rejected, SessionCheck.ALIVE.refusedSignInMessage())
        // An ended session is explained on the sign-in screen, not in a message here.
        assertNull(SessionCheck.ENDED.refusedSignInMessage())

        // Only the sign-in banner may say that the session has ended.
        assertFalse(SessionCheck.entries.any { it.refusedSignInMessage() == R.string.error_session_ended })
    }

    @Test
    fun `a sign-in provider that cannot start for lack of a connection says so`() {
        // What Clerk reports when it is started offline, as it is thrown and as the cause of another failure.
        val offline = UnknownHostException("Unable to resolve host \"clerk.example.dev\"")

        assertTrue(offline.isConnectionFailure())
        assertTrue(IllegalStateException("Failed to load client", SocketTimeoutException("timeout")).isConnectionFailure())
        assertFalse(IllegalStateException("Invalid Clerk publishable key.").isConnectionFailure())

        assertEquals(R.string.network_error, AuthState.Failed("Unable to resolve host", isNetwork = offline.isConnectionFailure()).messageRes())
        assertEquals(R.string.error_generic, AuthState.Failed("Invalid Clerk publishable key.").messageRes())
    }
}
