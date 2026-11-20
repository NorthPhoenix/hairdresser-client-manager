package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.HcmApi
import com.northphoenix.hairdresserclientmanager.data.TrpcClient
import com.northphoenix.hairdresserclientmanager.data.appHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AppHttpClientTest {
    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `a reload's requests all reach the server before any of them is answered`() = runBlocking {
        // As many as a reload with every screen open sends, and more than OkHttp's own limit of 5 at a time.
        val reload = 20
        val arrived = CountDownLatch(reload)
        val answeredBeforeAllArrived = AtomicInteger()

        // A server that has just woken up: it answers nothing until every request is in.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                arrived.countDown()
                if (!arrived.await(5, TimeUnit.SECONDS)) {
                    answeredBeforeAllArrived.incrementAndGet()
                }

                return MockResponse.Builder().body("""{"result":{"data":[]}}""").build()
            }
        }
        val api = HcmApi(TrpcClient(server.url("/api/trpc").toString(), appHttpClient()) { "token-1" })

        val lists = List(reload) { async(Dispatchers.IO) { api.listClients() } }.awaitAll()

        assertEquals(reload, lists.size)
        assertEquals(reload, server.requestCount)
        assertEquals("No request may wait for another one to be answered", 0, answeredBeforeAllArrived.get())
    }
}
