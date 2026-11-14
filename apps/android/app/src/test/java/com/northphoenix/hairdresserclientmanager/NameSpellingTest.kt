package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.ClientProfile
import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.domain.ClientRules
import com.northphoenix.hairdresserclientmanager.domain.NameSpelling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Client search across alphabets: a name saved in Latin letters is found in Cyrillic, and the other way round. */
class NameSpellingTest {
    private fun client(id: String, name: String, phone: String = "") =
        ClientProfile(id = id, name = name, language = Language.RU, phone = phone)

    private fun found(clients: List<ClientProfile>, search: String) = ClientRules.filter(clients, search).map { it.id }

    private fun assertOneSpelling(vararg names: String) {
        val spelled = names.map(NameSpelling::of)

        assertEquals(names.joinToString(" / "), 1, spelled.distinct().size)
    }

    @Test
    fun `a Russian name and the usual ways of writing it in Latin letters are one spelling`() {
        assertOneSpelling("Ольга", "Olga", "Ol'ga")
        assertOneSpelling("Юлия", "Yulia", "Yuliya", "Julia", "Iuliia")
        assertOneSpelling("Алёна", "Алена", "Alyona", "Alena")
        assertOneSpelling("Ксения", "Ksenia", "Kseniya", "Xenia")
        assertOneSpelling("Наталья", "Natalia", "Natalya", "Natalja", "Natal'ya")
        assertOneSpelling("Татьяна", "Tatiana", "Tatyana")
        assertOneSpelling("Мария", "Maria", "Mariya")
        assertOneSpelling("Елена", "Elena", "Yelena")
        assertOneSpelling("Евгения", "Evgenia", "Evgeniya", "Yevgeniya")
        assertOneSpelling("Виктория", "Victoria", "Viktoriya")
        assertOneSpelling("Кристина", "Kristina", "Christina")
        assertOneSpelling("Софья", "София", "Sofia", "Sophia", "Sofya")
        assertOneSpelling("Майя", "Maya", "Maiya")
        assertOneSpelling("Яна", "Yana", "Iana", "Jana")
        assertOneSpelling("Дмитрий", "Dmitry", "Dmitriy", "Dmitrii")
        assertOneSpelling("Михаил", "Mikhail")
        assertOneSpelling("Алексей", "Alexey", "Aleksei")
        assertOneSpelling("Пётр", "Pyotr", "Petr")
        assertOneSpelling("Жанна", "Zhanna")
        assertOneSpelling("Кузнецова", "Kuznetsova")
        assertOneSpelling("Щербакова", "Shcherbakova", "Scherbakova")
        assertOneSpelling("Хачатурян", "Khachaturyan")
        assertOneSpelling("Васильева", "Vasilyeva", "Vasilieva", "Vasileva")
        assertOneSpelling("Соловьёва", "Solovyova", "Soloveva")
        assertOneSpelling("Иванова", "Ivanova", "Iwanowa")
        // The same goes for an English name written in Cyrillic.
        assertOneSpelling("Emily Carter", "Эмили Картер")
        assertOneSpelling("Jessica Smith", "Джессика Смит")
        assertOneSpelling("Ukrainian Ігор", "Ukrainian Игор")
    }

    @Test
    fun `different names stay different`() {
        assertNotEquals(NameSpelling.of("Анна"), NameSpelling.of("Ана"))
        assertNotEquals(NameSpelling.of("Юлия"), NameSpelling.of("Улия"))
        assertNotEquals(NameSpelling.of("Ольга"), NameSpelling.of("Оля"))
        assertNotEquals(NameSpelling.of("Мария"), NameSpelling.of("Марина"))
    }

    @Test
    fun `the spelling is lower case, without accents, with one space between words`() {
        assertEquals("zoe saldana", NameSpelling.of("  Zoë   SALDANA "))
        assertEquals("ana maria", NameSpelling.of("Ana-María"))
        assertEquals("olga ot marini", NameSpelling.of("Ольга (от Марины)"))
        assertEquals("", NameSpelling.of(" -- "))
    }

    @Test
    fun `only a search with a letter is looked for in the other alphabet`() {
        assertEquals("olga", NameSpelling.ofSearch("ольга"))
        assertNull(NameSpelling.ofSearch("+7 916"))
        assertNull(NameSpelling.ofSearch("--"))
    }

    @Test
    fun `a search in Cyrillic finds a name saved in Latin letters, and the other way round`() {
        val clients = listOf(
            client("1", "Olga Smith"),
            client("2", "Ольга Иванова"),
            client("3", "Julia от Марины"),
            client("4", "Юлия Петрова"),
            client("5", "Xenia Moore"),
            client("6", "Анна Соколова"),
            client("7", "Татьяна"),
        )

        assertEquals(listOf("1", "2"), found(clients, "Ольга"))
        assertEquals(listOf("1", "2"), found(clients, "olga"))
        assertEquals(listOf("3", "4"), found(clients, "Юлия"))
        assertEquals(listOf("3", "4"), found(clients, "Yulia"))
        // A part of a name, as it is being typed.
        assertEquals(listOf("5"), found(clients, "Ксен"))
        assertEquals(listOf("4"), found(clients, "Petr"))
        // The Russian words beside a Latin name are found in either alphabet too.
        assertEquals(listOf("3"), found(clients, "марин"))
        assertEquals(listOf("3"), found(clients, "Julia ot"))
        // A doubled letter is not one: «Анна» is not a part of «Татьяна».
        assertEquals(listOf("6"), found(clients, "Анна"))
        assertEquals(listOf("6"), found(clients, "anna"))
        assertTrue(found(clients, "Мартин").isEmpty())
    }

    @Test
    fun `a number is still found only as typed or by its digits`() {
        val clients = listOf(client("1", "Anna 7"), client("2", "Олег", "+7 916 555-01-01"))

        // "+7" is a phone search: the "7" of a name is not found without its plus.
        assertEquals(listOf("2"), found(clients, "+7"))
        assertEquals(listOf("1", "2"), found(clients, "7"))
        assertEquals(listOf("1"), found(clients, "анна 7"))
    }
}
