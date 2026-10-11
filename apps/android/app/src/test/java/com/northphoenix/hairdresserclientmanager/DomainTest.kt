package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.core.AppConfig
import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.data.ClientProfile
import com.northphoenix.hairdresserclientmanager.data.DeviceContacts
import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.data.LocationType
import com.northphoenix.hairdresserclientmanager.domain.Amount
import com.northphoenix.hairdresserclientmanager.domain.ClientReminder
import com.northphoenix.hairdresserclientmanager.domain.ClientRules
import com.northphoenix.hairdresserclientmanager.domain.ContactFields
import com.northphoenix.hairdresserclientmanager.domain.ContactImport
import com.northphoenix.hairdresserclientmanager.domain.DeviceContact
import com.northphoenix.hairdresserclientmanager.domain.ImportedContact
import com.northphoenix.hairdresserclientmanager.domain.Money
import com.northphoenix.hairdresserclientmanager.domain.OneLine
import com.northphoenix.hairdresserclientmanager.domain.TimeFormat
import com.northphoenix.hairdresserclientmanager.domain.weeksOf
import com.northphoenix.hairdresserclientmanager.i18n.normalizeLanguage
import com.northphoenix.hairdresserclientmanager.i18n.stringsLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

class DomainTest {
    private fun client(id: String, name: String, phone: String = "") =
        ClientProfile(id = id, name = name, language = Language.RU, phone = phone)

    private fun found(clients: List<ClientProfile>, search: String) = ClientRules.filter(clients, search).map { it.id }

    private fun assertAmount(cents: Long, text: String) = assertEquals(text, Amount.Valid(cents), Money.parse(text))

    private fun assertRefused(reason: Amount, vararg texts: String) = texts.forEach { assertEquals(it, reason, Money.parse(it)) }

    @Test
    fun `an amount is read with a decimal point or a decimal comma and at most two decimals`() {
        assertAmount(8500, "85")
        assertAmount(8550, " 85,5 ")
        assertAmount(22050, "220.50")
        assertAmount(22050, "220,50")
        assertAmount(0, "0")
        assertAmount(0, "0.00")
        assertAmount(8500, "085")
        // A separator with digits on one side only is still one amount.
        assertAmount(50, ".5")
        assertAmount(50, ",50")
        assertAmount(8500, "85.")
        assertAmount(120000, "1200")
    }

    @Test
    fun `a separator followed by exactly three digits separates thousands`() {
        // M21: this was saved as 1.20.
        assertAmount(120000, "1,200")
        assertAmount(120000, "1.200")
        assertAmount(120000, "1 200")
        assertAmount(120000, "1\u00A0200")
        assertAmount(123456, "1,234.56")
        assertAmount(123450, "1,234.50")
        assertAmount(120050, "1.200,50")
        assertAmount(150050, "1 500,50")
        assertAmount(150050, "1 500.50")
        assertAmount(150000, "1 500")
        assertAmount(123456789, "1,234,567.89")
        assertAmount(100000000, "1.000.000")
        // Three digits cannot be cents, so this is not 12.35 rounded.
        assertAmount(1234500, "12.345")
        assertAmount(9999900, "99.999")
    }

    @Test
    fun `text that is not one amount is refused, never read as zero`() {
        assertRefused(Amount.Empty, "", "   ")
        assertRefused(Amount.Unreadable, "abc", "12abc", "1e3", "$85", "+5", "12.50.5", ".", ",", "8 5", "85 .50")
        // More decimals than cents have, in a shape that cannot be thousands either.
        assertRefused(Amount.Unreadable, "1,2345", "0.125", "0,500", "1234,567", "12,34,567")
        // Two separators, each followed by three digits: either could be the decimal one.
        assertRefused(Amount.Unreadable, "1,000.000", "1.234.56", "1,234,56", "1 234,567", "1,200,")
        assertRefused(Amount.Negative, "-20", "-4", "-0.5", "\u22125", "-1,200", "-99999999999")
        assertRefused(Amount.Unreadable, "-", "-abc", "--5")
    }

    @Test
    fun `only an amount is saved, and an empty field only where none is required`() {
        val notAmounts = listOf(Amount.Unreadable, Amount.Negative, Amount.TooLarge)

        assertFalse(Amount.Valid(0).isRefused())
        assertFalse(Amount.Valid(8500).isRefused(required = false))
        // A Service Menu Item and an ad hoc Service state their price. A Service from the menu may leave it to the menu.
        assertTrue(Amount.Empty.isRefused())
        assertFalse(Amount.Empty.isRefused(required = false))
        assertTrue(notAmounts.all { it.isRefused() })
        assertTrue(notAmounts.all { it.isRefused(required = false) })
    }

    @Test
    fun `an amount above the maximum is refused, never capped`() {
        assertAmount(Money.MAX_CENTS, "9999999.99")
        assertAmount(Money.MAX_CENTS, "9,999,999.99")
        // H7: these were saved as 21474836.47, the most an Int holds.
        assertRefused(Amount.TooLarge, "10000000", "10000000.00", "99999999999", "89161234567", "21474836.47", "10,000,000", "9".repeat(40))
        assertAmount(999999900, "0009999999")
    }

