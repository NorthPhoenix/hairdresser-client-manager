package com.northphoenix.hairdresserclientmanager.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The sign-in tokens that the app's requests to the server carry, from the sign-in provider
 * through [provider] (`fresh` asks it for a new one instead of the one it keeps). The app has one,
 * shared by its API client and its upload client.
 *
 * The token the provider keeps can be missing or out of date while the session is alive. When the
 * app opens after a long pause, the provider can fail to give it: Clerk shares one fetch of it
 * among everyone who asks at the same moment and drops them all when that fetch is cancelled or
 * fails, while a request for a new token is not shared. So a request that gets no kept token while
 * someone is signed in asks for a new one before it goes out, and one that the server refuses
 * for its sign-in is sent once more with a new token (see [sending]). The server refuses a
 * request without a valid token before it does any of it (`protectedProcedure`,
 * `authorizePhotoUpload`), so a change is not made twice. A second refusal, or no new token, is
 * the caller's to report (see `AppViewModel.report`), which asks the provider whether the
 * session has ended.
 */
class SignInTokens(
    /** Who is signed in now, null for nobody: a refused request is sent again only for whoever it was sent for. */
    private val account: () -> String? = { null },
    private val provider: suspend (fresh: Boolean) -> String?,
) {
    private val issuing = Mutex()

    // The last request for a new token: how many there have been, and what it was given (null for nothing). Written under [issuing].
    @Volatile
    private var issued = Issued(count = 0, token = null)

    /**
     * Runs [send] with the token the provider keeps, and once more with a new one when the server
     * refuses it for its sign-in. When the provider fails to give the token it keeps (it can drop
     * the request as the app comes back to the front, see `askingAgainWhenDropped`) while someone
     * is signed in, a new one is asked for first, so the request does not go out without one only
     * to be refused; requests in the same position at the same moment share that new token. With
     * nobody signed in, or no new token either, the request goes out without one.
     */
    suspend fun <T> sending(send: suspend (token: String?) -> T): T {
        var seen = issued.count
        val sender = account()
        var token = ask(fresh = false)

        if (token == null && sender != null) {
            val latest = issuedSince(seen, refusedToken = null)

            // A refusal from here on asks again, unless another request has asked since.
            seen = latest.count
            // Someone else signed in meanwhile: this request was meant for the account it began with.
            token = latest.token?.takeIf { account() == sender }
        }

        return try {
            send(token)
        } catch (refused: ApiException) {
            if (!refused.isUnauthorized) {
                throw refused
            }

            // A provider that gives the same token again (the debug builds' dev sign-in does) has none newer.
            val fresh = issuedSince(seen, refusedToken = token).token?.takeUnless { it == token }

            // Signed out since, or someone else signed in: their token would make the change in their account.
            if (fresh == null || account() != sender) {
                throw refused
            }

            send(fresh)
        }
    }

    /**
     * A new token for a request refused with [refusedToken] (null also for a request that has not
     * gone out yet), with a null token when the provider gave none. Requests asking at the same
     * moment (the app reloads its lists all at once) share one request for it: when a new token has
     * been asked for since [seen], this one takes what that request got, also nothing. It asks for
     * another only when that token is the one it was refused with (the provider already kept it
     * when this request went out).
     */
    private suspend fun issuedSince(seen: Int, refusedToken: String?): Issued =
        issuing.withLock {
            val latest = issued

            if (latest.count != seen && (latest.token == null || latest.token != refusedToken)) {
                latest
            } else {
                Issued(latest.count + 1, ask(fresh = true)).also { issued = it }
            }
        }

    private suspend fun ask(fresh: Boolean): String? =
        try {
            provider(fresh)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }

    private data class Issued(val count: Int, val token: String?)
}
