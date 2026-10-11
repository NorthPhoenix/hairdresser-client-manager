package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.ApiException
import com.northphoenix.hairdresserclientmanager.data.HcmApi
import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.data.PhotoCategory
import com.northphoenix.hairdresserclientmanager.data.TrpcClient
import com.northphoenix.hairdresserclientmanager.data.UploadThingClient
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
    fun `clearing the final total override sends an explicit null`() = runTest {
        enqueue("""{"result":{"data":{}}}""")

        api.updateAppointmentFinalTotal("a1", overrideCents = null)

        assertEquals("""{"id":"a1","finalTotalCentsOverride":null}""", server.takeRequest().body?.utf8())
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
}
