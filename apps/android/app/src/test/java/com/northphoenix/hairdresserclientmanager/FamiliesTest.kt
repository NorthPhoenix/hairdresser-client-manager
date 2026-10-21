package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.ClientProfile
import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.data.ProfileShare
import com.northphoenix.hairdresserclientmanager.domain.Families
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FamiliesTest {
    private fun client(id: String, name: String = id, familyId: String? = null, role: String = "") =
        ClientProfile(id = id, name = name, language = Language.RU, familyId = familyId, familyRole = role)

    private val oksana = client("oksana", "Оксана", "f1")
    private val antoni = client("antoni", "Антони", "f1", "сын")
    private val masha = client("masha", "маша", "f1", "дочь")
    private val lena = client("lena", "Лена", "f2", "мама")
    private val babushka = client("babushka", "Бабушка Лены", "f2")
    private val olga = client("olga", "Ольга")

    private val all = listOf(oksana, antoni, masha, lena, babushka, olga)

    @Test
    fun `lists the other Clients of the Family by name, and nobody for a Client in none`() {
        assertEquals(listOf(antoni, masha), Families.members(oksana, all))
        assertEquals(listOf(masha, oksana), Families.members(antoni, all))
        assertEquals(emptyList<ClientProfile>(), Families.members(olga, all))
    }

    @Test
    fun `offers the family of the Clients of an Appointment who are not in it, each once`() {
        assertEquals(listOf(antoni, masha), Families.suggestions(listOf("oksana"), all))
        assertEquals(listOf(masha), Families.suggestions(listOf("oksana", "antoni"), all))
        // Per Client in the order of the Appointment: the primary Client's family first.
        assertEquals(listOf(babushka, antoni, masha), Families.suggestions(listOf("lena", "oksana"), all))
        assertEquals(emptyList<ClientProfile>(), Families.suggestions(listOf("olga"), all))
        assertEquals(emptyList<ClientProfile>(), Families.suggestions(listOf("oksana", "antoni", "masha"), all))
        // A Client the list does not have (deleted meanwhile) is no one's family.
        assertEquals(emptyList<ClientProfile>(), Families.suggestions(listOf("gone"), all))
    }

    @Test
    fun `adding moves a Client out of another Family only when both have one`() {
        assertTrue(Families.movesFromOtherFamily(oksana, lena))
        assertFalse(Families.movesFromOtherFamily(oksana, antoni))
        assertFalse(Families.movesFromOtherFamily(oksana, olga))
        // A Client without a Family joins the picked one's.
        assertFalse(Families.movesFromOtherFamily(olga, lena))
    }

    @Test
    fun `taking one of two out ends the Family, one of three does not`() {
        assertTrue(Families.leavingEndsFamily(lena, all))
        assertFalse(Families.leavingEndsFamily(antoni, all))
        assertFalse(Families.leavingEndsFamily(olga, all))
    }

    @Test
    fun `says the role and the visits, each when there is one`() {
        assertEquals("сын · 2 визита", Families.subtitle("сын", "2 визита"))
        assertEquals("2 визита", Families.subtitle(" ", "2 визита"))
        assertEquals("сын", Families.subtitle("сын", null))
        assertEquals("", Families.subtitle("", null))
    }

    @Test
    fun `a deleted Client leaves a Family of two without anyone in it`() {
        val after = Families.withoutClient(all, "babushka")

        assertEquals(client("lena", "Лена"), after.first { it.id == "lena" })
        assertEquals(listOf(oksana, antoni, masha), after.filter { it.familyId == "f1" })
        assertEquals(5, after.size)
    }

    @Test
    fun `the answer to a change of a Family replaces the Clients it touched in their places`() {
        val share = ProfileShare(id = "s1", token = "t", language = Language.RU, createdAt = "2026-10-21T00:00:00Z")
        val shared = olga.copy(activeProfileShare = share, distinctFromIds = listOf("x"))
        val list = listOf(oksana, antoni, masha, lena, babushka, shared)
        // The server put Ольга in Лена's Family; its answer about a Client says nothing of their Profile Share or pairs.
        val after = Families.withChanged(list, listOf(olga.copy(familyId = "f2", familyRole = "сестра")))

        assertEquals(list.map { it.id }, after.map { it.id })
        assertEquals(shared.copy(familyId = "f2", familyRole = "сестра"), after.last())
    }

    @Test
    fun `the answer to a Client leaving a Family of two leaves nobody in it`() {
        val after = Families.withChanged(all, listOf(lena.copy(familyId = null, familyRole = "")))

        assertEquals(null, after.first { it.id == "babushka" }.familyId)
    }

    @Test
    fun `a merge puts the kept Client in the Family the server says and makes one Family of two`() {
        // Лена (kept, f2) and Оксана (removed, f1) are one person: f1 joins f2.
        val after = Families.afterMerge(all, "oksana", lena)

        assertEquals(listOf("antoni", "masha", "lena", "babushka"), after.filter { it.familyId == "f2" }.map { it.id })
        assertEquals("сын", after.first { it.id == "antoni" }.familyRole)
        assertFalse(after.any { it.id == "oksana" })
    }

    @Test
    fun `a merge of the two Clients of a Family ends it, and a kept Client without one takes the removed one's`() {
        val pair = listOf(client("a", familyId = "f1"), client("b", familyId = "f1", role = "мама"), olga)

        assertEquals(listOf(client("a"), olga), Families.afterMerge(pair, "b", client("a", familyId = "f1", role = "мама")))

        val joined = Families.afterMerge(all, "lena", olga.copy(familyId = "f2", familyRole = "мама"))

        assertEquals(olga.copy(familyId = "f2", familyRole = "мама"), joined.first { it.id == "olga" })
        assertEquals("f2", joined.first { it.id == "babushka" }.familyId)
    }
}
