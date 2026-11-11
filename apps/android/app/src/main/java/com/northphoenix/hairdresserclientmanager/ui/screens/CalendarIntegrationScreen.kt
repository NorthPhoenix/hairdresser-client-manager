package com.northphoenix.hairdresserclientmanager.ui.screens

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.data.DeviceCalendar
import com.northphoenix.hairdresserclientmanager.domain.CalendarAccess
import com.northphoenix.hairdresserclientmanager.domain.CalendarSetup
import com.northphoenix.hairdresserclientmanager.domain.CalendarSetupStep
import com.northphoenix.hairdresserclientmanager.domain.PhoneCalendar
import com.northphoenix.hairdresserclientmanager.domain.PhoneSync
import com.northphoenix.hairdresserclientmanager.state.AppViewModel
import com.northphoenix.hairdresserclientmanager.state.CalendarConnection
import com.northphoenix.hairdresserclientmanager.state.CalendarMirrorState
import com.northphoenix.hairdresserclientmanager.state.CalendarProblem
import com.northphoenix.hairdresserclientmanager.state.PhoneFacts
import com.northphoenix.hairdresserclientmanager.ui.LocalTimeFormat
import com.northphoenix.hairdresserclientmanager.ui.components.ConfirmDialog
import com.northphoenix.hairdresserclientmanager.ui.components.HcmCard
import com.northphoenix.hairdresserclientmanager.ui.components.HcmListBottomSheet
import com.northphoenix.hairdresserclientmanager.ui.components.PrimaryButton
import com.northphoenix.hairdresserclientmanager.ui.components.ScreenColumn
import com.northphoenix.hairdresserclientmanager.ui.components.SecondaryButton
import com.northphoenix.hairdresserclientmanager.ui.components.SubScreen
import com.northphoenix.hairdresserclientmanager.ui.components.TextAction
import com.northphoenix.hairdresserclientmanager.ui.components.rememberNow
import kotlinx.coroutines.delay

/**
 * Calendar Integration (ADR 0003): the setup, one step at a time, and once it is connected, how
 * the mirroring goes. The setup looks at the phone itself after each thing the Stylist does
 * there, and again every few seconds while it waits for something to happen outside the app (a
 * calendar made on calendar.google.com coming to the phone), so it always shows the one step that
 * is left and what to do for it.
 */
@Composable
fun CalendarIntegrationScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val state by viewModel.calendarSync.state.collectAsStateWithLifecycle()

    SubScreen(title = stringResource(R.string.calendar_integration_title), onBack = onBack) { padding ->
        ScreenColumn(padding) {
            Text(stringResource(R.string.calendar_integration_intro), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

            val connection = state.connection

            if (connection != null) {
                Connected(viewModel, state, connection)
            } else {
                Setup(viewModel)
            }
        }
    }
}

/** Asks for calendar access and says, after Android has answered, when only Android's settings can give it now. */
private class AccessAsk(val launch: () -> Unit, val blocked: Boolean)

@Composable
private fun rememberAccessAsk(onAnswer: (Boolean) -> Unit): AccessAsk {
    val context = LocalContext.current
    // Android shows its question at most twice; after that "Allow" can only lead to the settings.
    var blocked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result.values.isNotEmpty() && result.values.all { it }

        blocked = !granted && context.activity()?.let { activity -> DeviceCalendar.PERMISSIONS.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) } } == true
        onAnswer(granted)
    }

    return AccessAsk(launch = { launcher.launch(DeviceCalendar.PERMISSIONS) }, blocked = blocked)
}