    @Test
    fun `amounts are written out exactly, also above what an Int holds`() {
        assertEquals("85.00", Money.centsToPrice(8500))
        assertEquals("0.05", Money.centsToPrice(5))
        assertEquals("$220.50", Money.format(22050))
        // The Services total of an Appointment saved before amounts had a maximum.
        assertEquals("$42949672.94", Money.format(4294967294))
        assertEquals("$9999999.99", Money.format(Money.MAX_CENTS))
    }

    @Test
    fun `what the app writes into an amount field is read back as the same amount`() {
        listOf(0L, 5L, 50L, 8500L, 22050L, 120000L, Money.MAX_CENTS).forEach { cents ->
            assertEquals(Amount.Valid(cents), Money.parse(Money.centsToPrice(cents)))
        }
    }

    @Test
    fun `client search matches name, phone digits and phone as typed`() {
        val clients = listOf(client("1", "Анна Петрова", "+1 (512) 555-0100"), client("2", "Emily Carter", "+1 512 555 0102"))

        assertEquals(listOf("1"), ClientRules.filter(clients, "анна").map { it.id })
        assertEquals(listOf("2"), ClientRules.filter(clients, "5550102").map { it.id })
        assertEquals(listOf("1"), ClientRules.filter(clients, "(512) 555-0100").map { it.id })
        assertEquals(2, ClientRules.filter(clients, "  ").size)
        assertTrue(ClientRules.filter(clients, "nobody").isEmpty())
    }

    @Test
    fun `duplicate phone ignores formatting and the client being edited`() {
        val clients = listOf(client("1", "Anna", "+1 512 555 0100"))

        assertTrue(ClientRules.isDuplicatePhone(clients, editingClientId = null, phone = "+1 (512) 555-0100"))
        assertFalse(ClientRules.isDuplicatePhone(clients, editingClientId = "1", phone = "+1 512 555 0100"))
        assertFalse(ClientRules.isDuplicatePhone(clients, editingClientId = null, phone = "  "))
    }

    @Test
    fun `client search reads ё as е and ignores case`() {
        val clients = listOf(client("1", "Семён Смирнов"), client("2", "Мария Ёлкина"), client("3", "Елена Белова"))

        assertEquals(listOf("1"), found(clients, "Семен"))
        assertEquals(listOf("1"), found(clients, "СЕМЁН"))
        assertEquals(listOf("2"), found(clients, "елк"))
        assertEquals(listOf("2"), found(clients, "Ёлк"))
        assertEquals(listOf("3"), found(clients, "ёлена"))
        assertEquals(listOf("2", "3"), found(clients, "ел"))
        // "ё" saved as "е" and a combining diaeresis (a contact imported from another system) is the same letter.
        assertEquals(listOf("9"), found(listOf(client("9", "Семён")), "семен"))
        assertEquals(listOf("9"), found(listOf(client("9", "Семён")), "семён"))
    }

    @Test
    fun `a client search with letters is not matched through its digits`() {
        val clients = listOf(
            client("1", "Offline Oscar"),
            client("2", "Boris Plus", "+7 903 000-11-22"),
            client("3", "Anna 2", "555 0134"),
            client("4", "Ivy Moore", "(555) 0234 ext 7"),
        )

        assertTrue(found(clients, "oscar 7").isEmpty())
        assertTrue(found(clients, "zzz 7").isEmpty())
        assertTrue(found(clients, "Moore 0134").isEmpty())
        assertEquals(listOf("3"), found(clients, "anna 2"))
        // The phone as typed is still found, letters and all.
        assertEquals(listOf("4"), found(clients, "ext 7"))
        // A search that is only a number is a name or a phone.
        assertEquals(listOf("2", "4"), found(clients, "7"))
        assertEquals(listOf("2", "3", "4"), found(clients, "2"))
        assertEquals(listOf("3"), found(clients, "(555) 01"))
        assertEquals(listOf("2"), found(clients, "903 000–11–22"))
    }

    @Test
    fun `a Russian mobile is found by its number after 7 and after 8`() {
        val clients = listOf(
            client("1", "Seven", "+7 916 555-01-01"),
            client("2", "Eight", "8 916 555-01-01"),
            client("3", "Bare", "916 555 01 02"),
            client("4", "Emily", "+1 512 555 0102"),
        )

        assertEquals(listOf("1", "2", "3"), found(clients, "7916"))
        assertEquals(listOf("1", "2", "3"), found(clients, "8916"))
        assertEquals(listOf("1", "2", "3"), found(clients, "916555"))
        assertEquals(listOf("1", "2"), found(clients, "8 (916) 555-01-01"))
        assertEquals(listOf("3"), found(clients, "+7 916 555 01 02"))
        // Any other number is found by its own digits only.
        assertEquals(listOf("4"), found(clients, "1512"))
        assertTrue(found(clients, "8512").isEmpty())
        assertTrue(found(clients, "7512").isEmpty())
    }

