package com.northphoenix.hairdresserclientmanager.auth

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.northphoenix.hairdresserclientmanager.data.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow

sealed interface AuthState {
    data object Loading : AuthState

    data object SignedOut : AuthState

    data class SignedIn(val userId: String, val email: String) : AuthState

    /** The provider did not start. [isNetwork] means it could not be reached, as opposed to a broken setup. */
    data class Failed(val message: String, val isNetwork: Boolean = false) : AuthState
}

/** What the sign-in provider says about the session after the API refused its token. */
enum class SessionCheck {
    /** The provider has dropped the session, and [AuthGateway.state] becomes [AuthState.SignedOut]. */
    ENDED,

    /** The provider still issues tokens for the session. */
    ALIVE,

    /** The provider could not be asked. It has not ended anything, and it is why the request had no valid token. */
    UNREACHABLE,
}

/** The app's only dependency on an identity provider. Clerk in production. */
interface AuthGateway {
    val state: StateFlow<AuthState>

    /** Session token sent as `Authorization: Bearer <token>`, or null when signed out. */
    suspend fun token(): String?

    suspend fun signOut()

    /** Asks the provider about the session after the API refused its token. */
    suspend fun checkSession(): SessionCheck = SessionCheck.ALIVE

    /** Retries provider start-up after [AuthState.Failed]. */
    fun retry() {}

    @Composable
    fun SignInContent(modifier: Modifier)
}

/**
 * Asks the sign-in provider through [ask] on behalf of a request. A provider that shares one
 * token request between everyone who asks at the same moment can cancel that request for reasons
 * of its own (Clerk does when it restarts its token refresh as the app comes back to the front),
 * and everyone who waited is then thrown a cancellation that nobody asked for. Passed on, it would
 * end the caller's coroutine without a word. So the provider is asked once more, and a second
 * cancellation becomes a failure the caller reports. The caller's own cancellation is passed on.
 */
internal suspend fun <T> askingAgainWhenDropped(ask: suspend () -> T): T {
    try {
        return ask()
    } catch (error: CancellationException) {
        currentCoroutineContext().ensureActive()
    }

    return try {
        ask()
    } catch (error: CancellationException) {
        currentCoroutineContext().ensureActive()
        throw ApiException("The sign-in provider dropped the token request.", code = "TOKEN_REQUEST_DROPPED", cause = error)
    }
}
