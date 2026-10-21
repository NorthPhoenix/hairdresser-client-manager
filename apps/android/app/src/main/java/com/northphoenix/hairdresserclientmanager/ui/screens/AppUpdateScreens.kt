package com.northphoenix.hairdresserclientmanager.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.data.AppRelease
import com.northphoenix.hairdresserclientmanager.domain.AppUpdates
import com.northphoenix.hairdresserclientmanager.i18n.shownLanguage
import com.northphoenix.hairdresserclientmanager.state.AppViewModel
import com.northphoenix.hairdresserclientmanager.state.LoadState
import com.northphoenix.hairdresserclientmanager.ui.LocalTimeFormat
import com.northphoenix.hairdresserclientmanager.ui.components.HcmCard
import com.northphoenix.hairdresserclientmanager.ui.components.LoadFailed
import com.northphoenix.hairdresserclientmanager.ui.components.PrimaryButton
import com.northphoenix.hairdresserclientmanager.ui.components.ScreenPadding
import com.northphoenix.hairdresserclientmanager.ui.components.SubScreen
import com.northphoenix.hairdresserclientmanager.ui.components.Tag
import com.northphoenix.hairdresserclientmanager.ui.components.TextAction
import com.northphoenix.hairdresserclientmanager.ui.openUri
import com.northphoenix.hairdresserclientmanager.ui.theme.HcmColors
import java.time.LocalDate

/**
 * The one card Home shows about the app itself, at its top: "What's new" after an update, or a
 * newer version to update to. Never a dialog: the Stylist reads it when she wants and goes on.
 */
@Composable
fun AppUpdateHomeCard(card: AppUpdates.HomeCard, viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val language = LocalContext.current.shownLanguage()
    val openDownload = rememberOpenDownload(viewModel)

    when (card) {
        is AppUpdates.HomeCard.WhatsNew -> {
            val (from, to) = AppUpdates.versionSpan(card.releases)

            UpdateCard(
                icon = Icons.Rounded.AutoAwesome,
                title = if (to == null) stringResource(R.string.whats_new_title_one, from) else stringResource(R.string.whats_new_title_range, from, to),
                lines = AppUpdates.lines(card.releases, language),
                modifier = modifier,
            ) {
                TextAction(stringResource(R.string.whats_new_got_it), onClick = viewModel::acknowledgeWhatsNew)
            }
        }
        is AppUpdates.HomeCard.Update -> {
            val offer = card.offer

            UpdateCard(
                icon = Icons.Rounded.SystemUpdate,
                title = stringResource(R.string.update_available_title, offer.versionName),
                lines = AppUpdates.lines(offer.releases, language),
                modifier = modifier,
            ) {
                // Side by side at the end, and "Update" under "Later" when a large font leaves no room.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    TextAction(stringResource(R.string.update_later), onClick = { viewModel.putOffUpdate(offer.versionCode) })
                    offer.downloadUrl?.let { url ->
                        Button(
                            onClick = { openDownload(url) },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = HcmColors.Ink, contentColor = HcmColors.Surface),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.update_download), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

/** A Home card about the app: an icon and a title, the notes collapsed to their first lines, and the [actions] under them. */
@Composable
private fun UpdateCard(
    icon: ImageVector,
    title: String,
    lines: List<String>,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit,
) {
    HcmCard(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f), contentPadding = PaddingValues(start = 18.dp, end = 12.dp, top = 16.dp, bottom = 8.dp)) {
        Row(Modifier.padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        ReleaseNoteLines(lines, collapsible = true, modifier = Modifier.padding(top = 8.dp, end = 6.dp))
        Column(Modifier.fillMaxWidth().padding(top = 4.dp, end = 6.dp, bottom = 6.dp), horizontalAlignment = Alignment.End) {
            actions()
        }
    }
}

/** Notes as a list, one line per thing. [collapsible]: the first lines only, with "more…" that shows the rest. */
@Composable
private fun ReleaseNoteLines(lines: List<String>, collapsible: Boolean, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(lines) { mutableStateOf(false) }
    val (shown, hidden) = if (collapsible) AppUpdates.collapsed(lines, expanded) else lines to 0

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        shown.forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("•", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Text(line, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
        if (hidden > 0) {
            TextAction(stringResource(R.string.release_notes_more), onClick = { expanded = true }, modifier = Modifier.padding(start = 6.dp))
        } else if (collapsible && expanded) {
            TextAction(stringResource(R.string.release_notes_less), onClick = { expanded = false }, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

/** Opens the download address in the browser, where the Stylist then opens the downloaded app as she installed it the first time. */
@Composable
private fun rememberOpenDownload(viewModel: AppViewModel): (String) -> Unit {
    val context = LocalContext.current

    return { url ->
        if (!context.openUri(Uri.parse(url))) {
            viewModel.notify(R.string.link_open_failed, isError = true)
        }
    }
}

/** "What's new" of the Profile tab: every release of the app, newest first, with what it changed. */
@Composable
fun ReleasesScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val read by viewModel.appReleases.collectAsStateWithLifecycle()
    val newer by viewModel.newerApp.collectAsStateWithLifecycle()
    val language = LocalContext.current.shownLanguage()
    val format = LocalTimeFormat.current
    val openDownload = rememberOpenDownload(viewModel)
    val installed = viewModel.appVersionCode

    // Opened while the releases are not there (offline at the start): asked again.
    LaunchedEffect(Unit) {
        if (read.data == null) {
            viewModel.loadAppReleases()
        }
    }

    SubScreen(title = stringResource(R.string.whats_new_entry), onBack = onBack) { padding ->
        val releases = read.data?.releases.orEmpty()

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = padding.calculateTopPadding() + 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "version") {
                Text(stringResource(R.string.app_version, viewModel.appVersionName), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            newer?.let { offer ->
                offer.downloadUrl?.let { url ->
                    item(key = "update") {
                        PrimaryButton(
                            stringResource(R.string.releases_update_to, offer.versionName),
                            onClick = { openDownload(url) },
                            modifier = Modifier.fillMaxWidth(),
                            icon = Icons.Rounded.SystemUpdate,
                        )
                    }
                }
            }
            when (val state = read.state) {
                is LoadState.Failed -> if (releases.isEmpty()) item(key = "failed") { LoadFailed(state.message, onRetry = viewModel::loadAppReleases) }
                LoadState.Loading -> if (releases.isEmpty()) item(key = "loading") { LoadingRow() }
                else -> Unit
            }
            items(releases, key = { it.versionCode }) { release ->
                ReleaseCard(release, AppUpdates.notes(release, language), installed, date = release.date.toLocalDateOrNull()?.let(format::shortDate))
            }
        }
    }
}

@Composable
private fun ReleaseCard(release: AppRelease, lines: List<String>, installed: Int, date: String?) {
    HcmCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(release.versionName, style = MaterialTheme.typography.titleLarge)
                date?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            when {
                release.versionCode == installed -> Tag(stringResource(R.string.releases_installed))
                release.versionCode > installed -> Tag(
                    stringResource(R.string.releases_new),
                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                    container = MaterialTheme.colorScheme.primaryContainer,
                )
            }
        }
        ReleaseNoteLines(lines, collapsible = false, modifier = Modifier.padding(top = 10.dp))
    }
}

private fun String.toLocalDateOrNull(): LocalDate? = runCatching { LocalDate.parse(this) }.getOrNull()
