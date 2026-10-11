package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.auth.AuthState
import com.northphoenix.hairdresserclientmanager.auth.CodePurpose
import com.northphoenix.hairdresserclientmanager.auth.SessionCheck
import com.northphoenix.hairdresserclientmanager.auth.SignInError
import com.northphoenix.hairdresserclientmanager.data.ApiException
import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.data.LocationType
import com.northphoenix.hairdresserclientmanager.data.PhotoPreparer
import com.northphoenix.hairdresserclientmanager.data.isConnectionFailure
import com.northphoenix.hairdresserclientmanager.domain.Amount
import com.northphoenix.hairdresserclientmanager.domain.ClientReminder
import com.northphoenix.hairdresserclientmanager.domain.TimeFormat
import com.northphoenix.hairdresserclientmanager.i18n.bodyRes
import com.northphoenix.hairdresserclientmanager.i18n.clientReminderTemplate
import com.northphoenix.hairdresserclientmanager.i18n.emptyClientPickerText
import com.northphoenix.hairdresserclientmanager.i18n.messageRes
import com.northphoenix.hairdresserclientmanager.i18n.refusalRes
import com.northphoenix.hairdresserclientmanager.i18n.refusedSignInMessage
import com.northphoenix.hairdresserclientmanager.i18n.toLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.ZoneId
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

    /** Every `plurals` entry by name, with the text of each of its quantities. */
    private fun plurals(directory: String): Map<String, Map<String, String>> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/$directory/strings.xml"))
        val nodes = document.getElementsByTagName("plurals")

        return (0 until nodes.length).associate { index ->
            val items = (nodes.item(index) as org.w3c.dom.Element).getElementsByTagName("item")

            nodes.item(index).attributes.getNamedItem("name").nodeValue to
                (0 until items.length).associate { items.item(it).attributes.getNamedItem("quantity").nodeValue to items.item(it).textContent }
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
    fun `every counted text has each form its language needs`() {
        val englishPlurals = plurals("values")
        val russianPlurals = plurals("values-ru")

        assertEquals(englishPlurals.keys, russianPlurals.keys)
        for ((name, forms) in englishPlurals) {
            assertEquals(name, setOf("one", "other"), forms.keys)
        }
        for ((name, forms) in russianPlurals) {
            assertEquals(name, setOf("one", "few", "many", "other"), forms.keys)
            // In Russian "one" is also 21, 31 and so on, so every form names the number.
            forms.forEach { (quantity, text) -> assertTrue("$name $quantity", text.contains("%d")) }
        }
        for ((name, text) in (englishPlurals.values + russianPlurals.values).flatMap { it.entries }.map { it.key to it.value }) {
            assertTrue(name, text.isNotBlank())
        }
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
    fun `the Home heading for Appointments that need an outcome does not use the word for the total`() {
        // 'Итог' is the title of the money card: a heading with that word says that a total is needed, not a status.
        val total = russian.getValue("appointment_totals_title").text.lowercase()

        assertFalse(russian.getValue("home_unresolved_section").text.lowercase().contains(total))
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
        // A picked picture that could not be made into a photo: said as what it is, not as a failed upload.
        assertEquals(R.string.error_image_unreadable, ApiException("The selected image could not be read.", code = PhotoPreparer.UNREADABLE).messageRes())
        assertEquals(R.string.appointment_photo_too_large, ApiException("The photo is larger than 8 MB.", code = PhotoPreparer.TOO_LARGE).messageRes())
    }

    @Test
    fun `a refusal about the Clients of an Appointment says what it is about`() {
        fun refusal(message: String) = ApiException(message, code = "BAD_REQUEST").messageRes()

        // H1: photos keep a Client in the Appointment as Services do, and the message says which of the two it is.
        assertEquals(R.string.error_client_has_photos, refusal("Client has Appointment Photos attached to this Appointment."))
        assertEquals(R.string.error_client_has_services, refusal("Client has Services attached to this Appointment."))
        assertEquals(R.string.error_appointment_needs_client, refusal("Appointment must keep at least one Client."))
        // L29: a Client who was removed on another device is not "check what you entered", whatever was tried with them.
        listOf(
            "Client must be attached to the Appointment.",
            "Primary Client must be attached to the Appointment.",
            "Service Client must be attached to the Appointment.",
            "Photo Client must be attached to the Appointment.",
        ).forEach { assertEquals(it, R.string.error_client_not_in_appointment, refusal(it)) }
    }

    @Test
    fun `a Client picker with nothing to list says why`() {
        assertEquals(R.string.no_clients_title, emptyClientPickerText(clients = 0, offered = 0))
        // 'Add Client' on an Appointment that every Client is in already: not "No Clients yet", with or without a search.
        assertEquals(R.string.all_clients_in_appointment, emptyClientPickerText(clients = 8, offered = 0))
        assertEquals(R.string.all_clients_in_appointment, emptyClientPickerText(clients = 1, offered = 0))
        // Clients are offered, and the search finds none of them.
        assertEquals(R.string.no_client_results, emptyClientPickerText(clients = 8, offered = 3))
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

    // --- signing in ---

    @Test
    fun `every reason a step of signing in is refused for has a message of its own`() {
        assertEquals(SignInError.entries.size, SignInError.entries.map { it.messageRes() }.toSet().size)

        // What the report saw in English: a mistyped email, a wrong password, a wrong code.
        assertEquals(R.string.sign_in_error_email_invalid, SignInError.EMAIL_INVALID.messageRes())
        assertEquals(R.string.sign_in_error_wrong_password, SignInError.WRONG_PASSWORD.messageRes())
        assertEquals(R.string.sign_in_error_wrong_code, SignInError.WRONG_CODE.messageRes())
        // No answer from the sign-in provider is not the app's own server being away.
        assertEquals(R.string.error_sign_in_unreachable, SignInError.NETWORK.messageRes())
        assertEquals(R.string.error_generic, SignInError.GENERIC.messageRes())
    }

    @Test
    fun `the code step says what the code is for and where it was sent`() {
        assertEquals(CodePurpose.entries.size, CodePurpose.entries.map { it.bodyRes() }.toSet().size)

        for (name in listOf("sign_in_code_body_sign_up", "sign_in_code_body_sign_in", "sign_in_code_body_new_device", "sign_in_code_body_password_reset")) {
            // The email address is the one argument.
            assertEquals(name, listOf("%1\$s"), placeholder.findAll(russian.getValue(name).text).map { it.value }.toList())
        }
    }

    @Test
    fun `the Russian sign-in screens have no English word on them`() {
        // The sign-in screens are the first thing a Stylist sees. The example address is the one thing left in Latin letters.
        val latin = Regex("[A-Za-z]")
        val signIn = russian.filterKeys { it.startsWith("sign_in_") || it.startsWith("error_sign_in_") || it == "error_generic" || it == "error_session_ended" }

        assertTrue(signIn.size > 40)

        for ((name, translation) in signIn) {
            val text = translation.text.replace("name@example.com", "").replace(placeholder, "")

            assertFalse("$name: ${translation.text}", latin.containsMatchIn(text))
        }
    }

    // --- Client Reminder ---

    // The basic character set of an SMS (GSM 03.38). A message written in it holds 160 characters;
    // one character from outside it makes the message a Unicode SMS of 70.
    private val plainSmsAlphabet =
        "@£\$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"

    private val utc = ZoneId.of("UTC")

    private fun reminderAppointment(address: String = "", location: LocationType = LocationType.IN_SALON, endsAt: String? = null) =
        Appointment(
            id = "a",
            primaryClientId = "c",
            primaryClientName = "Rita",
            startsAt = "2026-10-09T09:00:00.000Z",
            endsAt = endsAt,
            status = AppointmentStatus.SCHEDULED,
            locationType = location,
            locationAddress = address,
        )

    /** The reminder as the Appointment screen composes it, with the wording of the string files. */
    private fun reminder(language: Language, appointment: Appointment): String {
        val name = when (clientReminderTemplate(appointment)) {
            R.string.client_reminder_message -> "client_reminder_message"
            R.string.client_reminder_message_at_home -> "client_reminder_message_at_home"
            else -> "client_reminder_message_in_salon"
        }
        val template = (if (language == Language.RU) russian else english).getValue(name).text

        return ClientReminder.message(template, appointment, language.toLocale(), utc)
    }

    @Test
    fun `the English Client Reminder is written in the plain SMS alphabet`() {
        val withEnd = reminderAppointment(address = "5 Verify Lane", endsAt = "2026-10-09T10:30:00.000Z")

        // On screen the time has a narrow no-break space before AM and the range an en dash; an SMS has neither.
        assertEquals(
            "Reminder: your appointment is scheduled for Oct 9, 2026, 9:00 AM - 10:30 AM. Location: 5 Verify Lane.",
            reminder(Language.EN, withEnd),
        )

        val typed = reminderAppointment(address = "5\u00A0O\u2019Brien St \u2014 \u201Crear\u201D door", endsAt = "2026-10-09T10:30:00.000Z")
        val reminders = listOf(
            withEnd,
            typed,
            reminderAppointment(),
            reminderAppointment(location = LocationType.AT_HOME),
        ).map { reminder(Language.EN, it) }

        for (text in reminders) {
            assertEquals(text, "", text.filterNot { it in plainSmsAlphabet })
            // One SMS.
            assertTrue(text, text.length <= 160)
        }
        assertTrue(reminders[1], reminders[1].endsWith("Location: 5 O'Brien St - \"rear\" door."))
    }

    @Test
    fun `the Russian Client Reminder keeps the way Russian writes a time`() {
        val appointment = reminderAppointment(address = "ул. Ленина, 5", endsAt = "2026-10-09T10:30:00.000Z")
        val time = TimeFormat(Language.RU.toLocale(), utc).dateAndTimeRange(appointment)

        // It is a Unicode SMS because of its letters, so nothing is gained by changing its dash.
        assertTrue(time, time.endsWith("09:00 – 10:30"))
        assertEquals("Напоминание: ваша запись назначена на $time. Адрес: ул. Ленина, 5.", reminder(Language.RU, appointment))
    }

    @Test
    fun `a Client Reminder without an address names the kind of place, not an address`() {
        val inSalon = reminderAppointment()
        val atHome = reminderAppointment(location = LocationType.AT_HOME)
        val russianTime = TimeFormat(Language.RU.toLocale(), utc).dateAndTimeRange(inSalon)

        // It used to read "Адрес: В салоне." and "Адрес: На дому.".
        assertEquals("Напоминание: ваша запись назначена на $russianTime. Место: в салоне.", reminder(Language.RU, inSalon))
        assertEquals("Напоминание: ваша запись назначена на $russianTime. Место: на дому.", reminder(Language.RU, atHome))
        assertEquals("Reminder: your appointment is scheduled for Oct 9, 2026, 9:00 AM. Location: in the salon.", reminder(Language.EN, inSalon))
        assertEquals("Reminder: your appointment is scheduled for Oct 9, 2026, 9:00 AM. Location: at home.", reminder(Language.EN, atHome))

        // An address of spaces is no address.
        assertEquals(reminder(Language.RU, inSalon), reminder(Language.RU, reminderAppointment(address = "  ")))

        // With an address the reminder gives it, wherever the Appointment is.
        for (location in LocationType.entries) {
            val withAddress = reminderAppointment(address = "5 Verify Lane", location = location)

            assertEquals("Напоминание: ваша запись назначена на $russianTime. Адрес: 5 Verify Lane.", reminder(Language.RU, withAddress))
            assertEquals("Reminder: your appointment is scheduled for Oct 9, 2026, 9:00 AM. Location: 5 Verify Lane.", reminder(Language.EN, withAddress))
        }
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
