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
    fun `with nobody signed in, a kept token the provider fails to give is sent as none, and then a new one`() = runTest {
        val sent = mutableListOf<String?>()
        val tokens = SignInTokens { fresh ->
            if (fresh) "token-2" else throw ApiException("The sign-in provider dropped the token request.", code = "TOKEN_REQUEST_DROPPED")
        }

        assertEquals("answered with token-2", tokens.sending { token -> sent += token; server()(token) })
        assertEquals(listOf(null, "token-2"), sent)
    }

    @Test
    fun `a request whose kept token is missing while signed in asks for a new one before it goes out`() = runTest {
        val sent = mutableListOf<String?>()
        var freshAsked = 0
        val tokens = SignInTokens(account = { "user-a" }) { fresh ->
            if (fresh) "token-${++freshAsked}" else throw ApiException("The sign-in provider dropped the token request.", code = "TOKEN_REQUEST_DROPPED")
        }

        assertEquals("answered with token-1", tokens.sending { token -> sent += token; server()(token) })
        assertEquals(listOf<String?>("token-1"), sent)
        assertEquals(1, freshAsked)
    }

    @Test
    fun `requests whose kept token is missing at the same moment share one new token`() = runTest {
        val sent = mutableListOf<String?>()
        var freshAsked = 0
        val answer = CompletableDeferred<String?>()
        val tokens = SignInTokens(account = { "user-a" }) { fresh ->
            if (fresh) {
                freshAsked++
                answer.await()
            } else {
                null
            }
        }

        val requests = List(3) { async { tokens.sending { token -> sent += token; server()(token) } } }
        runCurrent()
        answer.complete("token-1")

        requests.forEach { assertEquals("answered with token-1", it.await()) }
        assertEquals(List(3) { "token-1" }, sent)
        assertEquals(1, freshAsked)
    }

    @Test
    fun `with no new token before it goes out, a request refused without one asks once more`() = runTest {
        val sent = mutableListOf<String?>()
        var freshAsked = 0
        val firstAnswer = CompletableDeferred<String?>()
        val tokens = SignInTokens(account = { "user-a" }) { fresh ->
            when {
                !fresh -> null
                freshAsked++ == 0 -> firstAnswer.await()
                else -> "token-$freshAsked"
            }
        }

        val requests = List(3) { async { tokens.sending { token -> sent += token; server()(token) } } }
        runCurrent()
        // The provider's servers do not answer at first, and do by the time the refusals come back.
        firstAnswer.complete(null)

        requests.forEach { assertEquals("answered with token-2", it.await()) }
        assertEquals(List(3) { null }, sent.take(3))
        assertEquals(List(3) { "token-2" }, sent.drop(3))
        assertEquals(2, freshAsked)
    }

    @Test
    fun `a new token asked for before the request goes out is not used after a sign-out or another sign-in`() = runTest {
        listOf(null, "user-b").forEach { signedInMeanwhile ->
            var account: String? = "user-a"
            var freshAsked = 0
            val sent = mutableListOf<String?>()
            val tokens = SignInTokens(account = { account }) { fresh ->
                if (fresh) {
                    freshAsked++
                    // While the new token is on its way.
                    account = signedInMeanwhile
                    "token-of-user-a"
                } else {
                    null
                }
            }

            val refused = runCatching { tokens.sending { token -> sent += token; server()(token) } }

            assertEquals(refusal, refused.exceptionOrNull())
            assertEquals(listOf<String?>(null), sent)
            // Nor is another one asked for after the refusal.
            assertEquals(1, freshAsked)
        }
    }

    @Test
    fun `requests waiting behind a cancelled one for a new token before they go out ask for it themselves`() = runTest {
        var freshAsked = 0
        val sent = mutableListOf<String?>()
        val tokens = SignInTokens(account = { "user-a" }) { fresh ->
            if (fresh && freshAsked++ == 0) awaitCancellation()
            if (fresh) "token-$freshAsked" else null
        }

        val first = launch { tokens.sending { token -> sent += token; server()(token) } }
        runCurrent()
        val second = async { tokens.sending { token -> sent += token; server()(token) } }
        runCurrent()
        first.cancel()

        assertEquals("answered with token-2", second.await())
        assertEquals(listOf<String?>("token-2"), sent)
        assertEquals(2, freshAsked)
    }

    @Test
    fun `a request refused with an old kept token and one with no kept token at the same moment share one new token`() = runTest {
        var keptAsked = 0
        var freshAsked = 0
        val answer = CompletableDeferred<String>()
        val sent = mutableListOf<String?>()
        val tokens = SignInTokens(account = { "user-a" }) { fresh ->
            when {
                fresh -> {
                    freshAsked++
                    answer.await()
                }
                // The first request gets the kept token, which has run out; the second gets none.
                keptAsked++ == 0 -> "kept-token"
                else -> null
            }
        }

        val requests = List(2) { async { tokens.sending { token -> sent += token; server("kept-token")(token) } } }
        runCurrent()
        answer.complete("token-1")

        requests.forEach { assertEquals("answered with token-1", it.await()) }
        assertEquals(listOf("kept-token", "token-1", "token-1"), sent)
        assertEquals(1, freshAsked)
    }

    @Test
    fun `with nobody signed in, a missing kept token goes out as none without asking for a new one first`() = runTest {
        val steps = mutableListOf<String>()
        val tokens = SignInTokens { fresh ->
            steps += if (fresh) "asked for a new token" else "asked for the kept token"
            null
        }

        val refused = runCatching { tokens.sending { token -> steps += "sent with $token"; server()(token) } }

        assertEquals(refusal, refused.exceptionOrNull())
        assertEquals(listOf("asked for the kept token", "sent with null", "asked for a new token"), steps)
    }

    @Test
    fun `a request refused after a sign-out or another sign-in is not sent again`() = runTest {
        listOf(null, "user-b").forEach { signedInMeanwhile ->
            var account: String? = "user-a"
            var freshAsked = 0
            val sent = mutableListOf<String?>()
            // The kept token has run out: the server refuses it.
            val tokens = SignInTokens(account = { account }) { fresh -> if (fresh) "token-of-$account".also { freshAsked++ } else "kept-of-user-a" }

            val refused = runCatching {
                tokens.sending { token ->
                    sent += token
                    // While the request is on its way.
                    account = signedInMeanwhile
                    server("kept-of-user-a")(token)
                }
            }

            assertEquals(refusal, refused.exceptionOrNull())
            assertEquals(listOf<String?>("kept-of-user-a"), sent)
            assertEquals(0, freshAsked)
        }
    }

    @Test
    fun `a request refused while signed in is not sent again after a sign-out or another sign-in while the new token comes`() = runTest {
        listOf(null, "user-b").forEach { signedInMeanwhile ->
            var account: String? = "user-a"
            val sent = mutableListOf<String?>()
            val tokens = SignInTokens(account = { account }) { fresh ->
                if (fresh) {
                    // While the new token is on its way.
                    account = signedInMeanwhile
                    "token-of-user-a"
                } else {
                    "kept-of-user-a"
                }
            }

            val refused = runCatching { tokens.sending { token -> sent += token; server("kept-of-user-a")(token) } }

            assertEquals(refusal, refused.exceptionOrNull())
            assertEquals(listOf<String?>("kept-of-user-a"), sent)
        }
    }

    @Test
    fun `a new token asked for one account is not shared with a request of the next one`() = runTest {
        var account: String? = "user-a"
        var freshAsked = 0
        val answer = CompletableDeferred<Unit>()
        val sent = mutableListOf<String?>()
        val tokens = SignInTokens(account = { account }) { fresh ->
            when {
                !fresh -> null
                freshAsked++ == 0 -> {
                    answer.await()
                    "token-of-user-a"
                }
                else -> "token-of-$account"
            }
        }

        val ofUserA = async { runCatching { tokens.sending { token -> sent += token; server()(token) } } }
        runCurrent()
        // The Stylist signs out and someone else signs in while user-a's new token is on its way.
        account = "user-b"
        val ofUserB = async { tokens.sending { token -> sent += token; server()(token) } }
        runCurrent()
        answer.complete(Unit)

        assertEquals(refusal, ofUserA.await().exceptionOrNull())
        assertEquals("answered with token-of-user-b", ofUserB.await())
        assertEquals(listOf(null, "token-of-user-b"), sent)
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