    @Test
    fun `a phone without a digit is never a duplicate`() {
        val clients = listOf(client("1", "Nick Nophone"), client("2", "Dana Dash", "-"), client("3", "Eve Ext", "ext"))

        listOf("-", "ext", "—", "n/a", "").forEach { phone ->
            assertFalse(phone, ClientRules.isDuplicatePhone(clients, editingClientId = null, phone = phone))
        }
    }

    @Test
    fun `a Russian mobile written with 8, with +7 or with neither is one number`() {
        val rita = listOf(client("1", "Rita", "+7 916 123-45-67"))

        listOf("8 916 123 45 67", "8(916)123-45-67", "916 123 45 67", "+7(916)1234567", "79161234567").forEach { phone ->
            assertTrue(phone, ClientRules.isDuplicatePhone(rita, editingClientId = null, phone = phone))
        }
        assertTrue(ClientRules.isDuplicatePhone(listOf(client("1", "Olga", "8 916 123 45 67")), null, "+7 916 123-45-67"))
        assertTrue(ClientRules.isDuplicatePhone(listOf(client("1", "Olga", "916 123 45 67")), null, "8 916 123-45-67"))
        assertTrue(ClientRules.isDuplicatePhone(listOf(client("1", "Boris", "8 903 000 11 22")), null, "+7 903 000-11-22"))

        assertFalse(ClientRules.isDuplicatePhone(rita, editingClientId = null, phone = "8 916 123 45 68"))
        assertFalse(ClientRules.isDuplicatePhone(rita, editingClientId = "1", phone = "8 916 123 45 67"))
    }

    @Test
    fun `no number but a Russian mobile is rewritten for the duplicate check`() {
        // Eleven digits that start with 8: Japan's +81, not a Russian number written with 8.
        assertFalse(ClientRules.isDuplicatePhone(listOf(client("1", "Yuki", "+81 3 1234 5678")), null, "+7 131 234 56 78"))
        // The app cannot know the country of a number that has no country code.
        assertFalse(ClientRules.isDuplicatePhone(listOf(client("1", "Anna", "+1 512 555 0100")), null, "512 555 0100"))
        assertEquals("15125550100", ClientRules.comparablePhone("+1 (512) 555-0100"))
        assertEquals("79161234567", ClientRules.comparablePhone("8 (916) 123-45-67"))
        assertEquals("", ClientRules.comparablePhone("ext"))
    }

    @Test
    fun `avatar initials are the first letters of the first two words`() {
        assertEquals("АП", ClientRules.initials("анна петрова"))
        assertEquals("АП", ClientRules.initials("  Анна   Петрова Сергеевна "))
        assertEquals("Ё", ClientRules.initials("ёлкина"))
        assertEquals("E", ClientRules.initials("emily@example.com"))
        assertEquals("·", ClientRules.initials("   "))
    }

    @Test
    fun `an avatar initial is a whole character, never half of an emoji`() {
        val haircut = "💇" // 💇, two UTF-16 units
        val nails = "💅" // 💅
        val mediumSkin = "🏽" // skin tone modifier
        val family = "👩‍👩‍👧" // three people joined into one emoji
        val flag = "🇷🇺" // 🇷🇺, two regional indicators
        val heart = "❤️" // ❤️, a symbol and its emoji variation selector

        val initials = mapOf(
            "$haircut Мария" to "${haircut}М",
            "$haircut Зоя" to "${haircut}З",
            "Rita $nails" to "R$nails",
            "$haircut$nails" to haircut,
            "$haircut$mediumSkin Мария" to "$haircut${mediumSkin}М",
            "$family Ivanovs" to "${family}I",
            "$flag Rita" to "${flag}R",
            "$heart anna" to "${heart}A",
            "élodie" to "É",
        )

        initials.forEach { (name, expected) ->
            val actual = ClientRules.initials(name)

            assertEquals(name, expected, actual)
            assertTrue(name, actual.codePoints().noneMatch { it in 0xD800..0xDFFF })
        }
    }

    @Test
    fun `contact import takes the first non-blank value of each kind`() {
        val imported = ContactImport.normalize(
            DeviceContact(
                fullName = "  ",
                givenName = "Anna",
                familyName = "Petrova",
                phones = listOf(null, " ", "+1 555 0100", "+1 555 0199"),
                emails = listOf("", "anna@example.com"),
                addresses = listOf(
                    DeviceContact.PostalAddress(),
                    DeviceContact.PostalAddress(street = "1 Main St", city = "Austin", region = "TX", postcode = " ", country = "USA"),
                ),
            ),
        )

        assertEquals("Anna Petrova", imported.name)
        assertEquals("+1 555 0100", imported.phone)
        assertEquals("anna@example.com", imported.email)
        assertEquals("1 Main St, Austin, TX, USA", imported.address)
        assertTrue(imported.hasFields)
    }

    @Test
    fun `contact with nothing usable has no fields`() {
        val imported = ContactImport.normalize(DeviceContact(fullName = " ", phones = listOf("")))

        assertNull(imported.name)
        assertFalse(imported.hasFields)
    }

