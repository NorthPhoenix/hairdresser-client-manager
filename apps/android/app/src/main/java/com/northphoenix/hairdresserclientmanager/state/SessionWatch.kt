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

    /**
     * Call when the provider did not sign out and the Stylist is still signed in. The request is
     * over with that: when this session ends later, it ends on its own.
     */
    fun signOutNotDone() {
        signOutRequested = false
    }

    /**
     * Call with every new auth state. True when this state is the sign-out the Stylist asked for:
     * the moment at which what the device holds of their Clients is deleted. A session that ended
     * on its own is not: that Stylist is expected back.
     */
    fun onState(state: AuthState): Boolean =
        when (state) {
            is AuthState.SignedIn -> {
                signedIn = true
                signOutRequested = false
                _ended.value = false
                false
            }
            AuthState.SignedOut -> {
                val asked = signedIn && signOutRequested

                if (signedIn) {
                    _ended.value = !signOutRequested
                }
                signedIn = false
                signOutRequested = false
                asked
            }
            // Starting up or failing to start says nothing about the session.
            AuthState.Loading, is AuthState.Failed -> false
        }
}
