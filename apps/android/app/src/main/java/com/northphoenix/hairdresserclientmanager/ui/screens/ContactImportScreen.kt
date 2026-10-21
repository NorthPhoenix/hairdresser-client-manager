package com.northphoenix.hairdresserclientmanager.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.data.DeviceContacts
import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.domain.ContactImportList
import com.northphoenix.hairdresserclientmanager.domain.ImportCandidate
import com.northphoenix.hairdresserclientmanager.domain.ImportItem
import com.northphoenix.hairdresserclientmanager.domain.ImportRow
import com.northphoenix.hairdresserclientmanager.state.AppViewModel
import com.northphoenix.hairdresserclientmanager.ui.components.Avatar
import com.northphoenix.hairdresserclientmanager.ui.components.BottomActionBar
import com.northphoenix.hairdresserclientmanager.ui.components.EmptyState
import com.northphoenix.hairdresserclientmanager.ui.components.HcmTextField
import com.northphoenix.hairdresserclientmanager.ui.components.PrimaryButton
import com.northphoenix.hairdresserclientmanager.ui.components.ScreenPadding
import com.northphoenix.hairdresserclientmanager.ui.components.SecondaryButton
import com.northphoenix.hairdresserclientmanager.ui.components.SubScreen

/** What the screen has of the device's contacts: still being read, or read (null: Android refused). */
private sealed interface ContactsRead {
    data object Loading : ContactsRead

    data class Done(val candidates: List<ImportCandidate>?) : ContactsRead
}

/**
 * Import of several contacts at once: the device's contacts that have a phone number, to tick and add as Clients.
 * Asks for the permission to read contacts when it opens.
 */
