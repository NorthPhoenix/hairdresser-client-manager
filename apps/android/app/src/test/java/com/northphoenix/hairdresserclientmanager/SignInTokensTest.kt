package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.ApiException
import com.northphoenix.hairdresserclientmanager.data.SignInTokens
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SignInTokensTest {
    private val refusal = ApiException("Authentication required.", code = "UNAUTHORIZED")

    /** A server that refuses every token in [refused] and no token at all, and answers with the token otherwise. */
    private fun server(vararg refused: String): suspend (String?) -> String = { token ->
        if (token == null || token in refused) throw refusal
        "answered with $token"
    }

    @Test
    fun `a refusal after a new token was issued asks for another one`() = runTest {
        var kept: String? = null
        var issued = 0
        val tokens = SignInTokens { fresh -> if (fresh) "token-${++issued}" else kept }

        assertEquals("answered with token-1", tokens.sending(server()))

        // The provider now keeps that one, and the server refuses it later on (it ran out, say).
        kept = "token-1"
        assertEquals("answered with token-2", tokens.sending(server("token-1")))
        assertEquals(2, issued)
    }

    @Test
    fun `a request refused with the token another one was just given asks for a new one`() = runTest {
        var kept: String? = null
        var keptAsked = 0
        var issued = 0
        val keptLate = CompletableDeferred<Unit>()
        val tokens = SignInTokens { fresh ->
            when {
                fresh -> "token-${++issued}"
                // The first request is slow to get the kept token: by then another one has had a new token issued.
                keptAsked++ == 0 -> {
                    keptLate.await()
                    kept
                }
                else -> kept
            }
        }

        val slow = async { tokens.sending(server("token-1")) }
        runCurrent()
        assertEquals("answered with token-1", tokens.sending(server()))

        kept = "token-1"
        keptLate.complete(Unit)

        // Sent with token-1 and refused: not sent again with it.
        assertEquals("answered with token-2", slow.await())
        assertEquals(2, issued)
    }

    @Test
    fun `a kept token the provider fails to give is sent as none, and then a new one`() = runTest {
        val sent = mutableListOf<String?>()
        val tokens = SignInTokens { fresh ->
            if (fresh) "token-2" else throw ApiException("The sign-in provider dropped the token request.", code = "TOKEN_REQUEST_DROPPED")
        }

        assertEquals("answered with token-2", tokens.sending { token -> sent += token; server()(token) })
        assertEquals(listOf(null, "token-2"), sent)
    }

    @Test
    fun `a provider that gives the same token again has none newer, and the refusal stands`() = runTest {
        var sent = 0
        val tokens = SignInTokens { "token-1" }

        val refused = runCatching { tokens.sending { token -> sent++; server("token-1")(token) } }

        assertEquals(refusal, refused.exceptionOrNull())
        assertEquals(1, sent)
    }

    @Test
    fun `requests that waited behind a cancelled one ask for a new token themselves`() = runTest {
        var freshAsked = 0
        val tokens = SignInTokens { fresh ->
            if (fresh && freshAsked++ == 0) awaitCancellation()
            if (fresh) "token-1" else null
        }

        val first = launch { tokens.sending(server()) }
        runCurrent()
        val second = async { tokens.sending(server()) }
        runCurrent()
        first.cancel()

        assertEquals("answered with token-1", second.await())
        assertEquals(2, freshAsked)
    }

    @Test
    fun `requests refused while a new token was asked for share what it got, also nothing`() = runTest {
        var freshAsked = 0
        val answer = CompletableDeferred<String?>()
        val tokens = SignInTokens { fresh ->
            if (fresh) {
                freshAsked++
                answer.await()
            } else {
                null
            }
        }

        val requests = List(3) { async { runCatching { tokens.sending(server()) } } }
        runCurrent()
        // The provider's servers did not answer.
        answer.complete(null)

        requests.forEach { assertEquals(refusal, it.await().exceptionOrNull()) }
        assertEquals(1, freshAsked)
    }
}