    @Test
    fun `a contact's name and phone are imported as one line without control characters`() {
        val imported = ContactImport.normalize(
            DeviceContact(fullName = "Kim\u000BVert Importa", phones = listOf("+1 555\n0100"), emails = listOf(" kim@example.com\n")),
        )

        assertEquals("Kim Vert Importa", imported.name)
        assertEquals("+1 555 0100", imported.phone)
        assertEquals("kim@example.com", imported.email)
        assertEquals("Zoe Ctrl", ContactImport.normalize(DeviceContact(givenName = "Zoe\u0001", familyName = "\tCtrl")).name)
        // A name of nothing but a control character is no name: the given and family names are used.
        assertEquals("Anna", ContactImport.normalize(DeviceContact(fullName = "\u0001", givenName = "Anna")).name)
    }

    @Test
    fun `a contact saved without a name gives no Client name`() {
        // Android lists such a contact under its phone number (source 20) or its email address (source 10).
        assertNull(DeviceContacts.savedName("+1 650 555 0144", source = 20))
        assertNull(DeviceContacts.savedName("nameless@example.com", source = 10))
        assertNull(DeviceContacts.savedName(null, source = 0))
        // A structured name (40), a nickname (35) and an organization (30) are what the contact is called.
        assertEquals("Anna Petrova", DeviceContacts.savedName("Anna Petrova", source = 40))
        assertEquals("Anya", DeviceContacts.savedName("Anya", source = 35))
        assertEquals("Salon Supplies", DeviceContacts.savedName("Salon Supplies", source = 30))

        val imported = ContactImport.normalize(DeviceContact(fullName = null, phones = listOf("+1 650 555 0144")))

        assertNull(imported.name)
        assertEquals("+1 650 555 0144", imported.phone)
        assertTrue(imported.hasFields)
    }

    @Test
    fun `contact import asks before it replaces what is in the form`() {
        val nameAndPhone = ImportedContact(name = "Paula Phoneonly", phone = "+1 415 555 0123")

        fun asks(existingClient: Boolean = false, name: String = "", phone: String = "", email: String = "", address: String = "") =
            ContactImport.asksFirst(ContactFields(name, phone, email, address), nameAndPhone, existingClient)

        // An existing Client is always asked about, whatever the form holds and whether its Client is loaded yet.
        assertTrue(asks(existingClient = true))
        assertTrue(asks(existingClient = true, name = "Vera Full", phone = "+7 916 123-45-67"))

        // A new Client: only when something that was entered would be replaced.
        assertFalse(asks())
        assertFalse(asks(name = "  ", phone = " "))
        assertTrue(asks(name = "Typed Name"))
        assertTrue(asks(phone = "+1 415 555 0199"))
        assertTrue(asks(name = "Paula Phoneonly", phone = "+1 415 555 0199"))
        // The contact has no email and no address, so what was typed there stays and is not asked about.
        assertFalse(asks(email = "typed@example.com", address = "77 Typed Ave"))
        // The same value again replaces nothing.
        assertFalse(asks(name = " Paula Phoneonly ", phone = "+1 415 555 0123"))
        // Text with a space at its end that the contact leaves alone is not replaced either.
        assertFalse(asks(email = " typed@example.com ", address = "77 Typed Ave\n"))
    }

    @Test
    fun `contact import copies only what the contact has`() {
        val form = ContactFields(name = "Typed Name", phone = "+1 415 555 0199", email = "typed@example.com", address = "77 Typed Ave")

        assertEquals(
            form.copy(name = "Paula Phoneonly", phone = "+1 415 555 0123"),
            ContactImport.copy(form, ImportedContact(name = "Paula Phoneonly", phone = "+1 415 555 0123")).after,
        )
        assertEquals(form.copy(email = "paula@example.com"), ContactImport.copy(form, ImportedContact(email = "paula@example.com")).after)
        assertEquals(form, ContactImport.copy(form, ImportedContact()).after)
    }