@Composable
fun ContactImportScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val clients = viewModel.clients.collectAsStateWithLifecycle().value.data.orEmpty()
    val stylist by viewModel.stylist.collectAsStateWithLifecycle()
    val progress by viewModel.contactImportProgress.collectAsStateWithLifecycle()
    val running = progress != null

    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(hasPermission()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        granted = result
        asked = true
    }

    // Asked once, when the screen opens. Coming back from the settings looks again.
    LaunchedEffect(Unit) {
        if (!granted && !asked) {
            permission.launch(Manifest.permission.READ_CONTACTS)
        }
    }
    LifecycleStartEffect(Unit) {
        granted = hasPermission()
        onStopOrDispose {}
    }

    var read by remember { mutableStateOf<ContactsRead>(ContactsRead.Loading) }

    LaunchedEffect(granted) {
        if (granted) {
            read = ContactsRead.Loading
            read = ContactsRead.Done(DeviceContacts.readAll(context)?.let(ContactImportList::candidates))
        }
    }

    val cyrillicFirst = (stylist?.language ?: Language.RU) == Language.RU
    val rows = remember(read, clients, cyrillicFirst) {
        ((read as? ContactsRead.Done)?.candidates).orEmpty().let { candidates ->
            ContactImportList.rows(candidates, clients.map { it.phone }, cyrillicFirst)
        }
    }
    val selectable = remember(rows) { ContactImportList.selectable(rows) }

    var search by rememberSaveable { mutableStateOf("") }
    val shown = remember(rows, search) { ContactImportList.filter(rows, search) }
    val items = remember(shown) { ContactImportList.items(shown) }

    var picked by rememberSaveable(stateSaver = listSaver(save = { it.toList() }, restore = { it.toSet() })) { mutableStateOf(emptySet<Long>()) }
    // A contact who became a Client meanwhile cannot be picked any more.
    val selected = remember(picked, selectable) { picked.filterTo(HashSet()) { it in selectable } }
    val shownSelectable = remember(shown) { shown.filterNot { it.alreadyClient }.map { it.candidate.id } }
    val allShownSelected by remember(shownSelectable, selected) { derivedStateOf { shownSelectable.isNotEmpty() && selected.containsAll(shownSelectable) } }

    // What the last try left undone: the contacts stay selected, and the screen says why.
    var failedCount by rememberSaveable { mutableStateOf(0) }
    var failureReason by rememberSaveable { mutableStateOf(0) }

    fun add() {
        val chosen = rows.filter { it.candidate.id in selected }.map { it.candidate }

        failedCount = 0
        viewModel.importContacts(chosen) { result ->
            if (result.failed.isEmpty()) {
                onBack()
            } else {
                picked = result.failed
                failedCount = result.failed.size
                failureReason = result.reason ?: 0
            }
        }
    }

    SubScreen(
        title = stringResource(R.string.import_contact),
        onBack = onBack,
        bottomBar = {
            if (granted && rows.isNotEmpty()) {
                BottomActionBar {
                    val current = progress

                    if (current != null) {
                        LinearProgressIndicator(
                            progress = { if (current.second == 0) 0f else current.first.toFloat() / current.second },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    PrimaryButton(
                        text = when {
                            current != null -> stringResource(R.string.contact_import_progress, current.first, current.second)
                            selected.isEmpty() -> stringResource(R.string.contact_import_pick)
                            else -> pluralStringResource(R.plurals.contact_import_add, selected.size, selected.size)
                        },
                        onClick = ::add,
                        enabled = selected.isNotEmpty() && !running,
                        loading = running,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                !granted -> NoPermission(
                    onAllow = { permission.launch(Manifest.permission.READ_CONTACTS) },
                    onSettings = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                    },
                )
                read is ContactsRead.Loading -> LoadingRow()
                rows.isEmpty() -> EmptyState(
                    Icons.Rounded.Contacts,
                    stringResource(R.string.contact_import_empty_title),
                    body = stringResource(R.string.contact_import_empty_body),
                )
                else -> {
                    HcmTextField(
                        value = search,
                        onValueChange = { search = it },
                        label = stringResource(R.string.contact_import_search),
                        labelVisible = false,
                        placeholder = stringResource(R.string.contact_import_search),
                        leadingIcon = Icons.Rounded.Search,
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Search,
                        trailing = if (search.isNotEmpty()) {
                            { IconButton(onClick = { search = "" }) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.clear)) } }
                        } else {
                            null
                        },
                        modifier = Modifier.padding(horizontal = ScreenPadding).padding(top = 4.dp),
                    )

                    if (failedCount > 0) {
                        Column(Modifier.padding(horizontal = ScreenPadding).padding(top = 10.dp)) {
                            Text(stringResource(R.string.contact_import_failed_banner, failedCount), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            if (failureReason != 0) {
                                Text(stringResource(failureReason), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth().padding(start = ScreenPadding, end = 4.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            stringResource(R.string.contact_import_selected, selected.size),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (shownSelectable.isNotEmpty()) {
                            TextButton(
                                onClick = { picked = if (allShownSelected) selected - shownSelectable.toSet() else selected + shownSelectable },
                                enabled = !running,
                            ) {
                                Text(stringResource(if (allShownSelected) R.string.contact_import_unselect_all else R.string.contact_import_select_all))
                            }
                        }
                    }

                    if (shown.isEmpty()) {
                        EmptyState(Icons.Rounded.SearchOff, stringResource(R.string.no_client_results))
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                            items(
                                items,
                                key = { item ->
                                    when (item) {
                                        is ImportItem.Header -> "h:${item.letter}"
                                        is ImportItem.Entry -> "c:${item.row.candidate.id}"
                                    }
                                },
                                contentType = { it::class },
                            ) { item ->
                                when (item) {
                                    is ImportItem.Header -> Text(
                                        item.letter,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(start = ScreenPadding, end = ScreenPadding, top = 14.dp, bottom = 2.dp),
                                    )
                                    is ImportItem.Entry -> ContactRow(
                                        row = item.row,
                                        checked = item.row.candidate.id in selected,
                                        enabled = !running,
                                        onToggle = { on ->
                                            val id = item.row.candidate.id
                                            picked = if (on) selected + id else selected - id
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoPermission(onAllow: () -> Unit, onSettings: () -> Unit) {
    EmptyState(
        Icons.Rounded.Lock,
        stringResource(R.string.contact_import_denied_title),
        body = stringResource(R.string.contact_import_denied_body),
        action = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(stringResource(R.string.contact_import_allow), onClick = onAllow, modifier = Modifier.fillMaxWidth())
                SecondaryButton(stringResource(R.string.contact_import_open_settings), onClick = onSettings, modifier = Modifier.fillMaxWidth())
            }
        },
    )
}

@Composable
private fun ContactRow(row: ImportRow, checked: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val candidate = row.candidate
    val canPick = enabled && !row.alreadyClient

    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (row.alreadyClient) 0.5f else 1f)
            .toggleable(value = checked, enabled = canPick, role = Role.Checkbox, onValueChange = onToggle)
            .padding(horizontal = ScreenPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = canPick)
        Avatar(candidate.name, size = 40.dp)
        Column(Modifier.weight(1f)) {
            Text(candidate.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(candidate.phone, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (row.alreadyClient) {
                Text(stringResource(R.string.contact_import_already_client), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
