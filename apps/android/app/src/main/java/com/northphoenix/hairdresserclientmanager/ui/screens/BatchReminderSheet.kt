package com.northphoenix.hairdresserclientmanager.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.DeviceSms
import com.northphoenix.hairdresserclientmanager.domain.BatchReminders
import com.northphoenix.hairdresserclientmanager.domain.PhoneFormat
import com.northphoenix.hairdresserclientmanager.domain.ReminderLog
import com.northphoenix.hairdresserclientmanager.domain.ReminderMessage
import com.northphoenix.hairdresserclientmanager.state.AppViewModel
import com.northphoenix.hairdresserclientmanager.state.SendState
import com.northphoenix.hairdresserclientmanager.state.SmsFailure
import com.northphoenix.hairdresserclientmanager.ui.LocalTimeFormat
import com.northphoenix.hairdresserclientmanager.ui.clientReminderMessage
import com.northphoenix.hairdresserclientmanager.ui.components.ConfirmDialog
import com.northphoenix.hairdresserclientmanager.ui.components.HcmTallBottomSheet
import com.northphoenix.hairdresserclientmanager.ui.components.PrimaryButton
import com.northphoenix.hairdresserclientmanager.ui.components.TextAction
import com.northphoenix.hairdresserclientmanager.ui.components.rememberNow
import com.northphoenix.hairdresserclientmanager.ui.openInMessages
import com.northphoenix.hairdresserclientmanager.ui.theme.HcmColors

/**
 * 'Remind all': one row per remindable Appointment of [appointments] (tomorrow's), with the Client the
 * reminder goes to, the number, the language and the exact text, and a checkbox. Those already reminded
 * for their current time start unchecked. "Send N SMS" sends the checked ones from the phone (asking for
 * the SMS permission at that moment); without the permission, or a way to send, each one is opened in the
 * SMS app in turn instead. Each row then shows how its SMS went, and a failed one can be retried.
 */
