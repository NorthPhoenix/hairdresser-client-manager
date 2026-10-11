package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.ApiException
import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentParticipant
import com.northphoenix.hairdresserclientmanager.data.AppointmentPhoto
import com.northphoenix.hairdresserclientmanager.data.AppointmentService
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.data.ClientHistoryEntry
import com.northphoenix.hairdresserclientmanager.data.ClientProfile
import com.northphoenix.hairdresserclientmanager.data.ColorFormula
import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.data.LocationType
import com.northphoenix.hairdresserclientmanager.data.PhotoCategory
import com.northphoenix.hairdresserclientmanager.data.ProfileShare
import com.northphoenix.hairdresserclientmanager.data.ServiceMenuItem
import com.northphoenix.hairdresserclientmanager.data.ShareChoice
import com.northphoenix.hairdresserclientmanager.state.ActionRunner
import com.northphoenix.hairdresserclientmanager.state.AppointmentKey
import com.northphoenix.hairdresserclientmanager.state.AppointmentReads
import com.northphoenix.hairdresserclientmanager.state.Changes
import com.northphoenix.hairdresserclientmanager.state.ClientKey
import com.northphoenix.hairdresserclientmanager.state.ClientReads
import com.northphoenix.hairdresserclientmanager.state.DayKey
import com.northphoenix.hairdresserclientmanager.state.LatestWins
import com.northphoenix.hairdresserclientmanager.state.LoadState
import com.northphoenix.hairdresserclientmanager.state.ServiceMenuReads
import com.northphoenix.hairdresserclientmanager.state.StylistKey
import com.northphoenix.hairdresserclientmanager.state.plus
import com.northphoenix.hairdresserclientmanager.state.saveLatest
import com.northphoenix.hairdresserclientmanager.state.with
import com.northphoenix.hairdresserclientmanager.state.withoutColorFormula
import com.northphoenix.hairdresserclientmanager.state.withoutPhoto
import com.northphoenix.hairdresserclientmanager.state.withoutService
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The changes the Stylist does not wait for (optimistic changes): the screen shows the new value,
 * or the item gone, at once, nothing is disabled while it is saved, and when it cannot be saved
 * the screen is back on what the server has and the failure is reported once.
 *
 * [App] wires the runner and the reads as the view model does, each change as the view model makes it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OptimisticTest {
    private val monday = DayKey(LocalDate.of(2026, 10, 12), ZoneId.of("America/Chicago"))
    private val stylist = StylistKey("s1")

    private val cut = AppointmentService(id = "cut", clientId = "anna", name = "Cut", priceCents = 4_000)
    private val color = AppointmentService(
        id = "color",
        clientId = "anna",
        name = "Color",
        priceCents = 9_000,
        colorFormulas = listOf(ColorFormula("f1", "7N + 20 vol"), ColorFormula("f2", "9V gloss")),
    )
    private val blowout = AppointmentService(id = "blowout", clientId = "bella", name = "Blowout", priceCents = 2_500)

    /** Anna's and Bella's Appointment, as the server has it when a test starts. */
    private val booked = Appointment(
        id = "a1",
        primaryClientId = "anna",
        primaryClientName = "Anna Avery",
        participants = listOf(
            AppointmentParticipant(clientId = "anna", name = "Anna Avery", isPrimary = true, subtotalCents = 13_000),
            AppointmentParticipant(clientId = "bella", name = "Bella Brook", subtotalCents = 2_500),
        ),
        services = listOf(cut, color, blowout),
        photos = listOf(
            AppointmentPhoto(id = "p1", clientId = "anna", category = PhotoCategory.BEFORE),
            AppointmentPhoto(id = "p2", clientId = "anna", category = PhotoCategory.AFTER),
        ),
        serviceTotalCents = 15_500,
        finalTotalCents = 15_500,
        startsAt = "2026-10-12T15:00:00.000Z",
        status = AppointmentStatus.SCHEDULED,
        locationType = LocationType.IN_SALON,
    )

    private val share = ProfileShare(id = "ps1", token = "t1", language = Language.EN, createdAt = "2026-10-01T00:00:00.000Z")
    private val anna = ClientProfile(id = "anna", name = "Anna Avery", language = Language.EN, activeProfileShare = share)
    private val menu = listOf(
        ServiceMenuItem("m1", "Cut", 4_000),
        ServiceMenuItem("m2", "Color", 9_000),
        ServiceMenuItem("m3", "Blowout", 2_500),
    )

    private fun offline() = ApiException("Network request failed.", code = "NETWORK", isNetwork = true)

    /**
     * Answers reads with what it has when they arrive. A change waits until the test answers or
     * refuses it, and is made when it is answered.
     */
    private class Server(var appointment: Appointment?) {
        var clients = emptyList<ClientProfile>()
        var menu = emptyList<ServiceMenuItem>()
        var history = emptyList<ClientHistoryEntry>()

        /** The changes that arrived, in their order. */
        val changes = mutableListOf<String>()
        private val unanswered = ArrayDeque<CompletableDeferred<Unit>>()
        private var held: CompletableDeferred<Unit>? = null

        /** Reads from now on get no answer until [releaseReads]. */
        fun holdReads() {
            held = CompletableDeferred()
        }

        fun releaseReads() {
            held?.complete(Unit)
            held = null
        }

        suspend fun today(key: DayKey): List<Appointment> = read { listOfNotNull(appointment) }

        suspend fun find(id: String): Appointment? = read { appointment?.takeIf { it.id == id } }

        suspend fun clients(key: StylistKey): List<ClientProfile> = read { clients }

        suspend fun menu(key: StylistKey): List<ServiceMenuItem> = read { menu }

        suspend fun history(key: ClientKey): List<ClientHistoryEntry> = read { history }

        private suspend fun <T> read(answer: () -> T): T {
            val gate = held
            val value = answer()

            gate?.await()
            return value
        }

        suspend fun change(name: String, make: () -> Unit) {
            changes += name
            CompletableDeferred<Unit>().also(unanswered::addLast).await()
            make()
        }

        suspend fun changeAppointment(name: String, edit: (Appointment) -> Appointment) = change(name) { appointment = appointment?.let(edit) }

        /** The change that has waited longest is saved and answered. */
        fun answer() {
            unanswered.removeFirst().complete(Unit)
        }

        /** The change that has waited longest fails: it is not made. */
        fun refuse(error: ApiException) {
            unanswered.removeFirst().completeExceptionally(error)
        }
    }

    private inner class App(scope: CoroutineScope, val server: Server) {
        val failures = mutableListOf<ApiException>()
        var reloads = 0
        var reloadsOfEverything = 0

        private val marks = Changes()
        val reads = AppointmentReads(scope, marks, server::today, server::today, server::find, fetchSources = { emptyList() })
        val clientReads = ClientReads(scope, marks, server::clients, server::history)
        val menuReads = ServiceMenuReads(scope, marks, server::menu)
        val runner = ActionRunner(scope, { failures += it }, reloadAll = { reloadsOfEverything++ })

        private val statuses = LatestWins<String, AppointmentStatus>()
        private val notes = LatestWins<String, String>()
        private val shareChoices = LatestWins<String, Boolean>()

        val appointment: Appointment? get() = reads["a1"]

        suspend fun reload() {
            reloads++
            reads.today.refresh(monday)
            reads.one.refresh(AppointmentKey("a1"))
        }

        fun setStatus(status: AppointmentStatus) = runner.saveLatest(
            statuses,
            "status:a1",
            status,
            saved = appointment?.status,
            ahead = { next -> reads.ahead("status:a1", "a1") { it.copy(status = next) } + clientReads.statusAhead("status:a1", "a1", next) },
            reload = ::reload,
        ) { next -> server.changeAppointment("status $next") { it.copy(status = next) } }

        fun saveNote(note: String) = runner.saveLatest(
            notes,
            "note:a1",
            note,
            saved = appointment?.note,
            ahead = { next -> reads.ahead("note:a1", "a1") { it.copy(note = next) } },
            reload = ::reload,
        ) { next -> server.changeAppointment("note $next") { it.copy(note = next) } }

        fun deleteService(id: String) = runner.run(
            key = "deleteService:$id",
            change = { server.changeAppointment("delete $id") { it.withoutService(id) } },
            reload = ::reload,
            ahead = reads.ahead("deleteService:$id", "a1") { it.withoutService(id) },
        )

        fun deleteMenuItem(id: String) = runner.run(
            key = "deleteServiceMenuItem:$id",
            change = { server.change("delete $id") { server.menu = server.menu.filterNot { it.id == id } } },
            reload = { menuReads.menu.refresh(stylist) },
            ahead = menuReads.deletedAhead("deleteServiceMenuItem:$id", id),
        )

        fun setShareChoice(choice: ShareChoice, included: Boolean) {
            val shown = clientReads.list.shown(stylist)?.data?.single()?.activeProfileShare ?: return
            val target = "shareChoice:anna:${choice.name}"

            runner.saveLatest(
                shareChoices,
                target,
                included,
                saved = choice in shown.included,
                ahead = { next -> clientReads.shareChoiceAhead(target, "anna", choice, next) },
                reload = { clientReads.list.refresh(stylist) },
            ) { next ->
                server.change("${choice.name} $next") {
                    server.clients = server.clients.map { it.copy(activeProfileShare = it.activeProfileShare?.with(choice, next)) }
                }
                // The reply states the whole Profile Share as the server has it now.
                clientReads.profileShare("anna", server.clients.single().activeProfileShare)
            }
        }
    }

    private fun TestScope.app(server: Server = Server(booked)) = App(backgroundScope, server)

    /** What a screen that reads [flow] is shown, value by value. It starts reading at once, as a screen does. */
    private fun <T> TestScope.shownBy(flow: Flow<T>): List<T> {
        val shown = mutableListOf<T>()

        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { flow.collect { shown += it } }
        return shown
    }

    // --- a value that can be tapped again: the status chips ---

    @Test
    fun `a status chip is the status at once, on the Appointment's screen and on its cards, and nothing waits for it`() = runTest {
        val app = app()
        val screen = shownBy(app.reads.watch("a1"))
        val today = shownBy(app.reads.today.watch(monday))
        val calendar = shownBy(app.reads.days.watch(monday))
        runCurrent()
        val before = screen.size

        app.setStatus(AppointmentStatus.COMPLETED)

        // With the tap, before the server has answered or anything else has run.
        assertEquals(AppointmentStatus.COMPLETED, app.appointment?.status)
        assertEquals(AppointmentStatus.COMPLETED, app.reads.today.shown(monday)?.data?.single()?.status)
        assertEquals(AppointmentStatus.COMPLETED, app.reads.days.shown(monday)?.data?.single()?.status)
        assertEquals(listOf("status COMPLETED"), app.server.changes)
        assertEquals(AppointmentStatus.SCHEDULED, app.server.appointment?.status)
        // Nothing on the screen is disabled by it. The progress line is the only sign.
        assertFalse(app.runner.busy.value)
        assertTrue(app.runner.saving.value)

        runCurrent()
        assertEquals(AppointmentStatus.COMPLETED, screen.last().data?.status)
        assertEquals(AppointmentStatus.COMPLETED, today.last().data?.single()?.status)
        assertEquals(AppointmentStatus.COMPLETED, calendar.last().data?.single()?.status)
        // What the server is known to have is still there for whoever needs it.
        assertEquals(AppointmentStatus.SCHEDULED, screen.last().saved?.status)

        app.server.answer()
        runCurrent()

        // Saved and reloaded. The old status was never shown again on the way.
        assertEquals(listOf(AppointmentStatus.COMPLETED), screen.drop(before).map { it.data?.status }.distinct())
        assertEquals(AppointmentStatus.COMPLETED, screen.last().saved?.status)
        assertEquals(1, app.reloads)
        assertFalse(app.runner.saving.value)
        assertTrue(app.failures.isEmpty())
    }

    @Test
    fun `a status that cannot be saved goes back to the saved one, and the failure is reported once`() = runTest {
        val app = app()
        val screen = shownBy(app.reads.watch("a1"))
        val today = shownBy(app.reads.today.watch(monday))
        runCurrent()

        app.setStatus(AppointmentStatus.NO_SHOW)
        runCurrent()
        assertEquals(AppointmentStatus.NO_SHOW, screen.last().data?.status)

        app.server.refuse(offline())
        runCurrent()

        assertEquals(AppointmentStatus.SCHEDULED, screen.last().data?.status)
        assertEquals(AppointmentStatus.SCHEDULED, today.last().data?.single()?.status)
        assertEquals(listOf("NETWORK"), app.failures.map { it.code })
        // Nothing was saved, so there is nothing to reload.
        assertEquals(0, app.reloads)
        assertFalse(app.runner.saving.value)

        // The chip can be tapped again, and is sent again.
        app.setStatus(AppointmentStatus.NO_SHOW)
        assertEquals(AppointmentStatus.NO_SHOW, app.appointment?.status)
        assertEquals(listOf("status NO_SHOW", "status NO_SHOW"), app.server.changes)
    }

    @Test
    fun `of two taps of which the second cannot be saved, the status of the first one stays`() = runTest {
        val app = app()
        val screen = shownBy(app.reads.watch("a1"))
        runCurrent()
        val before = screen.size

        app.setStatus(AppointmentStatus.COMPLETED)
        app.setStatus(AppointmentStatus.CANCELED)
        assertEquals(AppointmentStatus.CANCELED, app.appointment?.status)
        // One request at a time.
        assertEquals(listOf("status COMPLETED"), app.server.changes)

        // The first is saved. The screen stays on the last tap, which is sent now.
        app.server.answer()
        runCurrent()
        assertEquals(listOf("status COMPLETED", "status CANCELED"), app.server.changes)
        assertEquals(AppointmentStatus.CANCELED, screen.last().data?.status)
        assertEquals(AppointmentStatus.COMPLETED, screen.last().saved?.status)

        app.server.refuse(offline())
        runCurrent()

        // Not the status from before the first tap: the one the server has.
        assertEquals(AppointmentStatus.COMPLETED, screen.last().data?.status)
        assertEquals(AppointmentStatus.COMPLETED, app.server.appointment?.status)
        // The screen went from the last tap straight to it, and never showed the status from before the taps.
        assertEquals(listOf(AppointmentStatus.CANCELED, AppointmentStatus.COMPLETED), screen.drop(before).map { it.data?.status }.distinct())
        assertEquals(listOf("NETWORK"), app.failures.map { it.code })
        // What the first tap saved is loaded again.
        assertEquals(1, app.reloads)
    }

    @Test
    fun `every tap on a status chip shows at once, and the last one is what is saved`() = runTest {
        val app = app()
        val screen = shownBy(app.reads.watch("a1"))
        runCurrent()

        // Through the chips and back to the one that is saved, while the first is still on its way.
        for (status in listOf(AppointmentStatus.COMPLETED, AppointmentStatus.CANCELED, AppointmentStatus.NO_SHOW, AppointmentStatus.SCHEDULED)) {
            app.setStatus(status)
            assertEquals(status, app.appointment?.status)
        }
        assertEquals(listOf("status COMPLETED"), app.server.changes)

        app.server.answer()
        runCurrent()
        app.server.answer()
        runCurrent()

        // The taps in between were never sent.
        assertEquals(listOf("status COMPLETED", "status SCHEDULED"), app.server.changes)
        assertEquals(AppointmentStatus.SCHEDULED, app.server.appointment?.status)
        assertEquals(AppointmentStatus.SCHEDULED, screen.last().data?.status)
        assertFalse(app.runner.saving.value)
        assertTrue(app.failures.isEmpty())

        // A tap on the status that is saved, with nothing on its way, sends nothing.
        app.setStatus(AppointmentStatus.SCHEDULED)
        assertEquals(2, app.server.changes.size)
        assertFalse(app.runner.saving.value)
    }

    @Test
    fun `a refresh that arrives while a status is being saved does not bring the old status back`() = runTest {
        val app = app()
        val screen = shownBy(app.reads.watch("a1"))
        val today = shownBy(app.reads.today.watch(monday))
        runCurrent()

        // A pull to refresh is on its way when the chip is tapped...
        app.server.holdReads()
        val pulled = launch { app.reads.today.refresh(monday) }
        runCurrent()
        val before = screen.size
        val cardsBefore = today.size
        app.setStatus(AppointmentStatus.COMPLETED)
        runCurrent()

        // ...and answers with the status the server still has. So does the screen coming into view again.
        app.server.releaseReads()
        app.reads.one.refresh(AppointmentKey("a1"))
        runCurrent()
        assertTrue(pulled.isCompleted)
        assertEquals(AppointmentStatus.SCHEDULED, app.server.appointment?.status)
        assertEquals(AppointmentStatus.COMPLETED, screen.last().data?.status)
        assertEquals(AppointmentStatus.COMPLETED, today.last().data?.single()?.status)

        // A refresh that is sent before the save is answered and arrives after it is not shown either.
        app.server.holdReads()
        val late = launch { app.reads.today.refresh(monday) }
        runCurrent()
        app.server.answer()
        runCurrent()
        app.server.releaseReads()
        runCurrent()

        assertTrue(late.isCompleted)
        assertEquals(listOf(AppointmentStatus.COMPLETED), screen.drop(before).map { it.data?.status }.distinct())
        assertEquals(listOf(AppointmentStatus.COMPLETED), today.drop(cardsBefore).map { it.data?.single()?.status }.distinct())
        assertTrue(app.failures.isEmpty())
    }

    @Test
    fun `a screen opened while a change is being saved shows the change too`() = runTest {
        val app = app()
        shownBy(app.reads.today.watch(monday))
        runCurrent()

        // Tapped, and the Stylist goes on: the Appointment's own screen is not open.
        app.setStatus(AppointmentStatus.COMPLETED)
        runCurrent()

        // Opened now, it shows the card's copy first and then its own answer, each with the status that was tapped.
        val screen = shownBy(app.reads.watch("a1"))
        runCurrent()
        assertEquals(AppointmentStatus.SCHEDULED, app.server.appointment?.status)
        assertTrue(screen.isNotEmpty())
        assertTrue(screen.all { it.data?.status == AppointmentStatus.COMPLETED })
        assertEquals(AppointmentStatus.SCHEDULED, screen.last().saved?.status)

        // So does the first frame of a screen that reads what the cache has.
        assertEquals(AppointmentStatus.COMPLETED, app.reads.one.now(AppointmentKey("a1")).data?.status)
    }

    @Test
    fun `what the cache has of an Appointment that is on no screen is shown with a change that is being saved`() = runTest {
        val app = app()

        // Its screen was open, and is closed again: the answer is in the cache.
        val open = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { app.reads.watch("a1").collect { } }
        runCurrent()
        open.cancel()
        runCurrent()
        assertNull(app.appointment)

        // A status is saved from somewhere else, and the screen is opened again while it is on its way.
        app.runner.run(
            key = "status",
            change = { app.server.changeAppointment("status COMPLETED") { it.copy(status = AppointmentStatus.COMPLETED) } },
            ahead = app.reads.ahead("status", "a1") { it.copy(status = AppointmentStatus.COMPLETED) },
        )

        val first = app.reads.one.now(AppointmentKey("a1"))
        assertEquals(AppointmentStatus.COMPLETED, first.data?.status)
        assertEquals(AppointmentStatus.SCHEDULED, first.saved?.status)

        val screen = shownBy(app.reads.watch("a1"))
        runCurrent()
        assertTrue(screen.all { it.data?.status == AppointmentStatus.COMPLETED })
    }

    @Test
    fun `an Appointment that was just created shows a change before any read of it has answered`() = runTest {
        val app = app()

        app.server.holdReads()
        app.reads.created(booked)
        val screen = shownBy(app.reads.watch("a1"))
        runCurrent()
        assertEquals(AppointmentStatus.SCHEDULED, screen.last().data?.status)

        app.setStatus(AppointmentStatus.CANCELED)
        runCurrent()

        assertEquals(AppointmentStatus.CANCELED, screen.last().data?.status)
        assertEquals(AppointmentStatus.SCHEDULED, screen.last().saved?.status)
    }

    @Test
    fun `a status chip shows in a Client's history that is on screen`() = runTest {
        val app = app()
        app.server.history = listOf(ClientHistoryEntry(id = "a1", primaryClientName = "Anna Avery", startsAt = booked.startsAt, status = AppointmentStatus.SCHEDULED))
        val history = shownBy(app.clientReads.history.watch(ClientKey("anna")))
        runCurrent()

        app.setStatus(AppointmentStatus.COMPLETED)
        runCurrent()
        assertEquals(AppointmentStatus.COMPLETED, history.last().data?.single()?.status)

        app.server.refuse(offline())
        runCurrent()
        assertEquals(AppointmentStatus.SCHEDULED, history.last().data?.single()?.status)
    }

    @Test
    fun `a status tapped on an Appointment that is gone goes back, is reported, and everything is loaded again`() = runTest {
        val app = app()
        val screen = shownBy(app.reads.watch("a1"))
        runCurrent()

        // Deleted in another session.
        app.server.appointment = null
        app.setStatus(AppointmentStatus.COMPLETED)
        app.server.refuse(ApiException("Appointment not found.", code = "NOT_FOUND"))
        runCurrent()

        assertEquals(listOf("NOT_FOUND"), app.failures.map { it.code })
        assertEquals(1, app.reloadsOfEverything)
        // Its own reload has found out that it is gone.
        assertEquals(1, app.reloads)
        assertEquals(LoadState.Missing, screen.last().state)
        assertFalse(app.runner.saving.value)
    }

    // --- text the Stylist has typed: the note ---

    @Test
    fun `a note that is being saved is the Appointment's, and its screen still knows what the server has`() = runTest {
        val app = app()
        val screen = shownBy(app.reads.watch("a1"))
        runCurrent()

        app.saveNote("Bring swatches")
        runCurrent()

        // The Appointment has the note, so it is no unsaved text. The field's text is started from
        // what the server has, which has not changed: the text stays as it was typed.
        assertEquals("Bring swatches", screen.last().data?.note)
        assertEquals("", screen.last().saved?.note)
        assertFalse(app.runner.busy.value)

        app.server.refuse(offline())
        runCurrent()

        // The Appointment is back without the note. What the server has did not change, so the field is not started again.
        assertEquals("", screen.last().data?.note)
        assertEquals("", screen.last().saved?.note)
        assertEquals(listOf("NETWORK"), app.failures.map { it.code })

        app.saveNote("Bring swatches")
        app.server.answer()
        runCurrent()
        assertEquals("Bring swatches", screen.last().data?.note)
        assertEquals("Bring swatches", screen.last().saved?.note)
    }

    // --- a delete the Stylist has confirmed ---

    @Test
    fun `a confirmed delete hides the Service at once, with its price, and nothing waits for it`() = runTest {
        val app = app()
        val screen = shownBy(app.reads.watch("a1"))
        val today = shownBy(app.reads.today.watch(monday))
        runCurrent()
        val before = screen.size

        app.deleteService("color")

        assertEquals(listOf(cut, blowout), app.appointment?.services)
        assertEquals(6_500L, app.appointment?.serviceTotalCents)
        assertEquals(6_500L, app.appointment?.finalTotalCents)
        assertEquals(listOf(4_000L, 2_500L), app.appointment?.participants?.map { it.subtotalCents })
        assertEquals(6_500L, app.reads.today.shown(monday)?.data?.single()?.finalTotalCents)
        assertEquals(3, app.server.appointment?.services?.size)
        assertFalse(app.runner.busy.value)
        assertTrue(app.runner.saving.value)

        app.server.answer()
        runCurrent()

        // Deleted and reloaded, and never back on the screen in between.
        assertTrue(screen.drop(before).all { it.data?.services == listOf(cut, blowout) })
        assertEquals(listOf(cut, blowout), screen.last().saved?.services)
        assertEquals(6_500L, today.last().data?.single()?.finalTotalCents)
        assertEquals(1, app.reloads)
        assertTrue(app.failures.isEmpty())
    }

    @Test
    fun `a delete that cannot be saved puts the Service back in its place, and is reported once`() = runTest {
        val app = app()
        val screen = shownBy(app.reads.watch("a1"))
        runCurrent()

        app.deleteService("color")
        runCurrent()
        assertEquals(listOf(cut, blowout), screen.last().data?.services)

        app.server.refuse(offline())
        runCurrent()

        // Between the two it stood between, with the totals as they were.
        assertEquals(booked, screen.last().data)
        assertEquals(listOf(cut, color, blowout), screen.last().data?.services)
        assertEquals(listOf("NETWORK"), app.failures.map { it.code })
        assertEquals(0, app.reloads)
        assertFalse(app.runner.saving.value)
    }

    @Test
    fun `a delete whose reply never came is reported as unknown, and the reload shows that it was saved`() = runTest {
        val app = app()
        val screen = shownBy(app.reads.watch("a1"))
        runCurrent()

        app.deleteService("color")
        // The server deleted it. The reply was lost on the way.
        app.server.appointment = booked.withoutService("color")
        app.server.refuse(ApiException("No reply from the server.", code = ApiException.NO_REPLY))
        runCurrent()

        assertTrue(app.failures.single().outcomeUnknown)
        assertEquals(1, app.reloads)
        assertEquals(listOf(cut, blowout), screen.last().data?.services)
        assertEquals(listOf(cut, blowout), screen.last().saved?.services)
    }

    @Test
    fun `a delete that is tapped again while it is being saved is sent once, and hides nothing that no request stands for`() = runTest {
        val app = app()
        val screen = shownBy(app.reads.watch("a1"))
        runCurrent()

        repeat(2) { app.deleteService("color") }
        assertEquals(listOf("delete color"), app.server.changes)

        // No reply: the Service is back while the reload finds out what became of it.
        app.server.holdReads()
        app.server.refuse(ApiException("No reply from the server.", code = ApiException.NO_REPLY))
        runCurrent()
        assertEquals(listOf(cut, color, blowout), screen.last().data?.services)

        // Still the same action while it reloads: a tap does nothing, and so shows nothing.
        app.deleteService("color")
        runCurrent()
        assertEquals(listOf("delete color"), app.server.changes)
        assertEquals(listOf(cut, color, blowout), screen.last().data?.services)

        app.server.releaseReads()
        runCurrent()
        app.deleteService("color")
        assertEquals(listOf("delete color", "delete color"), app.server.changes)
        assertEquals(listOf(cut, blowout), app.appointment?.services)
    }

    @Test
    fun `two deletes made in a row both show at once and are sent in the order they were made`() = runTest {
        val app = app()
        val screen = shownBy(app.reads.watch("a1"))
        runCurrent()

        app.deleteService("cut")
        app.deleteService("blowout")

        assertEquals(listOf(color), app.appointment?.services)
        assertEquals(9_000L, app.appointment?.finalTotalCents)
        assertEquals(listOf("delete cut"), app.server.changes)

        // The first is saved: the second is still only shown, and now sent. The reload after the first has not answered yet.
        app.server.holdReads()
        app.server.answer()
        runCurrent()
        assertEquals(listOf("delete cut", "delete blowout"), app.server.changes)
        assertEquals(listOf(color), screen.last().data?.services)
        assertEquals(listOf(color, blowout), screen.last().saved?.services)

        // The second fails: only that one comes back, also before anything is loaded again.
        app.server.refuse(offline())
        runCurrent()
        assertEquals(listOf(color, blowout), screen.last().data?.services)
        assertEquals(11_500L, screen.last().data?.finalTotalCents)
        assertEquals(listOf("NETWORK"), app.failures.map { it.code })

        app.server.releaseReads()
        runCurrent()
        assertEquals(listOf(color, blowout), screen.last().data?.services)
        assertFalse(app.runner.saving.value)
    }

    @Test
    fun `a deleted Service Menu Item leaves the menu at once, and is back in its place when the delete fails`() = runTest {
        val app = app(Server(booked).apply { menu = this@OptimisticTest.menu })
        val shown = shownBy(app.menuReads.menu.watch(stylist))
        runCurrent()

        app.deleteMenuItem("m2")
        assertEquals(listOf("m1", "m3"), app.menuReads.menu.shown(stylist)?.data?.map { it.id })
        assertFalse(app.runner.busy.value)

        app.server.refuse(offline())
        runCurrent()
        assertEquals(menu, shown.last().data)
        assertEquals(listOf("NETWORK"), app.failures.map { it.code })

        // Deleted for good the second time, and not back before the reload.
        val before = shown.size
        app.deleteMenuItem("m2")
        app.server.answer()
        runCurrent()
        assertTrue(shown.drop(before).all { read -> read.data?.map { it.id } == listOf("m1", "m3") })
        assertEquals(listOf("m1", "m3"), app.server.menu.map { it.id })
    }

    // --- the choices of a Profile Share ---

    @Test
    fun `a Profile Share choice moves with the tap, one request at a time, and goes back when it cannot be saved`() = runTest {
        val app = app(Server(booked).apply { clients = listOf(anna) })
        val shown = shownBy(app.clientReads.list.watch(stylist))
        runCurrent()
        fun included() = shown.last().data?.single()?.activeProfileShare?.included

        // Two switches, one after the other, before the first is answered.
        app.setShareChoice(ShareChoice.OTHER_PHOTOS, true)
        app.setShareChoice(ShareChoice.AFTER_PHOTOS, false)
        runCurrent()
        assertEquals(setOf(ShareChoice.COLOR_FORMULAS, ShareChoice.OTHER_PHOTOS), included())
        assertEquals(listOf("OTHER_PHOTOS true"), app.server.changes)
        assertFalse(app.runner.busy.value)

        // The reply to the first states the Profile Share without the second choice. The switch of the second stays where it was put.
        app.server.answer()
        runCurrent()
        assertEquals(listOf("OTHER_PHOTOS true", "AFTER_PHOTOS false"), app.server.changes)
        assertEquals(setOf(ShareChoice.COLOR_FORMULAS, ShareChoice.OTHER_PHOTOS), included())
        assertEquals(ShareChoice.entries.toSet(), shown.last().saved?.single()?.activeProfileShare?.included)

        app.server.answer()
        runCurrent()
        assertEquals(setOf(ShareChoice.COLOR_FORMULAS, ShareChoice.OTHER_PHOTOS), included())
        assertEquals(included(), app.server.clients.single().activeProfileShare?.included)

        // A choice that cannot be saved goes back, and the others stay.
        app.setShareChoice(ShareChoice.COLOR_FORMULAS, false)
        runCurrent()
        assertEquals(setOf(ShareChoice.OTHER_PHOTOS), included())
        app.server.refuse(offline())
        runCurrent()
        assertEquals(setOf(ShareChoice.COLOR_FORMULAS, ShareChoice.OTHER_PHOTOS), included())
        assertEquals(listOf("NETWORK"), app.failures.map { it.code })
        // The link is the same one throughout.
        assertEquals("t1", shown.last().data?.single()?.activeProfileShare?.token)
    }

    // --- sign-out ---

    @Test
    fun `what was shown ahead for a Stylist who has signed out is gone with the rest`() = runTest {
        val app = app()
        shownBy(app.reads.today.watch(monday))
        runCurrent()

        app.setStatus(AppointmentStatus.COMPLETED)
        app.reads.clear()
        runCurrent()

        assertEquals(AppointmentStatus.SCHEDULED, app.appointment?.status)

        // The answer that still arrives takes nothing away twice.
        app.server.refuse(offline())
        runCurrent()
        assertEquals(AppointmentStatus.SCHEDULED, app.appointment?.status)
    }

    // --- what a delete leaves ---

    @Test
    fun `an Appointment without one of its Services has the totals the server will state`() {
        val overridden = booked.copy(finalTotalCentsOverride = 12_000, finalTotalCents = 12_000)

        // The price leaves the Service total, the final total and the subtotal of the Client it was for.
        assertEquals(13_000L, booked.withoutService("blowout").serviceTotalCents)
        assertEquals(13_000L, booked.withoutService("blowout").finalTotalCents)
        assertEquals(listOf(13_000L, 0L), booked.withoutService("blowout").participants.map { it.subtotalCents })
        // A final total the Stylist has set stays what it is.
        assertEquals(6_500L, overridden.withoutService("color").serviceTotalCents)
        assertEquals(12_000L, overridden.withoutService("color").finalTotalCents)
        // A Service that is gone already changes nothing, so the same delete can be applied to what a reload has brought.
        assertEquals(booked, booked.withoutService("gone"))
        assertEquals(booked.withoutService("cut"), booked.withoutService("cut").withoutService("cut"))
    }

    @Test
    fun `a Color Formula and a photo leave the Appointment alone, and the rest stays in its order`() {
        assertEquals(listOf(ColorFormula("f2", "9V gloss")), booked.withoutColorFormula("f1").services.single { it.id == "color" }.colorFormulas)
        assertEquals(listOf(cut, blowout), booked.withoutColorFormula("f1").services.filterNot { it.id == "color" })
        assertEquals(listOf("p2"), booked.withoutPhoto("p1").photos.map { it.id })
        assertEquals(booked.services, booked.withoutPhoto("p1").services)
        assertNull(booked.withoutPhoto("p1").photos.firstOrNull { it.id == "p1" })
    }

    @Test
    fun `a Profile Share with one choice made the other way keeps its link and its other choices`() {
        val changed = share.with(ShareChoice.OTHER_PHOTOS, true).with(ShareChoice.COLOR_FORMULAS, false)

        assertEquals(setOf(ShareChoice.AFTER_PHOTOS, ShareChoice.OTHER_PHOTOS), changed.included)
        assertEquals(share.token, changed.token)
        assertEquals(share, changed.with(ShareChoice.OTHER_PHOTOS, false).with(ShareChoice.COLOR_FORMULAS, true))
    }
}
