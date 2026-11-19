package com.northphoenix.hairdresserclientmanager.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The sign-in tokens that the app's requests to the server carry, from the sign-in provider
 * through [provider] (`fresh` asks it for a new one instead of the one it keeps).
 *
 * A request that the server refuses for its sign-in is sent once more with a new token (see
 * [sending]). The token the provider keeps can be missing or out of date while the session is
 * alive: when the app opens after a long pause, the provider can fail to give one at first, and
 * the request then goes out without one. The server refuses such a request before it does any of
 * it (`protectedProcedure`, `authorizePhotoUpload`), so a change is not made twice. A second
 * refusal, or no new token, is the caller's to report (see `AppViewModel.report`), which asks the
 * provider whether the session has ended.
 */
class SignInTokens(private val provider: suspend (fresh: Boolean) -> String?) {
    private val issuing = Mutex()

    // The last request for a new token: how many there have been, and what it was given (null for nothing). Written under [issuing].
    @Volatile
    private var issued = Issued(count = 0, token = null)

    /** Runs [send] with the token the provider keeps, and once more with a new one when the server refuses it for its sign-in. */
    suspend fun <T> sending(send: suspend (token: String?) -> T): T {
        val seen = issued.count
        val token = provider(false)

        return try {
            send(token)
        } catch (refused: ApiException) {
            if (!refused.isUnauthorized) {
                throw refused
            }

            send(issuedSince(seen) ?: throw refused)
        }
    }

    /**
     * A new token for a refused request, or null when the provider gave none. Requests refused at
     * the same moment (the app reloads its lists all at once) share one request for it: a new token
     * asked for after this request was sent, with an older one, is what it takes too, and so is
     * the provider giving none then.
     */
    private suspend fun issuedSince(seen: Int): String? =
        issuing.withLock {
            val latest = issued

            if (latest.count != seen) {
                latest.token
            } else {
                ask(fresh = true).also { issued = Issued(latest.count + 1, it) }
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
