package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.ApiException
import com.northphoenix.hairdresserclientmanager.data.HcmApi
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.data.LocationType
import com.northphoenix.hairdresserclientmanager.data.PhotoCategory
import com.northphoenix.hairdresserclientmanager.data.TrpcClient
import com.northphoenix.hairdresserclientmanager.data.UploadThingClient
import com.northphoenix.hairdresserclientmanager.i18n.messageRes
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.concurrent.TimeUnit

class ApiClientTest {
    private val server = MockWebServer()
    private val http = OkHttpClient()
    private lateinit var api: HcmApi

    @Before
    fun setUp() {
        server.start()
        api = HcmApi(TrpcClient(server.url("/api/trpc").toString(), http) { "token-1" })
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun enqueue(body: String, code: Int = 200) {
        server.enqueue(MockResponse.Builder().code(code).body(body).build())
    }

    @Test
    fun `queries send input in the url with the bearer token`() = runTest {
        enqueue("""{"result":{"data":{"id":"s1","language":"en","timezone":"UTC","salonAddress":"","onboardingCompletedAt":null}}}""")

        val stylist = api.bootstrap(Language.EN, "America/Chicago")
        val request = server.takeRequest()

        assertEquals("GET", request.method)
        assertEquals("/api/trpc/stylist.bootstrap", request.url.encodedPath)
        assertEquals("""{"deviceLanguage":"en","deviceTimezone":"America/Chicago"}""", request.url.queryParameter("input"))
        assertEquals("Bearer token-1", request.headers["Authorization"])
        assertFalse(stylist.onboardingComplete)
    }

    @Test
    fun `mutations post the input and omit optional fields`() = runTest {
        enqueue("""{"result":{"data":{}}}""")

        api.addService("a1", "c1", menuItemId = "m1", name = null, priceCents = null, note = " ")
        val request = server.takeRequest()

        assertEquals("POST", request.method)
        assertEquals("/api/trpc/appointment.addService", request.url.encodedPath)
        assertEquals("""{"appointmentId":"a1","clientId":"c1","menuItemId":"m1"}""", request.body?.utf8())
    }

    @Test
    fun `an Appointment whose totals are more than an Int holds is still read`() = runTest {
        // H7: two Services at the largest price the old app could save. Read as Int, this reply failed to decode and no list loaded.
        enqueue(
            """{"result":{"data":[{"id":"a1","primaryClientId":"c1","primaryClientName":"Typo Client","participants":[{"clientId":"c1","name":"Typo Client","isPrimary":true,"subtotalCents":4294967294}],"services":[{"id":"s1","clientId":"c1","name":"Max One","priceCents":2147483647},{"id":"s2","clientId":"c1","name":"Max Two","priceCents":2147483647}],"serviceTotalCents":4294967294,"finalTotalCents":4294967294,"finalTotalCentsOverride":null,"startsAt":"2026-10-09T15:00:00.000Z","status":"scheduled","locationType":"inSalon"}]}}""",
        )

        val appointment = api.homeAppointments(Instant.parse("2026-10-09T05:00:00Z"), Instant.parse("2026-10-10T05:00:00Z")).single()

        assertEquals(4294967294, appointment.serviceTotalCents)
        assertEquals(4294967294, appointment.finalTotalCents)
        assertEquals(4294967294, appointment.participants.single().subtotalCents)
        assertEquals(listOf(2147483647L, 2147483647L), appointment.services.map { it.priceCents })
    }

    @Test
    fun `an amount is sent as whole cents`() = runTest {
        enqueue("""{"result":{"data":{}}}""")

        api.updateAppointmentFinalTotal("a1", overrideCents = 999999999)

        assertEquals("""{"id":"a1","finalTotalCentsOverride":999999999}""", server.takeRequest().body?.utf8())
    }

    @Test
    fun `clearing the final total override sends an explicit null`() = runTest {
        enqueue("""{"result":{"data":{}}}""")

        api.updateAppointmentFinalTotal("a1", overrideCents = null)

        assertEquals("""{"id":"a1","finalTotalCentsOverride":null}""", server.takeRequest().body?.utf8())
    }

    // --- Edit Appointment and the overlap warning ---

    private val updated =
        """{"id":"a1","primaryClientId":"c1","primaryClientName":"Alice Home","participants":[],"services":[],"photos":[],"startsAt":"2026-10-21T20:00:00.000Z","endsAt":null,"status":"scheduled","locationType":"atHome","locationAddress":"9 Lamar Blvd, Austin","customLocationAddress":"","mapUrl":"https://www.google.com/maps/search/?api=1&query=9%20Lamar%20Blvd%2C%20Austin","conflicts":[{"id":"a2","primaryClientId":"c2","primaryClientName":"Bob Noaddress","participants":[{"clientId":"c2","name":"Bob Noaddress","isPrimary":true}],"startsAt":"2026-10-21T20:00:00.000Z","endsAt":null,"status":"scheduled","locationType":"inSalon"}]}"""

    @Test
    fun `an edit sends the new start with the end, an end that was cleared as null, and the place`() = runTest {
        enqueue("""{"result":{"data":$updated}}""")
        enqueue("""{"result":{"data":$updated}}""")

        api.updateAppointmentTimeAndPlace(
            "a1",
            startsAt = Instant.parse("2026-10-21T20:00:00Z"),
            endsAt = Instant.parse("2026-10-21T21:30:00Z"),
            locationType = LocationType.AT_HOME,
            customLocationAddress = "1 Lost Lane",
        )
        api.updateAppointmentTimeAndPlace("a1", startsAt = Instant.parse("2026-10-21T20:00:00Z"), endsAt = null, locationType = LocationType.IN_SALON, customLocationAddress = "")

        assertEquals(
            """{"id":"a1","startsAt":"2026-10-21T20:00:00Z","endsAt":"2026-10-21T21:30:00Z","locationType":"atHome","customLocationAddress":"1 Lost Lane"}""",
            server.takeRequest().body?.utf8(),
        )
        // In the salon there is no address to send: the server takes the salon's.
        assertEquals("""{"id":"a1","startsAt":"2026-10-21T20:00:00Z","endsAt":null,"locationType":"inSalon"}""", server.takeRequest().body?.utf8())
    }

    @Test
    fun `an edit of the place alone sends no times, and at home an empty address for the primary Client's`() = runTest {
        enqueue("""{"result":{"data":$updated}}""")

        val saved = api.updateAppointmentTimeAndPlace("a1", startsAt = null, endsAt = null, locationType = LocationType.AT_HOME, customLocationAddress = "")

        assertEquals("""{"id":"a1","locationType":"atHome","customLocationAddress":""}""", server.takeRequest().body?.utf8())
        // The reply states where the Appointment is now: at the address of its primary Client.
        assertEquals("2026-10-21T20:00:00.000Z", saved.startsAt)
        assertNull(saved.endsAt)
        assertEquals(LocationType.AT_HOME, saved.locationType)
        assertEquals("9 Lamar Blvd, Austin", saved.locationAddress)
        assertEquals("", saved.customLocationAddress)
        assertEquals("https://www.google.com/maps/search/?api=1&query=9%20Lamar%20Blvd%2C%20Austin", saved.mapUrl)
    }

    @Test
    fun `an edit of the times alone sends no place, so the Appointment keeps the address it has`() = runTest {
        enqueue("""{"result":{"data":$updated}}""")
        enqueue("""{"result":{"data":$updated}}""")

        // An at-home Appointment with an outcome whose time is corrected: an empty address would be answered with the address its Client has today.
        api.updateAppointmentTimeAndPlace("a1", startsAt = Instant.parse("2026-10-21T20:00:00Z"), endsAt = null, locationType = null, customLocationAddress = "")
        // Nothing was changed at all.
        api.updateAppointmentTimeAndPlace("a1", startsAt = null, endsAt = null, locationType = null, customLocationAddress = "1 Lost Lane")

        assertEquals("""{"id":"a1","startsAt":"2026-10-21T20:00:00Z","endsAt":null}""", server.takeRequest().body?.utf8())
        assertEquals("""{"id":"a1"}""", server.takeRequest().body?.utf8())
    }

    @Test
    fun `the reply to a status change names the Appointments the time is taken by`() = runTest {
        enqueue("""{"result":{"data":$updated}}""")
        // A server from before the overlap warning of updates: no warning, and no failed save.
        enqueue("""{"result":{"data":{"id":"a1","startsAt":"2026-10-21T20:00:00.000Z","status":"canceled","locationType":"inSalon"}}}""")

        val scheduled = api.updateAppointmentStatus("a1", AppointmentStatus.SCHEDULED)
        val canceled = api.updateAppointmentStatus("a1", AppointmentStatus.CANCELED)

        assertEquals("""{"id":"a1","status":"scheduled"}""", server.takeRequest().body?.utf8())
        assertEquals(listOf("a2"), scheduled.conflicts.map { it.id })
        assertEquals("Bob Noaddress", scheduled.conflicts.single().participants.single().name)
        assertEquals("2026-10-21T20:00:00.000Z", scheduled.conflicts.single().startsAt)
        assertTrue(canceled.conflicts.isEmpty())
    }

    @Test
    fun `one Appointment is asked for by its id alone, so it is found wherever it was moved to`() = runTest {
        enqueue("""{"result":{"data":$updated}}""")

        val found = api.findAppointment("a1")
        val request = server.takeRequest()

        assertEquals("/api/trpc/appointment.get", request.url.encodedPath)
        assertEquals("""{"id":"a1"}""", request.url.queryParameter("input"))
        assertEquals("2026-10-21T20:00:00.000Z", found?.startsAt)
        assertEquals("9 Lamar Blvd, Austin", found?.locationAddress)
    }

    @Test
    fun `an Appointment is gone only when the api says so`() = runTest {
        enqueue("""{"error":{"message":"Appointment not found.","code":-32004,"data":{"code":"NOT_FOUND","httpStatus":404}}}""", code = 404)
        // A server from before the procedure existed. Taken for "gone", every Appointment would show as not found.
        enqueue("""{"error":{"message":"No procedure found on path \"appointment.get\"","code":-32004,"data":{"code":"NOT_FOUND","httpStatus":404}}}""", code = 404)

        assertNull(api.findAppointment("a1"))
        try {
            api.findAppointment("a1")
            fail("expected a failed request")
        } catch (error: ApiException) {
            assertTrue(error.isNotFound)
        }
    }

    @Test
    fun `Appointments and their Clients are in the order the server lists them`() = runTest {
        // C15, C1: the server keeps one order for Appointments that start together and lists the primary Client
        // first, then the others as they were added. The app shows that order as it is.
        fun appointment(id: String, vararg clients: String) =
            """{"id":"$id","primaryClientId":"${clients.first()}","primaryClientName":"${clients.first()}","participants":[""" +
                clients.mapIndexed { index, client -> """{"clientId":"$client","name":"$client","isPrimary":${index == 0}}""" }.joinToString(",") +
                """],"startsAt":"2026-10-09T23:33:00.000Z","status":"scheduled","locationType":"inSalon"}"""
        enqueue("""{"result":{"data":[${appointment("tia", "Tia")},${appointment("tom", "Dana", "Cara", "Bob", "Anna")},${appointment("ada", "Ada")}]}}""")

        val day = api.listAppointments(Instant.parse("2026-10-09T05:00:00Z"), Instant.parse("2026-10-10T05:00:00Z"))

        assertEquals(listOf("tia", "tom", "ada"), day.map { it.id })
        assertEquals(listOf("Dana", "Cara", "Bob", "Anna"), day[1].participants.map { it.name })
        assertTrue(day[1].participants.first().isPrimary)
    }

    @Test
    fun `Services and their Color Formulas are in the order the server lists them`() = runTest {
        // L21: the server keeps the order they were added in, also for copies and after an edit. The app shows it as it is.
        enqueue(
            """{"result":{"data":{"id":"a1","primaryClientId":"c1","primaryClientName":"Anna Avery","services":[""" +
                """{"id":"s2","clientId":"c1","name":"Gloss","priceCents":6499,"colorFormulas":[{"id":"f9","formula":"6N + 7N 1:1 EDIT","placement":"Roots"},{"id":"f1","formula":"Toner 9V","placement":""}]},""" +
                """{"id":"s1","clientId":"c1","name":"Cut","priceCents":4000}""" +
                """],"startsAt":"2026-10-10T10:57:00.000Z","status":"scheduled","locationType":"inSalon"}}}""",
        )

        val appointment = api.findAppointment("a1")!!

        assertEquals(listOf("Gloss", "Cut"), appointment.services.map { it.name })
        assertEquals(listOf("6N + 7N 1:1 EDIT", "Toner 9V"), appointment.services.first().colorFormulas.map { it.formula })
    }

    @Test
    fun `an edit of a Service sends its name, price and note together, and an emptied note as empty`() = runTest {
        // M22. Left out, the note would be cleared as well, but all three are what the form shows.
        enqueue("""{"result":{"data":{}}}""")
        enqueue("""{"result":{"data":{}}}""")

        api.updateService("s1", name = "Fix Me Again", priceCents = 1_250, note = "new note")
        api.updateService("s1", name = "Fix Me Again", priceCents = 0, note = "")
        val edited = server.takeRequest()
        val cleared = server.takeRequest()

        assertEquals("POST", edited.method)
        assertEquals("/api/trpc/appointment.updateService", edited.url.encodedPath)
        assertEquals("""{"id":"s1","name":"Fix Me Again","priceCents":1250,"note":"new note"}""", edited.body?.utf8())
        assertEquals("""{"id":"s1","name":"Fix Me Again","priceCents":0,"note":""}""", cleared.body?.utf8())
    }

    @Test
    fun `the copy sheet asks for what can be copied into its Appointment and reads what each source copies`() = runTest {
        // L25: only sources with something to copy, each with how many Services and whose.
        enqueue(
            """{"result":{"data":[{"id":"src","primaryClientId":"bob","primaryClientName":"Bob Baker","participants":[""" +
                """{"clientId":"bob","name":"Bob Baker","isPrimary":true},{"clientId":"anna","name":"Anna Avery"}],"services":[""" +
                """{"id":"s1","clientId":"anna","name":"Anna Color","priceCents":12050},{"id":"s2","clientId":"bob","name":"Bob Fade","priceCents":4000}],""" +
                """"startsAt":"2026-10-10T15:19:00.000Z","status":"completed","locationType":"inSalon","copyableServiceCount":1,"copyableClientIds":["anna"]}]}}""",
        )

        val sources = api.completedSources("target-1")
        val request = server.takeRequest()

        assertEquals("/api/trpc/appointment.completedSources", request.url.encodedPath)
        assertEquals("""{"targetAppointmentId":"target-1"}""", request.url.queryParameter("input"))
        assertEquals(1, sources.single().copyableServiceCount)
        assertEquals(listOf("anna"), sources.single().copyableClientIds)
    }

    @Test
    fun `nothing can be copied into an Appointment that is gone, and any other failure of the list is a failure`() = runTest {
        enqueue("""{"error":{"message":"Target Appointment not found.","code":-32004,"data":{"code":"NOT_FOUND","httpStatus":404}}}""", code = 404)
        assertTrue(api.completedSources("gone").isEmpty())

        enqueue("""{"error":{"message":"Stylist not found.","code":-32004,"data":{"code":"NOT_FOUND","httpStatus":404}}}""", code = 404)
        try {
            api.completedSources("a1")
            fail("expected ApiException")
        } catch (error: ApiException) {
            assertTrue(error.isNotFound)
        }
    }

    @Test
    fun `a copy is sent with its key and answers how many Services it added`() = runTest {
        // M8: the key makes a copy that is sent twice one copy. M7, L25: the reply says how many were copied.
        val reply = """{"result":{"data":{"id":"a1","primaryClientId":"c1","primaryClientName":"Anna Avery","startsAt":"2026-10-10T18:31:00.000Z","status":"scheduled","locationType":"inSalon","copiedServiceCount":2}}}"""
        enqueue(reply)
        enqueue(reply)
        enqueue("""{"result":{"data":{"id":"a1"}}}""")

        assertEquals(2, api.copyServices("a1", "src", requestKey = "3f0c1b6e-0000-4000-8000-000000000001"))
        assertEquals(2, api.copyServices("a1", sourceAppointmentId = null, requestKey = "3f0c1b6e-0000-4000-8000-000000000001"))
        // A reply that does not say how many is still a copy that was made.
        assertNull(api.copyServices("a1", "src", requestKey = "3f0c1b6e-0000-4000-8000-000000000002"))

        assertEquals(
            """{"targetAppointmentId":"a1","sourceAppointmentId":"src","requestKey":"3f0c1b6e-0000-4000-8000-000000000001"}""",
            server.takeRequest().body?.utf8(),
        )
        // The latest Completed Appointment with something to copy: no source is named.
        assertEquals("""{"targetAppointmentId":"a1","requestKey":"3f0c1b6e-0000-4000-8000-000000000001"}""", server.takeRequest().body?.utf8())
    }

    @Test
    fun `a copy refused because its source has nothing to copy means the sheet's list is out of date`() = runTest {
        // L25: the sheet lists only sources that have something to copy, so the list is loaded again after this refusal.
        enqueue("""{"error":{"message":"Source Appointment has no Services to copy.","code":-32600,"data":{"code":"BAD_REQUEST","httpStatus":400}}}""", code = 400)
        enqueue("""{"error":{"message":"Appointment total is too large.","code":-32600,"data":{"code":"BAD_REQUEST","httpStatus":400}}}""", code = 400)

        suspend fun refused(): ApiException =
            try {
                api.copyServices("a1", "src", requestKey = "3f0c1b6e-0000-4000-8000-000000000001")
                throw AssertionError("expected a refused copy")
            } catch (error: ApiException) {
                error
            }

        assertTrue(refused().screenOutOfDate)
        assertFalse(refused().screenOutOfDate)
    }

    @Test
    fun `a Client's history keeps each Service's price, note and Color Formulas, and the Client's photos`() = runTest {
        // L22, L49: all of this was in the reply and dropped by the model.
        enqueue(
            """{"result":{"data":[{"id":"a1","primaryClientId":"c1","primaryClientName":"Anna Avery","startsAt":"2026-10-10T12:17:00.000Z","status":"completed","participantClientIds":["c1","c2"],""" +
                """"services":[{"id":"s1","clientId":"c1","menuItemId":null,"name":"Old Trim","priceCents":3000,"note":"dry cut","colorFormulas":[{"id":"f1","formula":"5N + 20 vol","placement":"Ends"},{"id":"f2","formula":"Toner 9V","placement":""}]}],""" +
                """"photos":[{"id":"p1","clientId":"c1","category":"before","url":"https://app.ufs.sh/f/k1","thumbnailUrl":"","width":null,"height":null,"createdAt":"2026-10-10T12:20:00.000Z"},""" +
                """{"id":"p2","clientId":"c1","category":"after","url":"https://app.ufs.sh/f/k2","thumbnailUrl":"https://app.ufs.sh/f/k2","width":1200,"height":1600,"createdAt":"2026-10-10T13:20:00.000Z"}]},""" +
                """{"id":"a0","primaryClientId":"c2","primaryClientName":"Bob Baker","startsAt":"2026-10-01T12:00:00.000Z","status":"canceled","participantClientIds":["c2","c1"],"services":[],"photos":[]}]}}""",
        )

        val history = api.clientAppointmentHistory("c1")
        val service = history.first().services.single()

        assertEquals("""{"id":"c1"}""", server.takeRequest().url.queryParameter("input"))
        assertEquals(listOf("a1", "a0"), history.map { it.id })
        assertEquals("Old Trim", service.name)
        assertEquals(3_000L, service.priceCents)
        assertEquals("dry cut", service.note)
        assertEquals(listOf("Ends" to "5N + 20 vol", "" to "Toner 9V"), service.colorFormulas.map { it.placement to it.formula })
        assertEquals(listOf(PhotoCategory.BEFORE, PhotoCategory.AFTER), history.first().photos.map { it.category })
        assertEquals("https://app.ufs.sh/f/k1", history.first().photos.first().url)
        // An Appointment in which the Client has no Services and no photos is a row of the history all the same.
        assertTrue(history.last().services.isEmpty() && history.last().photos.isEmpty())
    }

    @Test
    fun `a change the api refuses because of the Clients an Appointment has now is told from any other refusal`() = runTest {
        fun refusal(message: String) = enqueue("""{"error":{"message":"$message","code":-32600,"data":{"code":"BAD_REQUEST","httpStatus":400}}}""", code = 400)
        suspend fun refused(change: suspend () -> Unit): ApiException =
            try {
                change()
                throw AssertionError("expected a refused change")
            } catch (error: ApiException) {
                error
            }

        // L29: the Client was removed from the Appointment on another device.
        refusal("Client must be attached to the Appointment.")
        refusal("Primary Client must be attached to the Appointment.")
        refusal("Service Client must be attached to the Appointment.")
        val gone = listOf(
            refused { api.removeParticipant("a1", "c2") },
            refused { api.updateAppointmentPrimary("a1", "c2") },
            refused { api.addService("a1", "c2", menuItemId = "m1", name = null, priceCents = null, note = null) },
        )

        assertTrue(gone.all { it.isClientNotAttached && it.screenOutOfDate })

        // H1, A1: the Client has photos or Services there, or is the last one left.
        refusal("Client has Appointment Photos attached to this Appointment.")
        refusal("Client has Services attached to this Appointment.")
        refusal("Appointment must keep at least one Client.")
        val kept = List(3) { refused { api.removeParticipant("a1", "c2") } }

        assertTrue(kept.all { it.screenOutOfDate && !it.isClientNotAttached })

        // Input the api does not take is no sign that the screen is out of date.
        refusal("End time must be after start time.")
        val invalid = refused { api.removeParticipant("a1", "c2") }

        assertFalse(invalid.screenOutOfDate || invalid.isClientNotAttached)
    }

    @Test
    fun `server errors carry the tRPC code and message`() = runTest {
        enqueue("""{"error":{"message":"Client not found.","code":-32004,"data":{"code":"NOT_FOUND","httpStatus":404}}}""", code = 404)

        try {
            api.deleteClient("missing")
            fail("expected an ApiException")
        } catch (error: ApiException) {
            assertEquals("NOT_FOUND", error.code)
            assertEquals("Client not found.", error.message)
            assertFalse(error.isNetwork)
        }
    }

    @Test
    fun `validation errors show the first issue instead of raw json`() = runTest {
        val issues = """[{\"code\":\"too_small\",\"path\":[\"name\"],\"message\":\"Too small: expected string to have >=1 characters\"}]"""
        enqueue("""{"error":{"message":"$issues","code":-32600,"data":{"code":"BAD_REQUEST","httpStatus":400}}}""", code = 400)

        try {
            api.saveServiceMenuItem(null, "", 0)
            fail("expected an ApiException")
        } catch (error: ApiException) {
            assertEquals("Too small: expected string to have >=1 characters", error.message)
        }
    }

    @Test
    fun `an unreachable server is reported as a network failure`() = runTest {
        server.close()

        try {
            api.listServiceMenu()
            fail("expected an ApiException")
        } catch (error: ApiException) {
            assertTrue(error.isNetwork)
        }
    }

    @Test
    fun `a change that could not be sent is a network failure, not an unknown outcome`() = runTest {
        server.close()

        try {
            api.deleteClient("c1")
            fail("expected an ApiException")
        } catch (error: ApiException) {
            assertTrue(error.isNetwork)
            assertFalse(error.outcomeUnknown)
        }
    }

    /** Reads the request, then never answers. */
    private fun enqueueNoReply() {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
    }

    private fun impatientApi() =
        HcmApi(TrpcClient(server.url("/api/trpc").toString(), http.newBuilder().readTimeout(300, TimeUnit.MILLISECONDS).build()) { "token-1" })

    @Test
    fun `a change that was sent but never answered has an unknown outcome`() = runTest {
        enqueueNoReply()

        try {
            impatientApi().saveClient(null, "Lost Carl", Language.EN, "5550123", "", "", "")
            fail("expected an ApiException")
        } catch (error: ApiException) {
            assertTrue(error.outcomeUnknown)
            assertFalse(error.isNetwork)
        }
        // The server did receive it, so it may well have been saved.
        assertEquals("/api/trpc/clientProfile.save", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `a query that was never answered is a plain network failure`() = runTest {
        enqueueNoReply()

        try {
            impatientApi().listClients()
            fail("expected an ApiException")
        } catch (error: ApiException) {
            assertTrue(error.isNetwork)
            assertFalse(error.outcomeUnknown)
        }
    }

    @Test
    fun `a change answered by a server-side failure that is not the api's has an unknown outcome`() = runTest {
        // A gateway that gave up waiting, the hosting platform's own error, the dev backend's
        // catch-all: the API may have saved the change before any of them answered in its place.
        val replies = listOf(
            504 to "<html><body><h1>504 Gateway Time-out</h1></body></html>",
            502 to """{"error":{"code":"502","message":"An error occurred with your deployment"}}""",
            503 to "",
            500 to """{"error":"Internal dev backend error"}""",
        )

        for ((status, reply) in replies) {
            enqueue(reply, code = status)
            try {
                api.saveClient(null, "Lost Carl", Language.EN, "5550123", "", "", "")
                fail("expected an ApiException for the $status reply")
            } catch (error: ApiException) {
                assertTrue("$status", error.outcomeUnknown)
                assertFalse("$status", error.isNetwork)
            }
        }
    }

    @Test
    fun `a refusal by the api, a reply from in front of it and a failed query are plain failures`() = runTest {
        // The API's own failure says that the change was not made.
        enqueue("""{"error":{"message":"Internal server error.","code":-32603,"data":{"code":"INTERNAL_SERVER_ERROR","httpStatus":500}}}""", code = 500)
        try {
            api.deleteClient("c1")
            fail("expected an ApiException")
        } catch (error: ApiException) {
            assertEquals("INTERNAL_SERVER_ERROR", error.code)
            assertFalse(error.outcomeUnknown)
        }

        // A rate limiter turned the change away before it reached the API.
        enqueue("<html><body>Too many requests</body></html>", code = 429)
        try {
            api.deleteClient("c1")
            fail("expected an ApiException")
        } catch (error: ApiException) {
            assertEquals("BAD_RESPONSE", error.code)
            assertFalse(error.outcomeUnknown)
        }

        // A query changes nothing, so there is no outcome to be unsure of.
        enqueue("<html><body><h1>504 Gateway Time-out</h1></body></html>", code = 504)
        try {
            api.listClients()
            fail("expected an ApiException")
        } catch (error: ApiException) {
            assertEquals("BAD_RESPONSE", error.code)
            assertFalse(error.outcomeUnknown)
        }
    }

    @Test
    fun `a json reply that is not a tRPC envelope is a failure, not a crash`() = runTest {
        val replies = listOf(
            """{"error":"Too many requests"}""",
            """{"error":["Too many requests"]}""",
            """{"result":null}""",
            """{"result":"ok"}""",
            """{"result":{}}""",
            """{"result":{"data":"ok"}}""",
            """[]""",
            """null""",
            """"Internal dev backend error"""",
        )

        for (reply in replies) {
            enqueue(reply)
            try {
                api.listClients()
                fail("expected an ApiException for the query reply $reply")
            } catch (error: ApiException) {
                assertEquals(reply, "BAD_RESPONSE", error.code)
            }

            enqueue(reply)
            try {
                api.updateAppointmentNote("a1", "gate note x")
                fail("expected an ApiException for the mutation reply $reply")
            } catch (error: ApiException) {
                assertEquals(reply, "BAD_RESPONSE", error.code)
            }
        }
    }

    @Test
    fun `an error envelope with unexpected parts is still an api failure`() = runTest {
        enqueue("""{"error":{"message":{"text":"Too many requests"},"data":"none"}}""", code = 429)

        try {
            api.listClients()
            fail("expected an ApiException")
        } catch (error: ApiException) {
            assertEquals("UNKNOWN", error.code)
            assertEquals("Request failed.", error.message)
        }
    }

    @Test
    fun `a null error beside a result is not an error`() = runTest {
        enqueue("""{"error":null,"result":{"data":[]}}""")

        assertTrue(api.listClients().isEmpty())
    }

    @Test
    fun `upload requests a presigned url then puts the file to it`() = runTest {
        val uploads = UploadThingClient(server.url("/api/uploadthing").toString(), http) { "token-1" }
        val presignedUrl = server.url("/ingest/key-1?signature=abc")
        enqueue("""[{"url":"$presignedUrl","key":"key-1","name":"photo.jpg","customId":null}]""")
        server.enqueue(MockResponse.Builder().code(200).addHeader("x-ut-range-start", "0").build())
        enqueue("""{"url":"https://ufs/key-1","appUrl":"https://ufs/key-1","ufsUrl":"https://ufs/key-1","fileHash":"h","serverData":{"appointmentId":"a1","clientId":"c1","category":"before","fileKey":"key-1","url":"https://ufs/key-1","thumbnailUrl":"https://ufs/key-1"}}""")

        val input = buildJsonObject {
            put("appointmentId", "a1")
            put("clientId", "c1")
            put("category", "before")
        }
        val uploaded = uploads.upload(UploadThingClient.APPOINTMENT_PHOTO_SLUG, input, "photo.jpg", "image/jpeg", ByteArray(64) { 7 })

        val start = server.takeRequest()
        assertEquals("POST", start.method)
        assertEquals("upload", start.url.queryParameter("actionType"))
        assertEquals("appointmentPhoto", start.url.queryParameter("slug"))
        assertEquals("Bearer token-1", start.headers["Authorization"])
        assertEquals(UploadThingClient.CLIENT_VERSION, start.headers["x-uploadthing-version"])
        val startBody = start.body?.utf8().orEmpty()
        assertTrue(startBody.contains(""""input":{"appointmentId":"a1","clientId":"c1","category":"before"}"""))
        assertTrue(startBody.contains(""""name":"photo.jpg","size":64,"type":"image/jpeg""""))

        assertEquals("HEAD", server.takeRequest().method)

        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/ingest/key-1", put.url.encodedPath)
        assertEquals("bytes=0-", put.headers["Range"])
        // The storage service authenticates by the signed url, so our session token must not be sent to it.
        assertNull(put.headers["Authorization"])
        assertTrue(put.headers["Content-Type"].orEmpty().startsWith("multipart/form-data"))

        assertEquals("key-1", uploaded.key)
        val photo = UploadThingClient.decodeUploadedPhoto(uploaded.serverData)
        assertEquals(PhotoCategory.BEFORE, photo?.category)
        assertEquals("https://ufs/key-1", photo?.url)
    }

    @Test
    fun `an upload reply with an error of an unexpected shape is a failed upload, not a crash`() = runTest {
        val uploads = UploadThingClient(server.url("/api/uploadthing").toString(), http) { "token-1" }
        enqueue("""[{"url":"${server.url("/ingest/key-1")}","key":"key-1"}]""")
        server.enqueue(MockResponse.Builder().code(200).build())
        enqueue("""{"error":{"code":"TooLarge"}}""")

        try {
            uploads.upload("appointmentPhoto", buildJsonObject {}, "photo.jpg", "image/jpeg", ByteArray(1))
            fail("expected an ApiException")
        } catch (error: ApiException) {
            assertEquals("UPLOAD_FAILED", error.code)
        }
    }

    @Test
    fun `a rejected upload surfaces the route's message`() = runTest {
        val uploads = UploadThingClient(server.url("/api/uploadthing").toString(), http) { null }
        enqueue("""{"message":"Appointment not found."}""", code = 500)

        try {
            uploads.upload("appointmentPhoto", buildJsonObject {}, "photo.jpg", "image/jpeg", ByteArray(1))
            fail("expected an ApiException")
        } catch (error: ApiException) {
            assertEquals("Appointment not found.", error.message)
        }
    }

    @Test
    fun `the route's refusal of a Client who is no longer in the Appointment is the API's own, and no other refusal is`() = runTest {
        val uploads = UploadThingClient(server.url("/api/uploadthing").toString(), http) { "token-1" }

        suspend fun refusal(message: String, status: Int): ApiException {
            enqueue("""{"message":"$message"}""", code = status)

            return try {
                uploads.upload("appointmentPhoto", buildJsonObject {}, "photo.jpg", "image/jpeg", ByteArray(1))
                throw AssertionError("expected an ApiException")
            } catch (error: ApiException) {
                error
            }
        }

        // L29: the Client was removed on another device. The route answers before any picture is stored.
        val removed = refusal("Photo Client must be attached to the Appointment.", status = 400)

        assertTrue(removed.isClientNotAttached)
        assertTrue(removed.screenOutOfDate)
        assertEquals(R.string.error_client_not_in_appointment, removed.messageRes())

        // Told by the status together with the words: neither alone makes it that refusal.
        listOf(
            refusal("Invalid config: InvalidFileType", status = 400),
            refusal("Photo Client must be attached to the Appointment.", status = 500),
        ).forEach { other ->
            assertEquals("UPLOAD_REJECTED", other.code)
            assertFalse(other.screenOutOfDate)
            assertEquals(R.string.appointment_photo_upload_failed, other.messageRes())
        }
    }
}