    @Test
    fun `a second contact import takes the place of the first one`() {
        val wrong = ImportedContact(name = "Anna Wrong", phone = "+1 415 555 0100", email = "wrong@example.com", address = "1 Wrong St")
        val right = ImportedContact(name = "Anna Right", phone = "+1 415 555 0123")

        // A new Client: the email and the address of the wrong contact do not stay with the right one.
        val first = ContactImport.copy(ContactFields(), wrong)
        assertEquals(ContactFields("Anna Wrong", "+1 415 555 0100", "wrong@example.com", "1 Wrong St"), first.after)
        assertTrue(ContactImport.asksFirst(first.after, right, existingClient = false, last = first))
        val second = ContactImport.copy(first.after, right, first)
        assertEquals(ContactFields(name = "Anna Right", phone = "+1 415 555 0123"), second.after)

        // The question is also asked when the contact only takes details out: this one has no name and no phone.
        val emailOnly = ImportedContact(email = "wrong@example.com")
        assertTrue(ContactImport.asksFirst(first.after, emailOnly, existingClient = false, last = first))
        assertEquals(ContactFields(email = "wrong@example.com"), ContactImport.copy(first.after, emailOnly, first).after)
        // The same contact again changes nothing and asks nothing.
        assertFalse(ContactImport.asksFirst(first.after, wrong, existingClient = false, last = first))
        assertEquals(first, ContactImport.copy(first.after, wrong, first))

        // A third import takes back the second, not the first again.
        val third = ContactImport.copy(second.after, ImportedContact(address = "3 Third Rd"), second)
        assertEquals(ContactFields(address = "3 Third Rd"), third.after)

        // What the Stylist typed is kept: text from before the first import comes back, text changed after it stays.
        val typedFirst = ContactImport.copy(ContactFields(email = "typed@example.com"), wrong)
        assertEquals("wrong@example.com", typedFirst.after.email)
        val edited = typedFirst.after.copy(address = "77 Typed Ave")
        assertEquals(
            ContactFields("Anna Right", "+1 415 555 0123", "typed@example.com", "77 Typed Ave"),
            ContactImport.copy(edited, right, typedFirst).after,
        )
        // A field the Stylist emptied after the import stays empty.
        assertEquals("", ContactImport.copy(typedFirst.after.copy(email = ""), right, typedFirst).after.email)

        // An existing Client gets the saved email and address back, not those of the wrong contact.
        val saved = ContactFields("Vera Full", "+7 916 123-45-67", "vera@example.com", "ул. Садовая, 12")
        val overSaved = ContactImport.copy(saved, wrong)
        assertEquals(
            ContactFields("Anna Right", "+1 415 555 0123", "vera@example.com", "ул. Садовая, 12"),
            ContactImport.copy(overSaved.after, right, overSaved).after,
        )

        // After a save there is no import to take back: what the first contact gave is the Client's own.
        assertEquals(
            ContactFields("Anna Right", "+1 415 555 0123", "wrong@example.com", "1 Wrong St"),
            ContactImport.copy(first.after, right).after,
        )
    }

    @Test
    fun `one-line text is cleaned the way the server cleans it`() {
        assertEquals("Paste Test second line", OneLine.clean("Paste Test\nsecond line"))
        assertEquals("ZoeCtrl", OneLine.clean("Zoe\u0001Ctrl"))
        assertEquals("Kim Vert", OneLine.clean("Kim\u000BVert"))
        assertEquals("a b", OneLine.clean("a \n \r\n b"))
        assertEquals("a b c", OneLine.clean("\ta b\u0085 c\r\n"))
        assertEquals("Anna Petrova", OneLine.clean("  Anna Petrova ﻿"))
        assertEquals("Anna  Petrova", OneLine.clean("Anna  Petrova"))
        assertEquals("", OneLine.clean("\u0001"))
        assertEquals("", OneLine.clean(" \n\t "))
        assertEquals("", OneLine.clean(""))
        // A zero-width joiner inside a name is kept: emoji and some scripts need it.
        assertEquals("a‍b", OneLine.clean("a‍b"))
    }

    @Test
    fun `a one-line field keeps what is typed and takes pasted text as one line`() {
        // Typing: nothing is touched, so a space can be typed before the next word.
        listOf("", "Anna", "Anna ", "  Anna  Petrova ", "Жан-Поль д’Арк", "👩🏽‍🦰 Zoë").forEach { assertEquals(it, it, OneLine.typed(it)) }

        assertEquals("Paste Test second line", OneLine.typed("Paste Test\nsecond line"))
        assertEquals("Paste Test second line", OneLine.typed("Paste Test \r\n\n  second line"))
        assertEquals("ZoeCtrl", OneLine.typed("Zoe\u0001Ctrl"))
        assertEquals("Anna Petrova ", OneLine.typed("Anna\tPetrova "))
        assertEquals("Anna", OneLine.typed("Anna\n"))
        assertEquals("", OneLine.typed("\n"))

        // What the field holds is saved as the server would save the pasted text.
        listOf("Paste Test\nsecond line", " a \u0001\n b ", "\u0001\nb", "a\n \u0001 \nb", "x\u007F\u009Fy ").forEach {
            assertEquals(OneLine.clean(it), OneLine.typed(it).trim())
        }
    }

    @Test
    fun `a name needs a character that can be seen`() {
        listOf("", " ", "\u0001", "​", "  ", "﻿", "󠀁").forEach { assertFalse(OneLine.hasVisibleCharacter(it)) }
        listOf("A", " я ", "😀", "​x", "-").forEach { assertTrue(it, OneLine.hasVisibleCharacter(it)) }
    }

    @Test
    fun `an email is empty or one address with a domain`() {
        listOf("", "  ", "name@example.com", " name@example.com ", "first.last+tag@mail.example.co.uk", "анна@почта.рф", "a@b.c").forEach {
            assertTrue(it, ClientRules.isEmail(it))
        }
        listOf(
            "not an email@@", "plainaddress", "name@", "@example.com", "name@example", "name@.com", "name@example.",
            "name@example..com", "na me@example.com", "name@exa mple.com", "a@b@example.com", "name@example.com, other@example.com",
        ).forEach {
            assertFalse(it, ClientRules.isEmail(it))
        }
    }

