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
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

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
