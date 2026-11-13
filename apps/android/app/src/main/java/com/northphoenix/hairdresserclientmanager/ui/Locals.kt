package com.northphoenix.hairdresserclientmanager.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.domain.TimeFormat
import com.northphoenix.hairdresserclientmanager.state.Read
import com.northphoenix.hairdresserclientmanager.ui.theme.HcmColors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

val LocalTimeFormat = staticCompositionLocalOf { TimeFormat(Locale.getDefault(), ZoneId.systemDefault()) }

val LocalSnackbarHostState = staticCompositionLocalOf { SnackbarHostState() }

/**
 * What is booked, for where a day is picked for an Appointment: the date picker marks the days of
 * its month that have an Appointment, and the form that books or moves one lists what the chosen
 * day already has. The app reads both as the Calendar does ([com.northphoenix.hairdresserclientmanager.state.AppViewModel.calendarMonthMarks],
 * [com.northphoenix.hairdresserclientmanager.state.AppViewModel.appointmentsOn]); outside it nothing is booked.
 */
interface Bookings {
    fun markedDays(month: YearMonth, firstDayOfWeek: DayOfWeek): Flow<Set<LocalDate>>

    fun on(day: LocalDate): Flow<Read<List<Appointment>>>

    object None : Bookings {
        override fun markedDays(month: YearMonth, firstDayOfWeek: DayOfWeek): Flow<Set<LocalDate>> = flowOf(emptySet())

        override fun on(day: LocalDate): Flow<Read<List<Appointment>>> = flowOf(Read())
    }
}

val LocalBookings = staticCompositionLocalOf<Bookings> { Bookings.None }

/**
 * [message] is the text when the notice appeared. On screen it is [messageRes] filled with [args], so a notice follows a change of the app language.
 * With [quantity], [messageRes] is a `plurals` resource. [actionRes] names the notice's button, if it has one; [actionLabel] is its text when the notice appeared.
 */
class NoticeVisuals(
    override val message: String,
    @StringRes val messageRes: Int,
    val isError: Boolean,
    val args: List<Any> = emptyList(),
    val quantity: Int? = null,
    @StringRes val actionRes: Int? = null,
    override val actionLabel: String? = null,
) : SnackbarVisuals {
    override val withDismissAction: Boolean = false

    // A notice with a button stays longer, so there is time to reach it.
    override val duration = if (isError || actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short
}

/** A notice's text. Only a text that was given arguments is formatted: any other is shown exactly as it is written. */
fun Context.noticeText(@StringRes message: Int, args: List<Any>, quantity: Int? = null): String =
    when {
        quantity != null -> resources.getQuantityString(message, quantity, *args.toTypedArray())
        args.isEmpty() -> getString(message)
        else -> getString(message, *args.toTypedArray())
    }

/** Every screen's Scaffold uses this so notices sit above that screen's own bottom bar. */
@Composable
fun HcmSnackbarHost(modifier: Modifier = Modifier) {
    SnackbarHost(LocalSnackbarHostState.current, modifier) { data ->
        val notice = data.visuals as? NoticeVisuals
        val isError = notice?.isError == true
        val action = notice?.actionRes

        Snackbar(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            shape = MaterialTheme.shapes.medium,
            containerColor = if (isError) HcmColors.Danger else HcmColors.Ink,
            contentColor = HcmColors.Surface,
            action = action?.let {
                {
                    TextButton(onClick = data::performAction, colors = ButtonDefaults.textButtonColors(contentColor = HcmColors.Surface)) {
                        Text(stringResource(it), style = MaterialTheme.typography.labelLarge)
                    }
                }
            },
        ) {
            val text = when {
                notice == null -> data.visuals.message
                notice.quantity != null -> pluralStringResource(notice.messageRes, notice.quantity, *notice.args.toTypedArray())
                notice.args.isEmpty() -> stringResource(notice.messageRes)
                else -> stringResource(notice.messageRes, *notice.args.toTypedArray())
            }

            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Opens a link in whatever app handles it. Returns false when nothing can. */
fun Context.openUri(uri: Uri): Boolean =
    try {
        startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