    @Test
    fun `a Client whose saved name holds a control character is found by the name as it reads`() {
        val clients = listOf(client("1", "Zoe\u0001Ctrl"), client("2", "Vera\tTab"), client("3", "Anna Petrova"))

        assertEquals(listOf("1"), found(clients, "zoectrl"))
        assertEquals(listOf("2"), found(clients, "veratab"))
        assertEquals(listOf("1"), found(clients, "zoe"))
        assertEquals(listOf("3"), found(clients, "anna p"))
    }

    @Test
    fun `reminder recipient keeps only digits and the plus sign`() {
        assertEquals("+15125550100", ClientReminder.recipient(" +1 (512) 555-0100 "))
        assertEquals("5550100", ClientReminder.recipient("555-0100 (mobile)"))
    }

    @Test
    fun `a phone with no digit to dial is no phone for a reminder`() {
        // Each of these used to open the SMS app with an empty recipient.
        for (phone in listOf("", "   ", "-", "n/a", "abc-CALL-me", "+", "+ ()")) {
            assertNull(phone, ClientReminder.recipient(phone))
        }
    }

    @Test
    fun `a reminder is offered only for a scheduled Appointment`() {
        fun offered(status: AppointmentStatus) = ClientReminder.isOffered(
            Appointment(id = "a", primaryClientId = "c", primaryClientName = "Rita", startsAt = "2026-10-09T09:00:00.000Z", status = status, locationType = LocationType.IN_SALON),
        )

        // Its text says "your appointment is scheduled for", which no other status makes true.
        assertEquals(listOf(AppointmentStatus.SCHEDULED), AppointmentStatus.entries.filter(::offered))
    }

    @Test
    fun `anything that is not English falls back to Russian`() {
        assertEquals(Language.EN, normalizeLanguage("en-US"))
        assertEquals(Language.RU, normalizeLanguage("ru-RU"))
        assertEquals(Language.RU, normalizeLanguage("de-DE"))
        assertEquals(Language.RU, normalizeLanguage(null))
    }

    @Test
    fun `only a Russian locale is shown in Russian, whatever the fallback would choose`() {
        assertEquals(Language.RU, stringsLanguage("ru"))
        assertEquals(Language.RU, stringsLanguage("ru-KZ"))
        assertEquals(Language.EN, stringsLanguage("en-GB"))
        // No German strings: Android shows the default set, which is English.
        assertEquals(Language.EN, stringsLanguage("de-DE"))
        assertEquals(Language.EN, stringsLanguage(null))
    }

    @Test
    fun `base url accepts the web origin or the full tRPC url`() {
        assertEquals("http://10.0.2.2:3000", AppConfig.normalizeBaseUrl("http://10.0.2.2:3000/api/trpc/"))
        assertEquals("https://hcm.example.com", AppConfig.normalizeBaseUrl(" https://hcm.example.com/ "))
    }

    @Test
    fun `times are shown and built in the stylist timezone`() {
        val format = TimeFormat(Locale.US, ZoneId.of("America/Chicago"))
        val appointment = Appointment(
            id = "a",
            primaryClientId = "c",
            primaryClientName = "Anna",
            startsAt = "2026-06-13T19:00:00.000Z",
            endsAt = "2026-06-13T20:30:00.000Z",
            status = AppointmentStatus.SCHEDULED,
            locationType = LocationType.IN_SALON,
        )

        assertEquals(LocalDate.of(2026, 6, 13), format.zoned(appointment.startsAt).toLocalDate())
        assertEquals(14, format.zoned(appointment.startsAt).hour)
        assertEquals(Instant.parse("2026-06-13T19:00:00Z"), format.toInstant(LocalDate.of(2026, 6, 13), LocalTime.of(14, 0)))

        val (start, end) = format.dayRange(LocalDate.of(2026, 6, 13))
        assertEquals(Instant.parse("2026-06-13T05:00:00Z"), start)
        assertEquals(Instant.parse("2026-06-14T05:00:00Z"), end)
    }

    @Test
    fun `dates and clock follow the app language`() {
        val day = LocalDate.of(2026, 10, 8)
        val english = TimeFormat(Locale.US, ZoneId.of("UTC"))
        val russian = TimeFormat(Locale.forLanguageTag("ru-RU"), ZoneId.of("UTC"))

        assertEquals("Thursday, October 8", english.longDay(day))
        assertEquals("Четверг, 8 октября", russian.longDay(day))
        assertTrue(english.uses12HourClock)
        assertFalse(russian.uses12HourClock)
        assertEquals("14:00", russian.time(LocalTime.of(14, 0)))
    }

