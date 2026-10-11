package com.northphoenix.hairdresserclientmanager.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.auth.AuthState
import com.northphoenix.hairdresserclientmanager.core.AppContainer
import com.northphoenix.hairdresserclientmanager.domain.TimeFormat
import com.northphoenix.hairdresserclientmanager.i18n.messageRes
import com.northphoenix.hairdresserclientmanager.state.AppViewModel
import com.northphoenix.hairdresserclientmanager.state.BootstrapState
import com.northphoenix.hairdresserclientmanager.ui.components.EmptyState
import com.northphoenix.hairdresserclientmanager.ui.components.HcmCard
import com.northphoenix.hairdresserclientmanager.ui.components.Kicker
import com.northphoenix.hairdresserclientmanager.ui.components.PrimaryButton
import com.northphoenix.hairdresserclientmanager.ui.components.Tag
import com.northphoenix.hairdresserclientmanager.ui.components.TextAction
import com.northphoenix.hairdresserclientmanager.ui.screens.MainNavigation
import com.northphoenix.hairdresserclientmanager.ui.screens.OnboardingScreen
import com.northphoenix.hairdresserclientmanager.ui.theme.HcmTheme
import kotlinx.coroutines.launch

@Composable
fun HcmApp(container: AppContainer) {
    HcmTheme {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            val auth = container.auth

            if (auth == null) {
                MissingConfigScreen()
                return@Box
            }

            val viewModel: AppViewModel = viewModel(factory = AppViewModel.factory(container, auth))
            val zone by viewModel.zone.collectAsStateWithLifecycle()
            val today by viewModel.today.collectAsStateWithLifecycle()
            // The per-app language shows up here as the configuration's locale.
            val locale = LocalConfiguration.current.locales[0]
            val context = LocalContext.current
            // The device's 12/24-hour setting; while it is left to the language, Android answers for the app language.
            // It is read again on every return to the app, since it can only be changed outside it.
            var uses24HourClock by remember(context, locale) { mutableStateOf(DateFormat.is24HourFormat(context)) }
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { uses24HourClock = DateFormat.is24HourFormat(context) }
            val timeFormat = remember(locale, zone, today.year, uses24HourClock) { TimeFormat(locale, zone, today.year, uses24HourClock) }
            val snackbarHostState = remember { SnackbarHostState() }

            LaunchedEffect(viewModel, context) {
                viewModel.notices.collect { notice ->
                    // Replace whatever is showing instead of queueing behind it.
                    snackbarHostState.currentSnackbarData?.dismiss()
                    launch { snackbarHostState.showSnackbar(NoticeVisuals(context.getString(notice.message), notice.message, notice.isError)) }
                }
            }

            // Another locale is another app language. One chosen in Android's settings arrives this way too,
            // while the app is open or on the way back to it, and the view model saves it as the Stylist's choice.
            LaunchedEffect(viewModel, locale) { viewModel.followLanguage() }

            CompositionLocalProvider(
                LocalTimeFormat provides timeFormat,
                LocalSnackbarHostState provides snackbarHostState,
            ) {
                val authState by viewModel.authState.collectAsStateWithLifecycle()

                when (val state = authState) {
                    AuthState.Loading -> LoadingScreen()
                    is AuthState.Failed -> FailureScreen(message = stringResource(state.messageRes()), onRetry = auth::retry)
                    AuthState.SignedOut -> SignedOutContent(viewModel)
                    is AuthState.SignedIn -> SignedInContent(viewModel)
                }
            }
        }
    }
}

/** The provider's sign-in screen. When the session ended on its own, the reason stays above it until someone signs in. */
@Composable
private fun SignedOutContent(viewModel: AppViewModel) {
    val sessionEnded by viewModel.sessionEnded.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        if (sessionEnded) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.error_session_ended),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        viewModel.auth.SignInContent(Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun SignedInContent(viewModel: AppViewModel) {
    val bootstrap by viewModel.bootstrap.collectAsStateWithLifecycle()
    val stylist by viewModel.stylist.collectAsStateWithLifecycle()
    val current = stylist

    when (val state = bootstrap) {
        BootstrapState.Loading -> LoadingScreen()
        is BootstrapState.Failed ->
            FailureScreen(message = stringResource(state.message), onRetry = viewModel::loadStylist, onSignOut = viewModel::signOut)
        BootstrapState.Ready ->
            when {
                current == null -> LoadingScreen()
                current.onboardingComplete -> MainNavigation(viewModel)
                else -> OnboardingScreen(viewModel, current)
            }
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            CircularProgressIndicator(strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun FailureScreen(message: String, onRetry: () -> Unit, onSignOut: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
        EmptyState(icon = Icons.Rounded.CloudOff, title = stringResource(R.string.load_failed_title), body = message) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PrimaryButton(stringResource(R.string.retry), onClick = onRetry)
                if (onSignOut != null) {
                    TextAction(stringResource(R.string.sign_out), onClick = onSignOut)
                }
            }
        }
    }
}

/** Shown when the build has no Clerk publishable key, mirroring the previous app's setup screen. */
@Composable
private fun MissingConfigScreen() {
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
        HcmCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Kicker(stringResource(R.string.app_name))
                Text(stringResource(R.string.missing_env_title), style = MaterialTheme.typography.displaySmall)
                Text(stringResource(R.string.missing_env_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Tag("hcm.clerkPublishableKey", content = MaterialTheme.colorScheme.primary, container = MaterialTheme.colorScheme.primaryContainer)
                Text("apps/android/local.properties", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

