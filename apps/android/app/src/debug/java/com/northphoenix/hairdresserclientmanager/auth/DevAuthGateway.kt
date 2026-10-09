package com.northphoenix.hairdresserclientmanager.auth

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.northphoenix.hairdresserclientmanager.core.AppConfig
import com.northphoenix.hairdresserclientmanager.ui.components.HcmCard
import com.northphoenix.hairdresserclientmanager.ui.components.Kicker
import com.northphoenix.hairdresserclientmanager.ui.components.PrimaryButton
import com.northphoenix.hairdresserclientmanager.ui.components.Tag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Debug-only stand-in for Clerk, for running against `apps/dev-backend` without provider keys.
 * It exists only in the debug source set, so release builds cannot contain it.
 */
private class DevAuthGateway(context: Context, userId: String) : AuthGateway {
    private val token = "dev_" + userId.removePrefix("dev_")
    private val preferences = context.getSharedPreferences("hcm-dev-auth", Context.MODE_PRIVATE)
    private val signedIn = AuthState.SignedIn(userId = token, email = "$token@dev.local")
    private val mutableState =
        MutableStateFlow(if (preferences.getBoolean(KEY_SIGNED_IN, false)) signedIn else AuthState.SignedOut)

    override val state: StateFlow<AuthState> = mutableState

    override suspend fun token(): String? = if (mutableState.value is AuthState.SignedIn) token else null

    override suspend fun signOut() = setSignedIn(false)

    private fun setSignedIn(value: Boolean) {
        preferences.edit().putBoolean(KEY_SIGNED_IN, value).apply()
        mutableState.value = if (value) signedIn else AuthState.SignedOut
    }

    @Composable
    override fun SignInContent(modifier: Modifier) {
        Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
            HcmCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Kicker("Hairdresser Client Manager")
                    Text("Dev sign-in", style = MaterialTheme.typography.displaySmall)
                    Text(
                        "This debug build skips Clerk and talks to the local dev backend as the user below.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Tag(token)
                    PrimaryButton(
                        "Continue as dev user",
                        onClick = { setSignedIn(true) },
                        modifier = Modifier.fillMaxWidth(),
                        icon = Icons.Rounded.Science,
                    )
                }
            }
        }
    }

    private companion object {
        const val KEY_SIGNED_IN = "signedIn"
    }
}

fun createDevAuthGateway(context: Context, config: AppConfig): AuthGateway? =
    if (config.hasDevAuth) DevAuthGateway(context, config.devAuthUserId) else null
