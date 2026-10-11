package com.northphoenix.hairdresserclientmanager.auth

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.clerk.api.Clerk
import com.clerk.api.ClerkConfigurationOptions
import com.clerk.api.network.serialization.ClerkResult
import com.clerk.api.network.serialization.successOrNull
import com.clerk.api.session.GetTokenOptions
import com.clerk.api.user.User
import com.clerk.api.user.update
import com.northphoenix.hairdresserclientmanager.BuildConfig
import com.northphoenix.hairdresserclientmanager.data.ApiException
import com.northphoenix.hairdresserclientmanager.data.isConnectionFailure
import com.northphoenix.hairdresserclientmanager.ui.screens.SignInScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Email/password Stylist auth through Clerk (ADR 0004). The sign-in screens are the app's own: see [SignInFlow]. */
class ClerkAuthGateway(
    private val context: Context,
    private val publishableKey: String,
    scope: CoroutineScope,
) : AuthGateway {
    private val startFailure = MutableStateFlow<String?>(null)

    init {
        start()
    }

    override val state: StateFlow<AuthState> =
        combine(
            startFailure,
            Clerk.isInitialized,
            Clerk.initializationError,
            Clerk.isAuthFlowCompleteFlow,
            Clerk.userFlow,
        ) { failure, initialized, initializationError, authComplete, user ->
            when {
                failure != null -> AuthState.Failed(failure)
                // Clerk hands over the failure of its own start-up request as it was thrown.
                !initialized && initializationError != null ->
                    AuthState.Failed(
                        initializationError.message ?: "Clerk failed to start.",
                        isNetwork = initializationError.isConnectionFailure(),
                    )
                !initialized -> AuthState.Loading
                authComplete && user != null ->
                    AuthState.SignedIn(userId = user.id, email = user.primaryEmailAddress?.emailAddress.orEmpty())
                else -> AuthState.SignedOut
            }
        }.stateIn(scope, SharingStarted.Eagerly, AuthState.Loading)

    // Clerk keeps its copy of the user up to date from every answer it gets, the answer to a saved name among them.
    override val accountName: StateFlow<AccountName?> =
        Clerk.userFlow
            .map { user -> user?.let { AccountName(it.firstName.orEmpty(), it.lastName.orEmpty()) } }
            .stateIn(scope, SharingStarted.Eagerly, null)

    // Lives as long as the app does, so a request that is on its way survives the screen being recreated.
    private val signIn = SignInFlow(ClerkSignInBackend(), scope)

    init {
        // Someone has signed in: whoever signs in next starts at the first step, with nothing left of this time.
        scope.launch { state.filterIsInstance<AuthState.SignedIn>().collect { signIn.reset() } }
    }

    private fun start() {
        try {
            Clerk.initialize(
                context.applicationContext,
                publishableKey,
                ClerkConfigurationOptions(enableDebugMode = BuildConfig.DEBUG),
            )
            startFailure.value = null
        } catch (error: Exception) {
            // A malformed publishable key throws synchronously instead of reporting through the flow.
            startFailure.value = error.message ?: "Invalid Clerk publishable key."
        }
    }

    override fun retry() {
        Clerk.reset()
        start()
    }

    override suspend fun token(): String? = askingAgainWhenDropped { Clerk.auth.getToken().successOrNull() }

    override suspend fun signOut() {
        val thrown =
            try {
                Clerk.auth.signOut()
                null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                error
            }

        // Clerk forgets the session on this device also when its servers did not answer, so what it returns says
        // little. What counts is whether someone is still signed in here, which Clerk knows by the time it returns.
        if (Clerk.user != null) {
            throw ApiException("Clerk did not sign out.", code = SIGN_OUT_FAILED, cause = thrown)
        }
    }

    /** An empty part takes that part of the name off the account. */
    override suspend fun saveAccountName(name: AccountName) {
        val user = Clerk.user ?: throw ApiException("Nobody is signed in.", code = ACCOUNT_NAME_NOT_SAVED)
        val result = user.update(User.UpdateParams(firstName = name.first, lastName = name.last))

        if (result is ClerkResult.Failure) {
            throw ApiException(
                "Clerk did not save the name.",
                code = ACCOUNT_NAME_NOT_SAVED,
                isNetwork = result.throwable?.isConnectionFailure() == true,
                cause = result.throwable,
            )
        }
    }

    override suspend fun checkSession(): SessionCheck {
        // Skipping the cache makes Clerk ask its servers for a token. Clerk itself drops a session
        // that can no longer issue one; any other failure leaves the session in place.
        val token = Clerk.auth.getToken(GetTokenOptions(skipCache = true))

        return when {
            Clerk.session == null -> SessionCheck.ENDED
            token is ClerkResult.Success<*> -> SessionCheck.ALIVE
            // No token and the session is still there: Clerk did not answer. That is also why the
            // refused request went out without a token (see token()).
            else -> SessionCheck.UNREACHABLE
        }
    }

    @Composable
    override fun SignInContent(modifier: Modifier) {
        SignInScreen(signIn, modifier)
    }
}
