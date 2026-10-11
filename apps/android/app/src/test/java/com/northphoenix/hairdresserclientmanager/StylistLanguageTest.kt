package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.data.Stylist
import com.northphoenix.hairdresserclientmanager.i18n.AppLanguageSetting
import com.northphoenix.hairdresserclientmanager.i18n.AppLocales
import com.northphoenix.hairdresserclientmanager.i18n.stringsLanguage
import com.northphoenix.hairdresserclientmanager.state.SettingsRequests
import com.northphoenix.hairdresserclientmanager.state.StylistLanguage
import com.northphoenix.hairdresserclientmanager.state.withSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.CoroutineContext

/**
 * The app language: what the screen is in, what the selector shows and what is saved are one
 * language, whichever way the Stylist chose it and however the save went.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StylistLanguageTest {
    /** Stands in for Android and the device. What it keeps outlives the app's process, as on a device. */
    private class Device(override var device: String = "en-US") : AppLocales {
        override var app: String? = null
        override var chosenByApp: String? = null

        /** The language the screen is in, which is also the option the selector shows as selected. */
        val shown: Language get() = stringsLanguage(app ?: device)
    }

    /** Stands in for the server: keeps the saved language, and answers each save only when the test says so. */
    private class Server(var language: Language) {
        val requests = mutableListOf<Language>()
        private val replies = ArrayDeque<CompletableDeferred<Unit>>()

        suspend fun save(language: Language) {
            requests += language
            CompletableDeferred<Unit>().also(replies::addLast).await()
            this.language = language
        }

        fun answer() = replies.removeFirst().complete(Unit)

        fun fail() = replies.removeFirst().completeExceptionally(IOException("offline"))
    }

    /** The app as far as its language goes, wired the way the view model wires it. A new one is the app after a restart. */
    private class App(val device: Device, val server: Server, onboarded: Boolean = true, private val scope: TestScope) {
        val language = StylistLanguage(AppLanguageSetting(device))
        var stylist = Stylist("s1", server.language, "Etc/UTC", onboardingCompletedAt = "2026-10-01T00:00:00Z".takeIf { onboarded })
        var failures = 0

        /** The Stylist was loaded from the server, or the app's locale changed. */
        fun follow() {
            language.follow(stylist)?.let(::choose)
        }

        /** Coming back to the app loads the Stylist again. */
        fun loadStylist() {
            stylist = stylist.copy(language = server.language)
            follow()
        }

        /** A tap on the selector, or a language chosen in Android's settings that [follow] found. */
        fun choose(chosen: Language) {
            if (!language.choose(stylist, chosen)) {
                return
            }

            scope.launch {
                try {
                    language.save(saved = { stylist.language }) { next ->
                        server.save(next)
                        stylist = stylist.copy(language = next)
                    }
                } catch (_: IOException) {
                    failures++
                }
            }
            scope.runCurrent()
        }

        /** 'Save and continue' on the onboarding screen. */
        fun finishOnboarding() {
            server.language = language.toSave(stylist)
            stylist = stylist.copy(language = server.language, onboardingCompletedAt = "2026-10-09T00:00:00Z")
            follow()
        }
    }

    private fun TestScope.app(device: Device, server: Server, onboarded: Boolean = true) =
        App(device, server, onboarded, this).also(App::loadStylist)

    private fun assertEverywhere(language: Language, app: App) {
        assertEquals("on screen", language, app.device.shown)
        assertEquals("known as saved", language, app.stylist.language)
        assertEquals("on the server", language, app.server.language)
    }

    // --- onboarding (M25, L72) ---

    @Test
    fun `the language chosen during onboarding survives a restart and is the one saved`() = runTest {
        val device = Device("en-US")
        val server = Server(Language.EN)

        app(device, server, onboarded = false).choose(Language.RU)
        assertEquals(Language.RU, device.shown)
        assertEquals("nothing is saved before 'Save and continue'", emptyList<Language>(), server.requests)

        // Android killed the app. The server still has the language the Stylist was created with.
        val restarted = app(device, server, onboarded = false)
        assertEquals(Language.RU, device.shown)

        restarted.finishOnboarding()
        assertEverywhere(Language.RU, restarted)
    }

    @Test
    fun `the reverse - English chosen on a Russian start survives the restart too`() = runTest {
        val device = Device("ru-RU")
        val server = Server(Language.RU)

        app(device, server, onboarded = false).choose(Language.EN)
        val restarted = app(device, server, onboarded = false)
        restarted.finishOnboarding()

        assertEverywhere(Language.EN, restarted)
    }

    @Test
    fun `on a device in a third language onboarding is in the Stylist's language, Russian`() = runTest {
        val device = Device("de-DE")
        // German strings do not exist: before the Stylist is known, Android shows the default English ones.
        assertEquals(Language.EN, device.shown)

        // The Stylist was created with the fallback for a device that is not in English.
        val app = app(device, Server(Language.RU), onboarded = false)
        assertEquals(Language.RU, device.shown)

        // 'Русский' is the selected option; a tap on it changes nothing, a tap on 'English' switches.
        app.choose(Language.RU)
        assertEquals(Language.RU, device.shown)
        app.choose(Language.EN)
        assertEquals(Language.EN, device.shown)

        app.finishOnboarding()
        assertEverywhere(Language.EN, app)
    }

    @Test
    fun `a per-app locale the app has no strings for is replaced by the language it stands for`() = runTest {
        // German first: the screen was English, the device counted as Russian, and 'Save and continue' flipped the app.
        val device = Device("en-US").apply { app = "de-DE" }
        val app = app(device, Server(Language.RU), onboarded = false)

        assertEquals("ru", device.app)
        assertEquals(Language.RU, device.shown)

        app.finishOnboarding()
        assertEverywhere(Language.RU, app)
    }

    // --- taps in quick succession (L68) ---

    @Test
    fun `English and quickly back to Russian ends on Russian, on screen and on the server`() = runTest {
        val device = Device()
        val server = Server(Language.RU)
        val app = app(device, server)

        app.choose(Language.EN)
        assertEquals(Language.EN, device.shown)
        // English is still on its way. The saved language is still Russian, and yet this tap is a change.
        app.choose(Language.RU)
        assertEquals(Language.RU, device.shown)
        assertEquals(listOf(Language.EN), server.requests)

        server.answer()
        runCurrent()
        assertEquals(listOf(Language.EN, Language.RU), server.requests)
        server.answer()
        runCurrent()

        assertEverywhere(Language.RU, app)
        assertEquals(0, app.failures)
    }

    @Test
    fun `back to the language already on its way sends nothing more`() = runTest {
        val device = Device()
        val server = Server(Language.RU)
        val app = app(device, server)

        app.choose(Language.EN)
        app.choose(Language.RU)
        app.choose(Language.EN)
        server.answer()
        runCurrent()

        assertEquals(listOf(Language.EN), server.requests)
        assertEverywhere(Language.EN, app)
    }

    @Test
    fun `a tap on the language that is shown and saved sends nothing`() = runTest {
        val server = Server(Language.RU)
        val app = app(Device(), server)

        app.choose(Language.RU)

        assertEquals(emptyList<Language>(), server.requests)
        assertEverywhere(Language.RU, app)
    }

    @Test
    fun `the app's locale changing while a choice is being saved does not put the saved language back`() = runTest {
        val device = Device()
        val server = Server(Language.RU)
        val app = app(device, server)

        app.choose(Language.EN)
        // The switch arrives at the screen as a new locale, which makes the app look at its language again.
        app.follow()
        assertEquals(Language.EN, device.shown)

        server.answer()
        runCurrent()
        app.follow()

        assertEverywhere(Language.EN, app)
        assertEquals(listOf(Language.EN), server.requests)
    }

    // --- a save that fails (L64) ---

    @Test
    fun `a switch that cannot be saved is rolled back and reported`() = runTest {
        val device = Device()
        val server = Server(Language.RU)
        val app = app(device, server)

        app.choose(Language.EN)
        assertEquals(Language.EN, device.shown)
        server.fail()
        runCurrent()

        assertEquals(1, app.failures)
        assertEverywhere(Language.RU, app)

        // Nothing is left to flip the app later: not the next start, not the next load of the Stylist.
        app(device, server)
        assertEquals(Language.RU, device.shown)
        assertEquals(listOf(Language.EN), server.requests)
    }

    @Test
    fun `after a failed switch the same tap tries again`() = runTest {
        val device = Device()
        val server = Server(Language.RU)
        val app = app(device, server)

        app.choose(Language.EN)
        server.fail()
        runCurrent()
        app.choose(Language.EN)
        server.answer()
        runCurrent()

        assertEverywhere(Language.EN, app)
    }

    @Test
    fun `when the last of two quick switches fails the app goes back to the one that was saved`() = runTest {
        val device = Device()
        val server = Server(Language.RU)
        val app = app(device, server)

        app.choose(Language.EN)
        app.choose(Language.RU)
        app.choose(Language.EN)
        app.choose(Language.RU)
        server.answer()
        runCurrent()
        // English is saved by now, the screen is in Russian, and Russian cannot be saved.
        server.fail()
        runCurrent()

        assertEquals(1, app.failures)
        assertEverywhere(Language.EN, app)
    }

    // --- Android's per-app language settings (L63) ---

    @Test
    fun `a language chosen in Android's settings while the app is open is saved`() = runTest {
        val device = Device()
        val server = Server(Language.RU)
        val app = app(device, server)

        device.app = "en-US"
        app.follow()
        assertEquals(listOf(Language.EN), server.requests)
        server.answer()
        runCurrent()

        assertEverywhere(Language.EN, app)
        assertEquals("the region chosen in Android's settings is kept", "en-US", device.app)

        // And it sticks after a restart.
        app(device, server)
        assertEquals(Language.EN, device.shown)
        assertEquals(listOf(Language.EN), server.requests)
    }

    @Test
    fun `a language chosen in Android's settings while the app was not running is saved at the next start`() = runTest {
        val device = Device()
        val server = Server(Language.RU)
        app(device, server)

        device.app = "en-US"
        val restarted = app(device, server)
        assertEquals(Language.EN, device.shown)
        server.answer()
        runCurrent()

        assertEverywhere(Language.EN, restarted)
    }

    @Test
    fun `'System default' in Android's settings chooses the device's language`() = runTest {
        val device = Device("en-US")
        val server = Server(Language.RU)
        val app = app(device, server)

        device.app = null
        app.follow()
        server.answer()
        runCurrent()

        assertEverywhere(Language.EN, app)
        assertEquals("en", device.app)
    }

    @Test
    fun `a language from Android's settings that cannot be saved is rolled back like any other`() = runTest {
        val device = Device()
        val server = Server(Language.RU)
        val app = app(device, server)

        device.app = "en-US"
        app.follow()
        server.fail()
        runCurrent()

        assertEquals(1, app.failures)
        assertEverywhere(Language.RU, app)
        // And it is not taken for a new choice the next time the app looks.
        app.follow()
        assertEquals(listOf(Language.EN), server.requests)
    }

    // --- another device (L66) ---

    @Test
    fun `a language saved on another device is shown when the Stylist is loaded again`() = runTest {
        val device = Device()
        val server = Server(Language.EN)
        val app = app(device, server)
        assertEquals(Language.EN, device.shown)

        server.language = Language.RU
        app.loadStylist()

        assertEverywhere(Language.RU, app)
        assertEquals("the other device's choice is not saved again", emptyList<Language>(), server.requests)
    }

    @Test
    fun `on a device the app has not chosen a language on yet, the saved language wins`() = runTest {
        // First sign-in on an English device, or the first start after an update: nothing says the per-app language was chosen in Android's settings.
        val fresh = Device("en-US")
        val updated = Device("en-US").apply { app = "en" }
        val server = Server(Language.RU)

        app(fresh, server)
        app(updated, server)

        assertEquals(Language.RU, fresh.shown)
        assertEquals(Language.RU, updated.shown)
        assertEquals(emptyList<Language>(), server.requests)
    }

    @Test
    fun `a locale that is shown in English without being English is not kept as the Stylist's English`() = runTest {
        // German shows the English strings, yet as a choice it stands for Russian. Left in place under a
        // Stylist whose language is English, it would be taken for Russian chosen in Android's settings.
        val device = Device("en-US").apply { app = "de-DE" }
        val server = Server(Language.EN)
        val app = app(device, server)

        assertEquals("en", device.app)
        app.follow()
        assertEquals(emptyList<Language>(), server.requests)
        assertEverywhere(Language.EN, app)
    }

    // --- requests about the settings ---

    @Test
    fun `saves of settings go out one at a time, each built after the one before it has ended`() = runTest {
        val requests = SettingsRequests()
        val first = CompletableDeferred<Unit>()
        var saved = "ru, UTC"
        val sent = mutableListOf<String>()

        launch {
            requests.save {
                sent += "en, UTC"
                first.await()
                saved = "en, UTC"
            }
        }
        // The Settings form is saved while the language switch is on its way. It must not put Russian back.
        launch { requests.save { sent += saved.substringBefore(",") + ", Asia/Tokyo" } }
        runCurrent()
        assertEquals(listOf("en, UTC"), sent)

        first.complete(Unit)
        runCurrent()
        assertEquals(listOf("en, UTC", "en, Asia/Tokyo"), sent)
    }

    /**
     * Stands in for the app's main thread and its immediate dispatcher: what comes from elsewhere,
     * such as a reply from the network, is queued, and a coroutine that is let go on while the
     * thread is at work goes on at once, before whoever let it.
     */
    private class MainThread : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        private var atWork = false

        override fun isDispatchNeeded(context: CoroutineContext) = !atWork

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue += block
        }

        fun work() {
            atWork = true
            try {
                while (queue.isNotEmpty()) {
                    queue.removeFirst().run()
                }
            } finally {
                atWork = false
            }
        }
    }

    @Test
    fun `a save that waited is built from what the one before it put in place`() {
        val main = MainThread()
        val scope = CoroutineScope(main)
        val requests = SettingsRequests()
        val formReply = CompletableDeferred<Unit>()
        var saved = "ru, UTC"
        val sent = mutableListOf<String>()

        // 'Save' on the Settings form with another timezone, whose reply is put in place inside the save...
        scope.launch {
            requests.save {
                sent += "ru, Asia/Tokyo"
                formReply.await()
                saved = "ru, Asia/Tokyo"
            }
        }
        // ...and the other language tapped while that request is on its way. It must not put the old timezone back.
        scope.launch { requests.save { sent += "en, " + saved.substringAfter(", ") } }
        main.work()
        assertEquals(listOf("ru, Asia/Tokyo"), sent)

        // The language save has its turn the moment the form's save lets go, before the form's caller runs again.
        formReply.complete(Unit)
        main.work()
        assertEquals(listOf("ru, Asia/Tokyo", "en, Asia/Tokyo"), sent)
    }

    @Test
    fun `settings fetched while a save ran are dropped`() = runTest {
        val requests = SettingsRequests()
        val reply = CompletableDeferred<String>()
        var fetched: String? = "not yet"

        launch { fetched = requests.fetch { reply.await() } }
        runCurrent()
        // The fetch was answered before this save reached the server, so what it brings is older.
        requests.save { }
        reply.complete("ru")
        runCurrent()
        assertNull(fetched)

        // Nor is anything fetched while a save is running: its reply will be the newer one.
        val saving = CompletableDeferred<Unit>()
        var asked = false
        launch { requests.save { saving.await() } }
        runCurrent()
        assertNull(requests.fetch { "ru".also { asked = true } })
        assertFalse(asked)

        saving.complete(Unit)
        runCurrent()
        assertEquals("en", requests.fetch { "en" })
    }

    // --- the settings and the lists ---

    @Test
    fun `the lists do not wait for the settings`() = runTest {
        val settingsReply = CompletableDeferred<Unit>()
        val listsReply = CompletableDeferred<Unit>()
        val asked = mutableListOf<String>()
        val answered = mutableListOf<String>()

        // 'Try again' under a list, on a connection where nothing is answered for a while.
        val loading = launch {
            withSettings(
                setting = { "UTC" },
                settings = {
                    asked += "settings"
                    settingsReply.await()
                },
                lists = {
                    asked += "lists"
                    listsReply.await()
                    answered += "lists"
                },
                listsOfSetting = { asked += "lists of the timezone" },
            )
        }
        runCurrent()

        // Both are on their way from the tap on, so the list shows its spinner at once.
        assertEquals(listOf("settings", "lists"), asked)

        // And the list is there as soon as it is answered, whatever the settings do.
        listsReply.complete(Unit)
        runCurrent()
        assertEquals(listOf("lists"), answered)
        assertFalse(loading.isCompleted)

        settingsReply.complete(Unit)
        runCurrent()
        assertTrue(loading.isCompleted)
        // The timezone is the one the lists were asked with: nothing is loaded twice.
        assertEquals(listOf("settings", "lists"), asked)
    }

    @Test
    fun `a timezone saved on another device loads the lists of its days again, after their first load has ended`() = runTest {
        var timezone = "Europe/Moscow"
        val settingsReply = CompletableDeferred<Unit>()
        val listsReply = CompletableDeferred<Unit>()
        val asked = mutableListOf<String>()

        launch {
            withSettings(
                setting = { timezone },
                settings = {
                    settingsReply.await()
                    timezone = "Asia/Tokyo"
                },
                lists = {
                    asked += "lists, days of $timezone"
                    listsReply.await()
                },
                listsOfSetting = { asked += "Appointments, days of $timezone" },
            )
        }
        runCurrent()
        settingsReply.complete(Unit)
        runCurrent()

        // The lists asked for with the old timezone are still on their way. Their answer must not be the one that arrives last.
        assertEquals(listOf("lists, days of Europe/Moscow"), asked)

        listsReply.complete(Unit)
        runCurrent()
        assertEquals(listOf("lists, days of Europe/Moscow", "Appointments, days of Asia/Tokyo"), asked)
    }

    @Test
    fun `the lists are loaded also when the settings could not be, and the failure is still thrown`() = runTest {
        val loaded = mutableListOf<String>()

        try {
            withSettings(
                setting = { "UTC" },
                settings = { throw IOException("settings") },
                lists = { loaded += "lists" },
                listsOfSetting = { loaded += "lists of the timezone" },
            )
            fail("the failure was swallowed")
        } catch (error: IOException) {
            assertEquals("settings", error.message)
        }

        assertEquals(listOf("lists"), loaded)
    }
}
