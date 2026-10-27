package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.domain.ImportedContact
import com.northphoenix.hairdresserclientmanager.domain.SharedContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SharedContactTest {
    @Test
    fun whatsAppContactCardGivesNameAndNumber() {
        val card = """
            BEGIN:VCARD
            VERSION:3.0
            N:;Анна;;;
            FN:Анна
            item1.TEL;waid=79001234567:+7 900 123-45-67
            item1.X-ABLabel:Mobile
            END:VCARD
        """.trimIndent()

        assertEquals(ImportedContact(name = "Анна", phone = "+7 900 123-45-67"), SharedContact.read(card))
    }

    @Test
    fun androidContactCardInQuotedPrintableIsDecoded() {
        // How Android's own contacts write a Russian name, folded where a line gets long.
        val card = "BEGIN:VCARD\r\n" +
            "VERSION:2.1\r\n" +
            "N;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:=D0=9F=D0=B5=D1=82=D1=80=D0=BE=D0=B2=D0=B0;=D0=90=D0=BD=D0=BD=D0=B0;;;\r\n" +
            "FN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:=D0=90=D0=BD=D0=BD=D0=B0=20=D0=9F=D0=B5=D1=82=\r\n" +
            "=D1=80=D0=BE=D0=B2=D0=B0\r\n" +
            "TEL;CELL:+1 512-917-4528\r\n" +
            "EMAIL;HOME:anna@example.com\r\n" +
            "ADR;HOME:;Apt 4;123 Main St;Chicago;IL;60601;USA\r\n" +
            "END:VCARD\r\n"

        assertEquals(
            ImportedContact(
                name = "Анна Петрова",
                phone = "+1 512-917-4528",
                email = "anna@example.com",
                address = "123 Main St, Apt 4, Chicago, IL, 60601, USA",
            ),
            SharedContact.read(card),
        )
    }

    @Test
    fun foldedLinesEscapesAndPreferredValues() {
        val card = """
            BEGIN:VCARD
            VERSION:3.0
            FN:Maria\, the
              colorist
            TEL;TYPE=HOME:+1 555 000 1111
            TEL;TYPE=CELL,PREF:+1 555 000 2222
            EMAIL:work@example.com
            EMAIL;TYPE="INTERNET,pref":maria@example.com
            END:VCARD
        """.trimIndent()

        assertEquals(
            ImportedContact(name = "Maria, the colorist", phone = "+1 555 000 2222", email = "maria@example.com"),
            SharedContact.read(card),
        )
    }

    @Test
    fun version4NumberLinkAndOnlyTheFirstCard() {
        val cards = """
            BEGIN:VCARD
            VERSION:4.0
            FN:Kim
            TEL;VALUE=uri;PREF=1:tel:+1-555-0100
            END:VCARD
            BEGIN:VCARD
            VERSION:4.0
            FN:Someone else
            TEL:+1-555-0199
            END:VCARD
        """.trimIndent()

        assertEquals(ImportedContact(name = "Kim", phone = "+1-555-0100"), SharedContact.read(cards))
    }

    @Test
    fun cardWithoutFullNameUsesItsNameParts() {
        val card = "BEGIN:VCARD\nVERSION:3.0\nN:Petrov;Ivan;;;\nTEL:89001234567\nEND:VCARD"

        assertEquals(ImportedContact(name = "Ivan Petrov", phone = "89001234567"), SharedContact.read(card))
    }

    @Test
    fun contactSharedAsTextGivesNameNumberAndEmail() {
        assertEquals(
            ImportedContact(name = "Анна Петрова", phone = "+7 (900) 123-45-67", email = "anna@example.com"),
            SharedContact.read("Анна Петрова\n+7 (900) 123-45-67\nanna@example.com"),
        )
    }

    @Test
    fun whatsAppChatLinkHoldsTheNumber() {
        assertEquals(ImportedContact(phone = "+79001234567"), SharedContact.read("https://wa.me/79001234567"))
        assertEquals(
            ImportedContact(phone = "+15125550100"),
            SharedContact.read("Write me: https://api.whatsapp.com/send?phone=15125550100&text=Hi"),
        )
    }

    @Test
    fun aMessageGivesTheNumberInItButNoName() {
        assertEquals(
            ImportedContact(phone = "8 900 123 45 67"),
            SharedContact.read("Здравствуйте! Можно записаться на пятницу? Мой номер 8 900 123 45 67"),
        )
    }

    @Test
    fun datesTimesSumsAndLinkDigitsAreNoNumbers() {
        val texts = listOf(
            "See you 27.10.2026 at 10:30",
            "2026-10-27 10:30",
            "Haircut 1 500 000",
            "https://www.facebook.com/profile.php?id=100012345678901",
            "https://m.me/anna.petrova",
            "Hello Nadia",
        )

        texts.forEach { text -> assertFalse(text, SharedContact.read(text).hasFields) }
    }

    @Test
    fun emptyAndUnreadableShares() {
        assertFalse(SharedContact.read("").hasFields)
        assertFalse(SharedContact.read("BEGIN:VCARD\nVERSION:3.0\nEND:VCARD").hasFields)
        assertFalse(SharedContact.read("BEGIN:VCARD\nnot a card").hasFields)
    }
}