@Composable
private fun Setup(viewModel: AppViewModel) {
    val sync = viewModel.calendarSync
    val context = LocalContext.current
    var facts by remember { mutableStateOf<PhoneFacts?>(null) }
    var looks by remember { mutableIntStateOf(0) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    val access = rememberAccessAsk { looks++ }

    // Back from Android's settings, from adding an account, or from calendar.google.com: look again.
    LifecycleResumeEffect(Unit) {
        looks++
        onPauseOrDispose { }
    }
    LaunchedEffect(looks) { facts = sync.phoneFacts() }

    val phone = facts ?: return
    val accessState = when {
        phone.access -> CalendarAccess.GRANTED
        access.blocked -> CalendarAccess.BLOCKED
        else -> CalendarAccess.ASKABLE
    }
    val step = CalendarSetup.step(accessState, phone.calendars, phone.sync)
    val accounts = phone.calendars.map { it.accountName }.distinct()
    // Waiting for what happens outside the app: a Google account added, the calendar made on
    // calendar.google.com coming to the phone. Android is asked to sync now and then, so it comes sooner.
    val watching = step is CalendarSetupStep.NoGoogleAccount || (step is CalendarSetupStep.Calendar && step.existing == null)

    LaunchedEffect(watching) {
        while (watching) {
            delay(3_000)
            looks++
        }
    }
    LaunchedEffect(watching, accounts) {
        while (watching) {
            sync.askForSync(accounts)
            delay(20_000)
        }
    }

    val ownName = stringResource(R.string.calendar_own_name)

    StepCard(number = 1, title = stringResource(if (phone.access) R.string.calendar_step_access_done else R.string.calendar_step_access_title), done = phone.access) {
        if (!phone.access) {
            Body(stringResource(R.string.calendar_step_access_body))
            if (accessState == CalendarAccess.BLOCKED) {
                Body(stringResource(R.string.calendar_step_access_blocked))
                PrimaryButton(stringResource(R.string.calendar_open_app_settings), onClick = { context.openAppSettings() }, modifier = Modifier.fillMaxWidth())
            } else {
                PrimaryButton(stringResource(R.string.calendar_step_access_action), onClick = access.launch, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    StepCard(number = 2, title = calendarStepTitle(step, ownName), done = false, active = phone.access) {
        when (step) {
            is CalendarSetupStep.Access -> Body(stringResource(R.string.calendar_step_calendar_body))
            is CalendarSetupStep.NoGoogleAccount -> {
                Body(stringResource(R.string.calendar_step_account_body))
                PrimaryButton(stringResource(R.string.calendar_step_account_action), onClick = { context.openAddGoogleAccount() }, modifier = Modifier.fillMaxWidth())
                SyncOff(step.sync)
            }
            is CalendarSetupStep.Calendar -> {
                val existing = step.existing

                if (existing != null) {
                    Body(stringResource(R.string.calendar_existing_found, existing.name, existing.accountName))
                    PrimaryButton(stringResource(R.string.calendar_use_existing), onClick = { sync.connect(existing) }, modifier = Modifier.fillMaxWidth())
                } else {
                    Body(stringResource(R.string.calendar_step_calendar_body))
                    Body(stringResource(R.string.calendar_create_where))
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.calendar_create_steps, accounts.joinToString(", "), ownName),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        )
                    }
                    if (step.sync.off) {
                        SyncOff(step.sync)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(Modifier.padding(top = 2.dp).size(16.dp), strokeWidth = 2.dp)
                            Body(stringResource(R.string.calendar_create_watching, ownName))
                        }
                    }
                }
                TextAction(stringResource(R.string.calendar_choose_other), onClick = { choosing = true }, modifier = Modifier.align(Alignment.End))
            }
        }
    }

    if (choosing) {
        val choices = remember(phone) { CalendarSetup.choices(phone.calendars) }

        HcmListBottomSheet(
            title = stringResource(R.string.calendar_choose_title),
            onDismiss = { choosing = false },
            content = {
                if (choices.isEmpty()) {
                    Body(stringResource(R.string.calendar_choose_none))
                }
            },
        ) {
            items(choices, key = { it.id }) { calendar ->
                CalendarChoice(calendar) {
                    choosing = false
                    sync.connect(calendar)
                }
            }
        }
    }
}

@Composable
private fun calendarStepTitle(step: CalendarSetupStep, ownName: String): String =
    when (step) {
        is CalendarSetupStep.NoGoogleAccount -> stringResource(R.string.calendar_step_account_title)
        // One that is there already is used, not made.
        is CalendarSetupStep.Calendar -> step.existing?.let { stringResource(R.string.calendar_step_calendar_found_title, it.name) } ?: stringResource(R.string.calendar_step_calendar_title, ownName)
        is CalendarSetupStep.Access -> stringResource(R.string.calendar_step_calendar_title, ownName)
    }

/** Calendar sync is off on the phone, which keeps a calendar made on calendar.google.com from coming to it. Nothing when it is on. */
@Composable
private fun SyncOff(sync: PhoneSync) {
    if (!sync.off) {
        return
    }

    val context = LocalContext.current

    Problem(stringResource(R.string.calendar_sync_off))
    SecondaryButton(stringResource(R.string.calendar_open_sync_settings), onClick = { context.openSyncSettings() }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun CalendarChoice(calendar: PhoneCalendar, onClick: () -> Unit) {
    HcmCard(onClick = onClick, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CalendarMonth, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(calendar.name, style = MaterialTheme.typography.titleMedium)
                if (calendar.accountName != calendar.name) {
                    Text(calendar.accountName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Connected(viewModel: AppViewModel, state: CalendarMirrorState, connection: CalendarConnection) {
    val sync = viewModel.calendarSync
    val context = LocalContext.current
    val format = LocalTimeFormat.current
    val now by rememberNow()
    var confirmDisconnect by rememberSaveable { mutableStateOf(false) }
    val access = rememberAccessAsk { granted -> if (granted) sync.syncNow() }

    // Back from Android's settings: what was wrong may be right now.
    LifecycleResumeEffect(Unit) {
        sync.syncNow()
        onPauseOrDispose { }
    }

    HcmCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.CalendarMonth, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.calendar_entry_on, connection.calendarName), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.calendar_connected_account, connection.accountName), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Body(stringResource(R.string.calendar_connected_since, format.mediumDate(connection.connectedAt.atZone(format.zone).toLocalDate())))

            when {
                state.running -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Body(stringResource(R.string.calendar_syncing))
                }
                // What was mirrored into a calendar that is gone is not in any calendar now.
                state.lastSyncedAt != null && state.problem != CalendarProblem.CALENDAR_GONE -> Body(
                    stringResource(R.string.calendar_last_sync, format.stamp(state.lastSyncedAt, now)) + " · " +
                        pluralStringResource(R.plurals.calendar_mirrored_count, state.mirrored, state.mirrored),
                )
            }

            when (state.problem) {
                CalendarProblem.NO_ACCESS -> {
                    Problem(stringResource(R.string.calendar_problem_no_access))
                    if (access.blocked) {
                        Body(stringResource(R.string.calendar_step_access_blocked))
                        PrimaryButton(stringResource(R.string.calendar_open_app_settings), onClick = { context.openAppSettings() }, modifier = Modifier.fillMaxWidth())
                    } else {
                        PrimaryButton(stringResource(R.string.calendar_step_access_action), onClick = access.launch, modifier = Modifier.fillMaxWidth())
                    }
                }
                CalendarProblem.CALENDAR_GONE -> {
                    Problem(stringResource(R.string.calendar_problem_gone, connection.calendarName))
                    PrimaryButton(stringResource(R.string.calendar_set_up_again), onClick = sync::disconnect, modifier = Modifier.fillMaxWidth())
                }
                CalendarProblem.SERVER -> Problem(stringResource(R.string.calendar_problem_server))
                CalendarProblem.PHONE -> Problem(stringResource(R.string.calendar_problem_phone))
                null -> Unit
            }

            if (state.problem?.needsStylist != true) {
                SecondaryButton(stringResource(R.string.calendar_sync_now), onClick = sync::syncNow, modifier = Modifier.fillMaxWidth(), loading = state.running)
            }
        }
    }

    HcmCard(color = MaterialTheme.colorScheme.surfaceContainer) {
        Body(stringResource(R.string.calendar_how_it_works))
    }

    TextAction(stringResource(R.string.calendar_disconnect), onClick = { confirmDisconnect = true })

    if (confirmDisconnect) {
        ConfirmDialog(
            title = stringResource(R.string.calendar_disconnect_title),
            body = stringResource(R.string.calendar_disconnect_body, connection.calendarName),
            confirmText = stringResource(R.string.calendar_disconnect_confirm),
            danger = true,
            onConfirm = {
                confirmDisconnect = false
                sync.disconnect()
            },
            onDismiss = { confirmDisconnect = false },
        )
    }
}

/** One step of the setup: its number, or a check mark once it is done. A step that is not [active] yet is shown faded, with what it will be. */
@Composable
private fun StepCard(number: Int, title: String, done: Boolean, active: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val faded = !active && !done

    HcmCard(color = if (faded) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(30.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (done) {
                            Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                        } else {
                            Text(number.toString(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (faded) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            if (!done) {
                content()
            }
        }
    }
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Problem(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp).padding(top = 1.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
}

/**
 * The card on the Profile tab that opens the Calendar Integration screen, with how it is now:
 * not set up, which calendar the Appointments are in, or that it needs the Stylist.
 */
@Composable
fun CalendarIntegrationEntry(state: CalendarMirrorState, onClick: () -> Unit) {
    val connection = state.connection
    val attention = state.problem?.needsStylist == true

    HcmCard(onClick = onClick, contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.CalendarMonth, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.calendar_integration_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    when {
                        attention -> stringResource(R.string.calendar_entry_attention)
                        connection != null -> stringResource(R.string.calendar_entry_on, connection.calendarName)
                        else -> stringResource(R.string.calendar_entry_off)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

/** Home's card when the calendar has stopped being updated for a reason the Stylist can put right. Nothing otherwise. */
@Composable
fun CalendarProblemHomeCard(state: CalendarMirrorState, onFix: () -> Unit, modifier: Modifier = Modifier) {
    if (state.connection == null || state.problem?.needsStylist != true) {
        return
    }

    HcmCard(modifier = modifier, onClick = onFix, borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.5f)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.calendar_home_problem_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    if (state.problem == CalendarProblem.NO_ACCESS) {
                        stringResource(R.string.calendar_home_cause_no_access)
                    } else {
                        stringResource(R.string.calendar_home_cause_gone, state.connection.calendarName)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Under the text, which keeps the width of the card at every font size.
                TextAction(stringResource(R.string.calendar_home_problem_action), onClick = onFix, modifier = Modifier.align(Alignment.End))
            }
        }
    }
}

private fun Context.activity(): Activity? {
    var context = this

    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }

    return null
}

private fun Context.startOrNothing(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // A phone without that settings screen. The screen still says what to do.
    }
}

private fun Context.openAppSettings() = startOrNothing(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))

private fun Context.openAddGoogleAccount() = startOrNothing(Intent(Settings.ACTION_ADD_ACCOUNT).putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google")))

private fun Context.openSyncSettings() = startOrNothing(Intent(Settings.ACTION_SYNC_SETTINGS))