@Composable
fun BatchReminderSheet(viewModel: AppViewModel, appointments: List<Appointment>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val format = LocalTimeFormat.current
    val outbox = viewModel.reminders
    val log by outbox.log.collectAsStateWithLifecycle()
    val sends by outbox.sends.collectAsStateWithLifecycle()
    val now by rememberNow()

    // The results of an earlier batch are not this one's, unless it is still going.
    remember { outbox.reset() }

    val messages = remember(appointments, format.zone) {
        appointments.mapNotNull { clientReminderMessage(context, it, format.zone) }
    }
    // Reminded ones start unchecked. What the Stylist checks is kept across a rotation, and not changed by the log growing as the batch goes.
    var checked by rememberSaveable {
        mutableStateOf(BatchReminders.initiallyChecked(appointments, log).toList())
    }
    var fallback by rememberSaveable { mutableStateOf(false) }
    var askingPermission by rememberSaveable { mutableStateOf(false) }

    val selected = messages.filter { it.appointmentId in checked }
    val started = messages.any { sends[it.appointmentId] != null }
    val sending = messages.any { sends[it.appointmentId].let { state -> state == SendState.Waiting || state == SendState.Sending } }
    val sentCount = selected.count { sends[it.appointmentId] == SendState.Sent }
    // In the SMS app, the ones not opened yet.
    val toOpen = selected.filter { sends[it.appointmentId] == null }

    fun openNext() {
        val next = toOpen.firstOrNull() ?: return

        if (openInMessages(context, next)) {
            outbox.opened(next)
        } else {
            viewModel.notify(R.string.sms_app_unavailable, isError = true)
        }
    }

    fun startSending() {
        when {
            !DeviceSms.isAvailable(context) -> {
                fallback = true
                openNext()
            }
            DeviceSms.hasPermission(context) -> outbox.send(selected)
            else -> askingPermission = true
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            outbox.send(selected)
        } else {
            fallback = true
            openNext()
        }
    }

    if (askingPermission) {
        ConfirmDialog(
            title = stringResource(R.string.batch_reminders_permission_title),
            body = stringResource(R.string.batch_reminders_permission_body),
            confirmText = stringResource(R.string.batch_reminders_permission_continue),
            onConfirm = {
                askingPermission = false
                permission.launch(Manifest.permission.SEND_SMS)
            },
            onDismiss = {
                askingPermission = false
                fallback = true
                openNext()
            },
            dismissText = stringResource(R.string.compose_client_reminder_in_messages),
        )
    }

    HcmTallBottomSheet(
        title = stringResource(R.string.batch_reminders_title),
        onDismiss = onDismiss,
        fillHeight = false,
        bottomBar = {
            if (started && !fallback && !sending) {
                Text(
                    stringResource(R.string.batch_reminders_summary, sentCount, selected.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                // Nothing is to be sent or opened any more.
                started && !sending && (!fallback || toOpen.isEmpty()) -> PrimaryButton(stringResource(R.string.batch_reminders_done), onClick = onDismiss, modifier = Modifier.fillMaxWidth())
                sending -> PrimaryButton(stringResource(R.string.batch_reminders_sending), onClick = {}, modifier = Modifier.fillMaxWidth(), loading = true)
                fallback -> PrimaryButton(
                    stringResource(R.string.batch_reminders_open_messages, toOpen.size),
                    onClick = ::openNext,
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Rounded.Sms,
                )
                else -> PrimaryButton(
                    if (selected.isEmpty()) stringResource(R.string.batch_reminders_none_checked) else stringResource(R.string.batch_reminders_send, selected.size),
                    onClick = ::startSending,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = selected.isNotEmpty(),
                    icon = Icons.Rounded.Sms,
                )
            }
        },
    ) {
        messages.forEach { message ->
            val state = sends[message.appointmentId]
            val remindedAt = appointments.firstOrNull { it.id == message.appointmentId }?.let { ReminderLog.remindedAt(log, it) }

            ReminderRow(
                message = message,
                checked = message.appointmentId in checked,
                // Fixed once a batch has begun: its results are what the row shows.
                enabled = !started,
                state = state,
                remindedNote = remindedAt?.takeIf { state == null }?.let { stringResource(R.string.reminded_at, format.stamp(it, now)) },
                onToggle = { on -> checked = if (on) checked + message.appointmentId else checked - message.appointmentId },
                onRetry = { outbox.send(listOf(message)) },
            )
        }
        Text(
            stringResource(if (fallback) R.string.batch_reminders_fallback_note else R.string.batch_reminders_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReminderRow(
    message: ReminderMessage,
    checked: Boolean,
    enabled: Boolean,
    state: SendState?,
    remindedNote: String?,
    onToggle: (Boolean) -> Unit,
    onRetry: () -> Unit,
) {
    val description = stringResource(R.string.batch_reminders_check_description, message.recipientName)
    // A row left out of a batch that has begun stays as it was.
    val left = !checked && state == null

    Column(Modifier.fillMaxWidth().alpha(if (!enabled && left) 0.5f else 1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onToggle)
                .semantics { contentDescription = description },
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(top = 2.dp))
            Column(Modifier.weight(1f).padding(top = 10.dp)) {
                Text(message.recipientName, style = MaterialTheme.typography.titleMedium)
                Text(
                    PhoneFormat.display(message.phone) + " · " + message.language.tag.uppercase(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                remindedNote?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = HcmColors.Success) }
            }
        }
        Text(
            message.text,
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (state != null) {
            SendResult(state, onRetry)
        }
    }
}

/** How the SMS of a row went: waiting, on its way, sent, opened in the SMS app, or failed with the reason and a retry. */
@Composable
private fun SendResult(state: SendState, onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state) {
            SendState.Waiting -> Text(stringResource(R.string.batch_reminder_waiting), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SendState.Sending -> {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.batch_reminder_sending), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SendState.Sent, SendState.Opened -> {
                Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp), tint = HcmColors.Success)
                Text(
                    stringResource(if (state == SendState.Sent) R.string.batch_reminder_sent else R.string.batch_reminder_opened),
                    style = MaterialTheme.typography.labelLarge,
                    color = HcmColors.Success,
                )
            }
            is SendState.Failed -> {
                Icon(Icons.Rounded.ErrorOutline, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                Text(
                    stringResource(failureText(state.reason)),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                TextAction(stringResource(R.string.retry), onClick = onRetry)
            }
        }
    }
}

private fun failureText(reason: SmsFailure): Int = when (reason) {
    SmsFailure.AIRPLANE_MODE -> R.string.batch_reminder_failed_airplane
    SmsFailure.NO_SERVICE -> R.string.batch_reminder_failed_no_service
    SmsFailure.NO_PERMISSION -> R.string.batch_reminder_failed_no_permission
    SmsFailure.TIMEOUT -> R.string.batch_reminder_failed_timeout
    SmsFailure.OTHER -> R.string.batch_reminder_failed
}