    @Test
    fun `times follow the device's 12 or 24-hour setting, whatever the app language`() {
        val utc = ZoneId.of("UTC")
        val afternoon = LocalTime.of(14, 0)
        val appointment = Appointment(
            id = "a",
            primaryClientId = "c",
            primaryClientName = "Anna",
            startsAt = "2026-06-13T14:00:00.000Z",
            endsAt = "2026-06-13T15:30:00.000Z",
            status = AppointmentStatus.SCHEDULED,
            locationType = LocationType.IN_SALON,
        )
        // Which space stands before AM/PM depends on the platform's locale data.
        fun TimeFormat.plain(time: LocalTime) = time(time).replace(Regex("\\p{Zs}"), " ")

        // English on a device set to 24 hours: no AM/PM anywhere, and the time picker has none either.
        val english24 = TimeFormat(Locale.US, utc, uses24HourClock = true)
        assertFalse(english24.uses12HourClock)
        assertEquals("14:00", english24.time(afternoon))
        assertEquals("09:05", english24.time(LocalTime.of(9, 5)))
        assertEquals("14:00", english24.time(appointment.startsAt))
        assertEquals("14:00 – 15:30", english24.timeRange(appointment))

        // Russian on a device set to 12 hours.
        val russian12 = TimeFormat(Locale.forLanguageTag("ru-RU"), utc, uses24HourClock = false)
        assertTrue(russian12.uses12HourClock)
        assertEquals("2:00 PM", russian12.plain(afternoon))
        assertEquals("9:05 AM", russian12.plain(LocalTime.of(9, 5)))

        // The setting that agrees with the language changes nothing.
        val english12 = TimeFormat(Locale.US, utc, uses24HourClock = false)
        assertTrue(english12.uses12HourClock)
        assertEquals("2:00 PM", english12.plain(afternoon))
        assertEquals(TimeFormat(Locale.US, utc).time(afternoon), english12.time(afternoon))
        assertEquals("14:00", TimeFormat(Locale.forLanguageTag("ru-RU"), utc, uses24HourClock = true).time(afternoon))
    }

    @Test
    fun `a day of another year is shown with its year`() {
        val utc = ZoneId.of("UTC")
        val english = TimeFormat(Locale.US, utc, thisYear = 2026)
        val russian = TimeFormat(Locale.forLanguageTag("ru-RU"), utc, thisYear = 2026)
        val thisYear = LocalDate.of(2026, 10, 8)
        val lastYear = LocalDate.of(2025, 1, 10)
        val nextYear = LocalDate.of(2027, 10, 9)
        fun String.plain() = replace(Regex("\\p{Zs}"), " ")

        // The New Appointment date field and the Calendar's day label.
        assertEquals("Thursday, October 8", english.longDay(thisYear))
        assertEquals("Friday, January 10, 2025", english.longDay(lastYear))
        // The same day and month as today, in the wrong year: only the year tells.
        assertEquals("Saturday, October 9, 2027", english.longDay(nextYear))
        assertEquals("Четверг, 8 октября", russian.longDay(thisYear))
        assertEquals("Пятница, 10 января 2025 г.", russian.longDay(lastYear).plain())

        // The date over an Appointment card that needs an outcome.
        assertEquals("Oct 8", english.shortDate(thisYear))
        assertEquals("Jan 10, 2025", english.shortDate(lastYear))
        assertEquals("Oct 9, 2027", english.shortDate(nextYear))
        assertFalse(russian.shortDate(thisYear).contains("2026"))
        assertTrue(russian.shortDate(lastYear), russian.shortDate(lastYear).matches(Regex("10 янв\\.? 2025")))

        // A format that was not told the year shows none.
        assertEquals("Friday, January 10", TimeFormat(Locale.US, utc).longDay(lastYear))
        assertEquals("Friday, January 10", TimeFormat(Locale.US, utc).dayTitle(lastYear))
        assertEquals("Jan 10", TimeFormat(Locale.US, utc).shortDate(lastYear))
    }

    @Test
    fun `the Appointment title of a day of another year keeps its year on one line`() {
        val utc = ZoneId.of("UTC")
        val english = TimeFormat(Locale.US, utc, thisYear = 2026)
        val russian = TimeFormat(Locale.forLanguageTag("ru-RU"), utc, thisYear = 2026)

        // A day of this year is titled as before.
        assertEquals("Thursday, October 8", english.dayTitle(LocalDate.of(2026, 10, 8)))
        assertEquals("Четверг, 8 октября", russian.dayTitle(LocalDate.of(2026, 10, 8)))

        assertEquals("Fri, Jan 10, 2025", english.dayTitle(LocalDate.of(2025, 1, 10)))
        assertEquals("Sat, Oct 9, 2027", english.dayTitle(LocalDate.of(2027, 10, 9)))
        // In full these are longer than the line and their year was cut: "Tuesday, September 30, 2025", "Понедельник, 22 декабря 2025 г.".
        assertEquals("Tue, Sep 30, 2025", english.dayTitle(LocalDate.of(2025, 9, 30)))
        assertTrue(russian.dayTitle(LocalDate.of(2025, 12, 22)), russian.dayTitle(LocalDate.of(2025, 12, 22)).matches(Regex("Пн, 22 дек\\.? 2025")))
        assertTrue(russian.dayTitle(LocalDate.of(2025, 9, 28)), russian.dayTitle(LocalDate.of(2025, 9, 28)).matches(Regex("Вс, 28 сент\\.? 2025")))

        // Whatever the day, the title of another year ends with the year and is shorter than every title of this year that fills the line.
        for (format in listOf(english, russian)) {
            for (year in listOf(2025, 2027)) {
                for (date in (0L until 365).map { LocalDate.of(year, 1, 1).plusDays(it) }) {
                    val title = format.dayTitle(date)

                    assertTrue(title, title.endsWith(" $year"))
                    // "Wed, Sep 30, 2025" and "Ср, 30 сент. 2025"; the longest of this year, "Понедельник, 30 сентября", has 24.
                    assertTrue(title, title.length <= 17)
                }
            }
        }
    }

