package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.ClientProfile
import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.domain.ClientRules
import com.northphoenix.hairdresserclientmanager.domain.NameSpelling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        assertOneSpelling("Васильева", "Vasilyeva", "Vasileva")
        assertOneSpelling("Соловьёва", "Solovyova", "Soloveva")
        assertOneSpelling("Иванова", "Ivanova", "Iwanowa")
        // The same goes for an English name written in Cyrillic.
        assertOneSpelling("Emily Carter", "Эмили Картер")
        assertOneSpelling("Jessica Smith", "Джессика Смит")
        assertOneSpelling("John Johnson", "Джон Джонсон")
        assertOneSpelling("Sarah", "Сара")
        assertOneSpelling("Hannah", "Ханна")
        assertOneSpelling("Cindy", "Синди")
        assertOneSpelling("Cecilia", "Сесилия")
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
        assertTrue(NameSpelling.Search("ольга").finds("Olga Smith"))
        assertFalse(NameSpelling.Search("+7 916").finds("Anna +7 916"))
        assertFalse(NameSpelling.Search("--").finds("Anna -- Smith"))
        // Spelled as one letter, a search would find nearly every name.
        assertFalse(NameSpelling.Search("Дж").finds("Maria Lopez"))
        assertFalse(NameSpelling.Search("о").finds("Olga Smith"))
    }

    @Test
    fun `a Russian search finds no other Russian name than as typed`() {
        val clients = listOf(
            client("1", "Жанна"),
            client("2", "Наташа"),
            client("3", "Лилия"),
            client("4", "Эмилия"),
            client("5", "Диана"),
            client("6", "Стефания"),
            client("7", "Мария"),
            client("8", "Hannah Lee"),
            client("9", "Maria Lopez"),
        )

        // «х» is "kh", never the "h" of «ж», «ч» or «ш»; an "h" of a Latin name is «х».
        assertEquals(listOf("8"), found(clients, "Ханна"))
        assertEquals(listOf("8"), found(clients, "Ха"))
        // Spellings that meet ("ilia" in "lilia") do not join two Russian names.
        assertTrue(found(clients, "Илья").isEmpty())
        assertTrue(found(clients, "Яна").isEmpty())
        assertTrue(found(clients, "Аня").isEmpty())
        assertTrue(found(clients, "Дж").isEmpty())
        assertEquals(listOf("7", "9"), found(clients, "Мария"))
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
    fun `a Russian search finds a name written with Ye at its start or -ie at its end`() {
        val clients = listOf(
            client("1", "Yelena Smith"),
            client("2", "Yevgeniya Lee"),
            client("3", "Natalie Brown"),
            client("4", "Sophie Moore"),
            client("5", "Marie Lopez"),
        )

        assertEquals(listOf("1"), found(clients, "Елена"))
        assertEquals(listOf("2"), found(clients, "Евгения"))
        assertEquals(listOf("3"), found(clients, "Натали"))
        assertEquals(listOf("4"), found(clients, "Софи"))
        assertEquals(listOf("5"), found(clients, "Мари"))
    }

    @Test
    fun `a Russian search finds a Latin name as it is being typed`() {
        val clients = listOf(client("1", "Daniel Brown"), client("2", "Ivan Dmitriev"), client("3", "Gabriela Lopez"), client("4", "Alyona Lee"))

        listOf("Дан", "Дани", "Даниэ", "Даниэл").forEach { assertEquals(it, listOf("1"), found(clients, it)) }
        listOf("Дмитр", "Дмитри", "Дмитриев").forEach { assertEquals(it, listOf("2"), found(clients, it)) }
        listOf("Габри", "Габриэла").forEach { assertEquals(it, listOf("3"), found(clients, it)) }
        listOf("Ал", "Алё", "Алён", "Алёна", "Алена").forEach { assertEquals(it, listOf("4"), found(clients, it)) }
    }

    @Test
    fun `a Latin search finds a Russian name written with Ye or Yo`() {
        val clients = listOf(client("1", "Елена Белова"), client("2", "Егор"), client("3", "Ёлкина"), client("4", "Пётр"))

        assertEquals(listOf("1"), found(clients, "Yelena"))
        assertEquals(listOf("2"), found(clients, "Yegor"))
        assertEquals(listOf("3"), found(clients, "Yolkina"))
        assertEquals(listOf("4"), found(clients, "Pyotr"))
    }

    @Test
    fun `a search that starts with Jo, Je, Ye or Yo keeps its first letter`() {
        val clients = listOf(
            client("1", "Анна Петрова"),
            client("2", "Emily Carter"),
            client("3", "Sofia Nguyen"),
            client("4", "Елена Белова"),
            client("5", "Алексей"),
            client("6", "John Doe"),
        )

        listOf("Jo", "Je", "Yo", "Joy", "Дже", "Йо").forEach { search ->
            assertTrue(search, found(clients, search).all { it == "6" })
        }
        assertEquals(listOf("6"), found(clients, "Jo"))
        // "Ye" is found as typed, in "Nguyen", and nowhere else.
        assertEquals(listOf("3"), found(clients, "Ye"))
        assertEquals(listOf("6"), found(clients, "Джо"))
        // Inside a word "ye" is «е» all the same.
        assertEquals(listOf("3"), found(clients, "Нгуен"))
    }

    @Test
    fun `a name is spelled the same every time`() {
        repeat(3) { assertEquals("iulia petrova", NameSpelling.of("Юлия Петрова")) }
        assertEquals("iulia petrova", NameSpelling.of("Julia Petrova"))
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
