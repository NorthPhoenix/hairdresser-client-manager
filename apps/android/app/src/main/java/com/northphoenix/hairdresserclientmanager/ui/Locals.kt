package com.northphoenix.hairdresserclientmanager.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.northphoenix.hairdresserclientmanager.domain.TimeFormat
import com.northphoenix.hairdresserclientmanager.ui.theme.HcmColors
import java.time.ZoneId
import java.util.Locale

val LocalTimeFormat = staticCompositionLocalOf { TimeFormat(Locale.getDefault(), ZoneId.systemDefault()) }

val LocalSnackbarHostState = staticCompositionLocalOf { SnackbarHostState() }

/** [message] is the text when the notice appeared. On screen it is [messageRes], so a notice follows a change of the app language. */
class NoticeVisuals(override val message: String, @StringRes val messageRes: Int, val isError: Boolean) : SnackbarVisuals {
    override val actionLabel: String? = null
    override val withDismissAction: Boolean = false
    override val duration = if (isError) androidx.compose.material3.SnackbarDuration.Long else androidx.compose.material3.SnackbarDuration.Short
}

/** Every screen's Scaffold uses this so notices sit above that screen's own bottom bar. */
@Composable
fun HcmSnackbarHost(modifier: Modifier = Modifier) {
    SnackbarHost(LocalSnackbarHostState.current, modifier) { data ->
        val notice = data.visuals as? NoticeVisuals
        val isError = notice?.isError == true

        Snackbar(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            shape = MaterialTheme.shapes.medium,
            containerColor = if (isError) HcmColors.Danger else HcmColors.Ink,
            contentColor = HcmColors.Surface,
        ) {
            Text(notice?.let { stringResource(it.messageRes) } ?: data.visuals.message, style = MaterialTheme.typography.bodyMedium)
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
