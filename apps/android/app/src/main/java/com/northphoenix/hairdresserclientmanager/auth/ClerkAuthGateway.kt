package com.northphoenix.hairdresserclientmanager.auth

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.clerk.api.Clerk
import com.clerk.api.ClerkConfigurationOptions
import com.clerk.api.network.serialization.successOrNull
import com.clerk.ui.auth.AuthMode
import com.clerk.ui.auth.AuthView
import com.northphoenix.hairdresserclientmanager.BuildConfig
import com.northphoenix.hairdresserclientmanager.ui.theme.hcmClerkTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Email/password Stylist auth through Clerk's prebuilt native views (ADR 0004). */
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
                !initialized && initializationError != null ->
                    AuthState.Failed(initializationError.message ?: "Clerk failed to start.")
                !initialized -> AuthState.Loading
                authComplete && user != null ->
                    AuthState.SignedIn(userId = user.id, email = user.primaryEmailAddress?.emailAddress.orEmpty())
                else -> AuthState.SignedOut
            }
        }.stateIn(scope, SharingStarted.Eagerly, AuthState.Loading)

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

    override suspend fun token(): String? = Clerk.auth.getToken().successOrNull()

    override suspend fun signOut() {
        Clerk.auth.signOut()
    }

    @Composable
    override fun SignInContent(modifier: Modifier) {
        AuthView(
            modifier = modifier,
            clerkTheme = hcmClerkTheme(),
            isDismissible = false,
            mode = AuthMode.SignInOrUp,
        )
    }
}
