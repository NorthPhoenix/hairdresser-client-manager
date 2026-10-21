package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.ClientProfile
import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.domain.Referrals
import com.northphoenix.hairdresserclientmanager.domain.UnsavedInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReferralsTest {
    private fun client(id: String, name: String, referredById: String? = null, referredByName: String? = null) =
        ClientProfile(id = id, name = name, language = Language.RU, referredById = referredById, referredByName = referredByName)

    private val lena = client("lena", "Лена")
    private val nadya = client("nadya", "Надя От Лены Балаяж")

    @Test
    fun `finds who recommended a Client by id, and shows their name as it is now`() {
        val renamed = client("lena", "Лена К.")
        val oleg = client("oleg", "Олег", referredById = "lena", referredByName = "Лена")

        assertEquals(renamed, Referrals.referrer(oleg, listOf(renamed, oleg)))
        assertEquals("Лена К.", Referrals.referrerName(oleg, listOf(renamed, oleg)))
        // Not in the list (yet): the name the server stated.
        assertNull(Referrals.referrer(oleg, listOf(oleg)))
        assertEquals("Лена", Referrals.referrerName(oleg, listOf(oleg)))
        assertNull(Referrals.referrerName(lena, listOf(lena, oleg)))
    }

    @Test
    fun `lists the Clients a Client recommended, by name`() {
        val b = client("b", "боря", referredById = "lena")
        val a = client("a", "Аня", referredById = "lena")
        val other = client("c", "Вика", referredById = "b")

        assertEquals(listOf(a, b), Referrals.referred(lena, listOf(lena, b, other, a)))
        assertEquals(emptyList<ClientProfile>(), Referrals.referred(other, listOf(lena, b, other, a)))
    }

    @Test
    fun `suggests the one Client the name says, also when the name has an ending`() {
        assertEquals(lena, Referrals.suggestion(nadya, listOf(lena, nadya, client("o", "Оля"))))
        assertEquals(lena, Referrals.suggestion(client("n", "Надя от лены"), listOf(lena)))
        assertEquals(lena, Referrals.suggestion(client("n", "Надя ОТ Лены, балаяж"), listOf(lena)))
        assertEquals(client("e", "Ёлка Иванова"), Referrals.suggestion(client("n", "Надя от Елки"), listOf(client("e", "Ёлка Иванова"))))
        assertEquals(client("s", "Сергей"), Referrals.suggestion(client("n", "Паша от Сергея"), listOf(client("s", "Сергей"))))
        assertEquals(client("m", "Марина К"), Referrals.suggestion(client("n", "Паша от Марины"), listOf(client("m", "Марина К"), client("a", "Мария"))))
    }

    @Test
    fun `suggests nothing without exactly one match`() {
        // Two Lenas, nobody, a different name.
        assertNull(Referrals.suggestion(nadya, listOf(lena, client("l2", "Лена Иванова"), nadya)))
        assertNull(Referrals.suggestion(nadya, listOf(nadya)))
        assertNull(Referrals.suggestion(nadya, listOf(nadya, client("m", "Марина"))))
    }

    @Test
    fun `suggests nothing for a name without 'от' before a word, or a Client who has a referrer`() {
        assertNull(Referrals.suggestion(client("n", "Из Церкви"), listOf(lena)))
        assertNull(Referrals.suggestion(client("n", "Анна Отрадная"), listOf(lena)))
        assertNull(Referrals.suggestion(client("n", "Надя от"), listOf(lena)))
        assertNull(Referrals.suggestion(client("n", "От Лены"), listOf(lena)))
        assertNull(Referrals.suggestion(nadya.copy(referredById = "x"), listOf(lena)))
    }

    @Test
    fun `never suggests the Client themselves`() {
        val self = client("lena2", "Лена от Лены")

        assertNull(Referrals.suggestion(self, listOf(self)))
    }

    @Test
    fun `the form is unsaved when the referrer changes`() {
        val saved = UnsavedInput.clientForm("Надя", Language.RU, "", "", "", "", referredById = "lena")

        assertEquals(saved, UnsavedInput.clientForm("Надя", Language.RU, "", "", "", "", referredById = "lena"))
        assertNotEquals(saved, UnsavedInput.clientForm("Надя", Language.RU, "", "", "", "", referredById = null))
        assertEquals(UnsavedInput.clientForm("Надя", Language.RU, "", "", "", ""), UnsavedInput.clientForm("Надя", Language.RU, "", "", "", "", referredById = null))
    }

    @Test
    fun `a deleted Client leaves nobody recommended by them`() {
        val oleg = client("oleg", "Олег", referredById = "lena", referredByName = "Лена")

        assertEquals(listOf(client("oleg", "Олег")), Referrals.withoutClient(listOf(lena, oleg), "lena"))
    }

    @Test
    fun `a merge moves the links of the removed Client to the kept one, and none to the kept Client themselves`() {
        val kept = client("kept", "Jessica", referredById = "removed")
        val oleg = client("oleg", "Олег", referredById = "removed", referredByName = "Джессика")
        val other = client("other", "Вика", referredById = "lena")

        assertEquals(
            listOf(client("kept", "Jessica"), client("oleg", "Олег", referredById = "kept", referredByName = "Jessica"), other),
            Referrals.afterMerge(listOf(kept, oleg, other), "removed", kept),
        )
    }
}