    @Test
    fun `a time the clocks skip when they go forward is no instant`() {
        val berlin = TimeFormat(Locale.US, ZoneId.of("Europe/Berlin"))
        val springForward = LocalDate.of(2027, 3, 28)

        // 2:00 to 2:59 does not exist that night; it used to be moved an hour later without a word.
        assertNull(berlin.toInstant(springForward, LocalTime.of(2, 0)))
        assertNull(berlin.toInstant(springForward, LocalTime.of(2, 30)))
        assertNull(berlin.toInstant(springForward, LocalTime.of(2, 59)))
        assertEquals(Instant.parse("2027-03-28T00:59:00Z"), berlin.toInstant(springForward, LocalTime.of(1, 59)))
        assertEquals(Instant.parse("2027-03-28T01:00:00Z"), berlin.toInstant(springForward, LocalTime.of(3, 0)))
        assertNull(TimeFormat(Locale.US, ZoneId.of("America/New_York")).toInstant(LocalDate.of(2027, 3, 14), LocalTime.of(2, 30)))

        // The same time on any other day, and the hour that comes twice when the clocks go back, are times like any other.
        assertEquals(Instant.parse("2027-03-27T01:30:00Z"), berlin.toInstant(springForward.minusDays(1), LocalTime.of(2, 30)))
        assertEquals(Instant.parse("2027-10-31T00:30:00Z"), berlin.toInstant(LocalDate.of(2027, 10, 31), LocalTime.of(2, 30)))
        // A timezone without daylight saving skips nothing.
        assertEquals(Instant.parse("2027-03-28T02:30:00Z"), TimeFormat(Locale.US, ZoneId.of("UTC")).toInstant(springForward, LocalTime.of(2, 30)))
    }

    // --- date picker ---

    @Test
    fun `a day in the date picker is named in full, in the app language`() {
        val day = LocalDate.of(2026, 10, 8)

        assertEquals("Thursday, October 8, 2026", TimeFormat(Locale.US, ZoneId.of("UTC")).fullDate(day))
        // Which no-break space keeps the year and its "г." together depends on the platform's locale data.
        assertEquals(
            "Четверг, 8 октября 2026 г.",
            TimeFormat(Locale.forLanguageTag("ru-RU"), ZoneId.of("UTC")).fullDate(day).replace(Regex("\\p{Zs}"), " "),
        )
    }

    @Test
    fun `the week starts on Sunday in US English and on Monday in Russian`() {
        assertEquals(DayOfWeek.SUNDAY, TimeFormat(Locale.US, ZoneId.of("UTC")).firstDayOfWeek)
        assertEquals(DayOfWeek.MONDAY, TimeFormat(Locale.forLanguageTag("ru-RU"), ZoneId.of("UTC")).firstDayOfWeek)
    }

    @Test
    fun `a month is laid out in whole weeks from the first day of the week`() {
        // 1 October 2026 is a Thursday.
        val sundayFirst = weeksOf(YearMonth.of(2026, 10), DayOfWeek.SUNDAY)
        val mondayFirst = weeksOf(YearMonth.of(2026, 10), DayOfWeek.MONDAY)

        assertEquals(listOf(null, null, null, null, 1, 2, 3), sundayFirst.first().map { it?.dayOfMonth })
        assertEquals(listOf(25, 26, 27, 28, 29, 30, 31), sundayFirst.last().map { it?.dayOfMonth })
        assertEquals(listOf(null, null, null, 1, 2, 3, 4), mondayFirst.first().map { it?.dayOfMonth })
        assertEquals(listOf(26, 27, 28, 29, 30, 31, null), mondayFirst.last().map { it?.dayOfMonth })
        assertEquals(5, mondayFirst.size)
        assertEquals((1..31).map { LocalDate.of(2026, 10, it) }, mondayFirst.flatten().filterNotNull())
    }

    @Test
    fun `a month takes between four and six weeks`() {
        // February 2026 starts on a Sunday, August 2026 on a Saturday.
        val shortest = weeksOf(YearMonth.of(2026, 2), DayOfWeek.SUNDAY)
        val longest = weeksOf(YearMonth.of(2026, 8), DayOfWeek.MONDAY)

        assertEquals(4, shortest.size)
        assertTrue(shortest.flatten().none { it == null })
        assertEquals(6, longest.size)
        assertEquals(listOf(null, null, null, null, null, 1, 2), longest.first().map { it?.dayOfMonth })
        assertEquals(listOf(31, null, null, null, null, null, null), longest.last().map { it?.dayOfMonth })
        assertTrue(longest.all { it.size == 7 })
    }
}
