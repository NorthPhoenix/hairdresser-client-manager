package com.northphoenix.hairdresserclientmanager.state

import com.northphoenix.hairdresserclientmanager.auth.AuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tells a session that ended on its own (revoked, expired, dropped by the sign-in provider) apart
 * from a sign-out the Stylist asked for, so the sign-in screen can say why it has appeared.
 */
internal class SessionWatch {
    private var signedIn = false
    private var signOutRequested = false
    private val _ended = MutableStateFlow(false)

    /** True from the moment a session ends on its own until someone signs in again. */
    val ended: StateFlow<Boolean> = _ended.asStateFlow()

    /** Call before asking the provider to sign out. */
    fun signOutRequested() {
        signOutRequested = true
    }

    /** Call with every new auth state. */
    fun onState(state: AuthState) {
        when (state) {
            is AuthState.SignedIn -> {
                signedIn = true
                signOutRequested = false
                _ended.value = false
            }
            AuthState.SignedOut -> {
                if (signedIn) {
                    _ended.value = !signOutRequested
                }
                signedIn = false
                signOutRequested = false
            }
            // Starting up or failing to start says nothing about the session.
            AuthState.Loading, is AuthState.Failed -> Unit
        }
    }
}
