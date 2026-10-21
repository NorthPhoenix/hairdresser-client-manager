package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.ClientProfile
import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.domain.ClientRules
import com.northphoenix.hairdresserclientmanager.domain.PhoneFormat
import com.northphoenix.hairdresserclientmanager.state.withSaved
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneDuplicatesTest {
    private fun client(id: String, phone: String = "", distinctFrom: List<String>? = null) =
        ClientProfile(id = id, name = id, language = Language.RU, phone = phone, distinctFromIds = distinctFrom)

    @Test
    fun `every way of writing a US number is shown as one`() {
        listOf(
            "(512) 917-4528",
            "+1 512-917-4528",
            "5129174528",
            "+1(512)917-4528",
            "1 512 917 4528",
            "512.917.4528",
            " 512 917 4528 ",
            "+1 (512) 917–4528",
            "15129174528",
        ).forEach { typed -> assertEquals(typed, "(512) 917-4528", PhoneFormat.display(typed)) }
    }

    @Test
    fun `any other phone is shown as typed`() {
        listOf(
            "+7 916 123-45-67",
            "8 916 123 45 67",
            "+44 20 7946 0958",
            "917-4528",
            "512 917 4528 ext 2",
            "+52 512 917 4528",
            "25129174528",
            "n/a",
            "",
        ).forEach { typed -> assertEquals(typed, typed, PhoneFormat.display(typed)) }
        assertEquals("+7 916 123-45-67", PhoneFormat.display("  +7 916 123-45-67 "))
        // A '+' in the middle is not a country code.
        assertNull(PhoneFormat.usDigits("512+9174528"))
        // A '+' in front of ten digits is a number of another country written without its spaces.
        assertNull(PhoneFormat.usDigits("+5129174528"))
    }

    @Test
    fun `Clients whose numbers are written differently are possible duplicates`() {
        val jessica = client("a", "(512) 917-4528")
        val clients = listOf(jessica, client("b", "+1 512-917-4528"), client("c", "5129174528"), client("d", "(512) 917-4529"), client("e"))

        assertEquals(listOf("b", "c"), ClientRules.possibleDuplicates(jessica, clients).map { it.id })
        assertEquals(setOf("a", "b", "c"), ClientRules.withPossibleDuplicates(clients))
    }

    @Test
    fun `Clients without a phone are never duplicates`() {
        val clients = listOf(client("a"), client("b"), client("c", "-"), client("d", "n/a"))

        clients.forEach { assertEquals(emptyList<ClientProfile>(), ClientRules.possibleDuplicates(it, clients)) }
        assertEquals(emptySet<String>(), ClientRules.withPossibleDuplicates(clients))
    }

    @Test
    fun `a pair said to be different people is no longer a duplicate, from either side`() {
        val a = client("a", "5129174528", distinctFrom = listOf("b"))
        val b = client("b", "+1 512 917 4528")
        val c = client("c", "512-917-4528")
        val clients = listOf(a, b, c)

        assertEquals(listOf("c"), ClientRules.possibleDuplicates(a, clients).map { it.id })
        assertEquals(listOf("c"), ClientRules.possibleDuplicates(b, clients).map { it.id })
        assertEquals(listOf("a", "b"), ClientRules.possibleDuplicates(c, clients).map { it.id })
        assertEquals(setOf("a", "b", "c"), ClientRules.withPossibleDuplicates(clients))

        val allSaid = listOf(a.copy(distinctFromIds = listOf("b", "c")), b.copy(distinctFromIds = listOf("c")), c)

        assertEquals(emptySet<String>(), ClientRules.withPossibleDuplicates(allSaid))
    }

    @Test
    fun `a Russian mobile with and without its code is a duplicate, a number of another country is not`() {
        val olga = client("a", "8 916 123 45 67")

        assertEquals(listOf("b"), ClientRules.possibleDuplicates(olga, listOf(olga, client("b", "+7 916 123-45-67"))).map { it.id })
        assertEquals(emptyList<ClientProfile>(), ClientRules.possibleDuplicates(client("y", "+81 3 1234 5678"), listOf(client("z", "+7 131 234 56 78"))))
    }

    @Test
    fun `a saved Client keeps who they are not, which the reply to a save leaves out`() {
        val list = listOf(client("a", "5129174528", distinctFrom = listOf("b")), client("b"))
        val saved = list.withSaved(client("a", "(512) 917-4528"))

        assertEquals(listOf("b"), saved.first { it.id == "a" }.distinctFromIds)
    }
}
