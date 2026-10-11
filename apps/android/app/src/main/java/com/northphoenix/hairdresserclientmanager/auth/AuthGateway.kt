package com.northphoenix.hairdresserclientmanager.auth

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.northphoenix.hairdresserclientmanager.data.ApiException
import com.northphoenix.hairdresserclientmanager.domain.OneLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
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

/**
 * The first and last name on the Stylist's sign-in account. Clients see it on Profile Shares and
 * Share Images; the account's email address never stands in for it. An account without a name
 * has two empty parts.
 */
data class AccountName(val first: String = "", val last: String = "") {
    companion object {
        /** How long one part can be. The Share Image has one line for the whole name. */
        const val MAX_LENGTH = 50

        /** What a field of the name holds while it is typed into: no more than [MAX_LENGTH] characters, and no half of a character that takes two. */
        fun typed(text: String): String =
            if (text.length <= MAX_LENGTH) text else text.take(if (text[MAX_LENGTH - 1].isHighSurrogate()) MAX_LENGTH - 1 else MAX_LENGTH)

        /** The name as it is saved, from what the Stylist typed: each part on one line, without spaces at its ends. */
        fun of(first: String, last: String): AccountName = AccountName(OneLine.clean(typed(first)), OneLine.clean(typed(last)))
    }
}

/** The app's only dependency on an identity provider. Clerk in production. */
interface AuthGateway {
    val state: StateFlow<AuthState>

    /** The name on the signed-in account. Null when nobody is signed in or the provider keeps no names. */
    val accountName: StateFlow<AccountName?> get() = NoAccountName

    /** Saves [name] on the signed-in account. Throws an [ApiException] when it was not saved. */
    suspend fun saveAccountName(name: AccountName) {
        throw ApiException("This sign-in provider keeps no names.", code = ACCOUNT_NAME_NOT_SAVED)
    }

    /** Session token sent as `Authorization: Bearer <token>`, or null when signed out. */
    suspend fun token(): String?

    /**
     * Ends the session on this device. Throws an [ApiException] ([SIGN_OUT_FAILED]) when it did
     * not and the Stylist is still signed in, and only then: whoever asked takes a failure to
     * mean that [state] stays as it is.
     */
    suspend fun signOut()

    /** Asks the provider about the session after the API refused its token. */
    suspend fun checkSession(): SessionCheck = SessionCheck.ALIVE

    /** Retries provider start-up after [AuthState.Failed]. */
    fun retry() {}

    @Composable
    fun SignInContent(modifier: Modifier)
}

private val NoAccountName: StateFlow<AccountName?> = MutableStateFlow(null)

/** The code of the failure of [AuthGateway.saveAccountName] when the provider refused or failed to save the name. */
const val ACCOUNT_NAME_NOT_SAVED = "ACCOUNT_NAME_NOT_SAVED"

/** The code of the failure of [AuthGateway.signOut]: the Stylist is still signed in. */
const val SIGN_OUT_FAILED = "SIGN_OUT_FAILED"

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
