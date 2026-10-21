package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.domain.ContactImportList
import com.northphoenix.hairdresserclientmanager.domain.DeviceContact
import com.northphoenix.hairdresserclientmanager.domain.ImportCandidate
import com.northphoenix.hairdresserclientmanager.domain.ImportItem
import com.northphoenix.hairdresserclientmanager.domain.PhoneDigits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Import of several contacts: which contacts are listed, in which order, which are Clients already, and what a search finds. */
class ContactImportListTest {
    private fun contact(id: Long, name: String?, vararg phones: String, address: DeviceContact.PostalAddress? = null, note: String? = null) =
        DeviceContact(
            id = id,
            fullName = name,
            phones = phones.toList(),
            addresses = listOfNotNull(address),
            note = note,
        )

    private fun candidate(id: Long, name: String, vararg phones: String) =
        ImportCandidate(id, name, phones.first(), phones.toList())

    // --- phone digits ---

    @Test
    fun `a US number is the same number however it is written`() {
        assertTrue(PhoneDigits.same("(512) 917-4528", "+1 512-917-4528"))
        assertTrue(PhoneDigits.same("5129174528", "1(512)9174528"))
        assertFalse(PhoneDigits.same("(512) 917-4528", "(512) 917-4529"))
    }

    @Test
    fun `a short number or none compares with nothing`() {
        assertNull(PhoneDigits.key("911"))
        assertNull(PhoneDigits.key(""))
        assertNull(PhoneDigits.key(null))
        assertFalse(PhoneDigits.same("911", "911"))
        assertFalse(PhoneDigits.same("", ""))
    }

    // --- which contacts are listed ---

    @Test
    fun `only contacts with a phone number are listed`() {
        val list = ContactImportList.candidates(
            listOf(
                contact(1, "Анна", "(512) 111-2222"),
                contact(2, "Без номера"),
                contact(3, "Пустой", " ", "n/a"),
            ),
        )

        assertEquals(listOf(1L), list.map { it.id })
    }

    @Test
    fun `a contact becomes a Client with its name, first number, address and note`() {
        val list = ContactImportList.candidates(
            listOf(
                contact(
                    1, "Анна Иванова", "(512) 111-2222", "(512) 333-4444",
                    address = DeviceContact.PostalAddress(street = "12 Oak St", city = "Austin", region = "TX", postcode = "78701"),
                    note = "  Любит мягкие слои  ",
                ),
            ),
        )

        val anna = list.single()

        assertEquals("Анна Иванова", anna.name)
        assertEquals("(512) 111-2222", anna.phone)
        assertEquals(listOf("(512) 111-2222", "(512) 333-4444"), anna.phones)
        assertEquals("12 Oak St, Austin, TX, 78701", anna.address)
        assertEquals("Любит мягкие слои", anna.note)
        assertFalse(anna.nameless)
    }

    @Test
    fun `a contact without a name is listed under its number`() {
        val nameless = ContactImportList.candidates(listOf(contact(1, null, "(512) 111-2222"))).single()

        assertEquals("(512) 111-2222", nameless.name)
        assertTrue(nameless.nameless)
    }

    @Test
    fun `the same number twice in a contact is listed once`() {
        val anna = ContactImportList.candidates(listOf(contact(1, "Анна", "(512) 111-2222", "+1 512 111 2222"))).single()

        assertEquals(1, anna.phones.size)
    }

    @Test
    fun `texts are cut to what the server takes`() {
        val long = ContactImportList.candidates(listOf(contact(1, "a".repeat(150), "5121112222", note = "n".repeat(6000)))).single()

        assertEquals(100, long.name.length)
        assertEquals(5000, long.note.length)
    }

    // --- order and letters ---

    @Test
    fun `contacts are sorted by name with Cyrillic first for a Russian Stylist and the names without a letter last`() {
        val candidates = listOf(
            candidate(1, "Bob", "5120000001"),
            candidate(2, "Ёлка", "5120000002"),
            candidate(3, "123 Salon", "5120000003"),
            candidate(4, "Анна", "5120000004"),
            candidate(5, "Елена", "5120000005"),
        )

        assertEquals(listOf(4L, 5L, 2L, 1L, 3L), ContactImportList.rows(candidates, emptyList(), cyrillicFirst = true).map { it.candidate.id })
        assertEquals(listOf(1L, 4L, 5L, 2L, 3L), ContactImportList.rows(candidates, emptyList(), cyrillicFirst = false).map { it.candidate.id })
    }

    @Test
    fun `a letter heads each run of names, and ё is listed under е`() {
        val rows = ContactImportList.rows(
            listOf(candidate(1, "Анна", "5120000001"), candidate(2, "Елена", "5120000002"), candidate(3, "Ёлка", "5120000003"), candidate(4, "42", "5120000004")),
            emptyList(),
            cyrillicFirst = true,
        )

        val items = ContactImportList.items(rows)

        assertEquals(
            listOf("А", "Е", "#"),
            items.filterIsInstance<ImportItem.Header>().map { it.letter },
        )
        assertEquals(7, items.size)
    }

    // --- Clients already ---

    @Test
    fun `a contact whose number a Client has is already a Client, however the number is written`() {
        val rows = ContactImportList.rows(
            listOf(candidate(1, "Анна", "+1 512-111-2222"), candidate(2, "Борис", "5123334444"), candidate(3, "Вера", "5125550000", "5125551111")),
            clientPhones = listOf("(512) 111-2222", "", "512 555 1111"),
            cyrillicFirst = true,
        )

        assertEquals(listOf(true, false, true), rows.map { it.alreadyClient })
        assertEquals(setOf(2L), ContactImportList.selectable(rows))
    }

    // --- search ---

    private val rows = ContactImportList.rows(
        listOf(candidate(1, "Ёлка Петрова", "(512) 111-2222"), candidate(2, "Bob", "+1 512 333 4444", "737-555-0100")),
        emptyList(),
        cyrillicFirst = true,
    )

    @Test
    fun `a search finds a name whatever the case, and ё is е`() {
        assertEquals(listOf(1L), ContactImportList.filter(rows, "елка").map { it.candidate.id })
        assertEquals(listOf(1L), ContactImportList.filter(rows, "ПЕТР").map { it.candidate.id })
        assertEquals(listOf(2L), ContactImportList.filter(rows, "bo").map { it.candidate.id })
        assertTrue(ContactImportList.filter(rows, "zzz").isEmpty())
        assertEquals(2, ContactImportList.filter(rows, "  ").size)
    }

    @Test
    fun `a search that is a number matches the digits of any number of the contact`() {
        assertEquals(listOf(2L), ContactImportList.filter(rows, "333-44").map { it.candidate.id })
        assertEquals(listOf(2L), ContactImportList.filter(rows, "7375550100").map { it.candidate.id })
        assertEquals(listOf(1L), ContactImportList.filter(rows, "(512) 111").map { it.candidate.id })
        assertEquals(2, ContactImportList.filter(rows, "512").size)
    }
}
