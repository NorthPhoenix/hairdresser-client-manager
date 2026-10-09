package com.northphoenix.hairdresserclientmanager.auth

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

sealed interface AuthState {
    data object Loading : AuthState

    data object SignedOut : AuthState

    data class SignedIn(val userId: String, val email: String) : AuthState

    data class Failed(val message: String) : AuthState
}

/** The app's only dependency on an identity provider. Clerk in production. */
interface AuthGateway {
    val state: StateFlow<AuthState>

    /** Session token sent as `Authorization: Bearer <token>`, or null when signed out. */
    suspend fun token(): String?

    suspend fun signOut()

    /** Retries provider start-up after [AuthState.Failed]. */
    fun retry() {}

    @Composable
    fun SignInContent(modifier: Modifier)
}
