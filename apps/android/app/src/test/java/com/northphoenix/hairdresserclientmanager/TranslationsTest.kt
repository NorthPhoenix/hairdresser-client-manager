package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.ApiException
import com.northphoenix.hairdresserclientmanager.i18n.messageRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
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
        assertEquals(R.string.error_session_ended, ApiException("Authentication required.", code = "UNAUTHORIZED").messageRes())
        assertEquals(R.string.error_generic, ApiException("boom").messageRes())
    }
}
