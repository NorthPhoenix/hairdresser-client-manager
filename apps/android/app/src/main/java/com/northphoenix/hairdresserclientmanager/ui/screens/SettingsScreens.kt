package com.northphoenix.hairdresserclientmanager.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.auth.AuthState
import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.data.Stylist
import com.northphoenix.hairdresserclientmanager.i18n.AppLanguage
import com.northphoenix.hairdresserclientmanager.i18n.label
import com.northphoenix.hairdresserclientmanager.state.AppViewModel
import com.northphoenix.hairdresserclientmanager.ui.HcmSnackbarHost
import com.northphoenix.hairdresserclientmanager.ui.components.Avatar
import com.northphoenix.hairdresserclientmanager.ui.components.Hairline
import com.northphoenix.hairdresserclientmanager.ui.components.HcmCard
import com.northphoenix.hairdresserclientmanager.ui.components.HcmTextField
import com.northphoenix.hairdresserclientmanager.ui.components.Kicker
import com.northphoenix.hairdresserclientmanager.ui.components.PickerField
import com.northphoenix.hairdresserclientmanager.ui.components.PrimaryButton
import com.northphoenix.hairdresserclientmanager.ui.components.ScreenPadding
import com.northphoenix.hairdresserclientmanager.ui.components.ScreenTitle
import com.northphoenix.hairdresserclientmanager.ui.components.SegmentedControl
import com.northphoenix.hairdresserclientmanager.ui.components.TextAction
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Editable timezone and salon address. Saveable, so a language switch mid-edit does not lose them. */
private class SettingsDraft(timezone: MutableState<String>, salonAddress: MutableState<String>) {
    var timezone by timezone
    var salonAddress by salonAddress
}

@Composable
private fun rememberSettingsDraft(stylist: Stylist): SettingsDraft {
    val timezone = rememberSaveable(stylist.timezone) { mutableStateOf(stylist.timezone) }
    val salonAddress = rememberSaveable(stylist.salonAddress) { mutableStateOf(stylist.salonAddress) }

    return remember(timezone, salonAddress) { SettingsDraft(timezone, salonAddress) }
}

/** Switching language takes effect immediately; there is nothing to save. */
@Composable
private fun LanguageField(viewModel: AppViewModel) {
    val selected = AppLanguage.current(LocalContext.current)

    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(stringResource(R.string.language_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SegmentedControl(
            options = Language.entries,
            selected = selected,
            onSelect = { if (it != selected) viewModel.setLanguage(it) },
            label = { stringResource(it.label) },
        )
    }
}

@Composable
private fun SettingsFields(draft: SettingsDraft) {
    var showTimezones by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PickerField(
            label = stringResource(R.string.timezone_label),
            value = draft.timezone.ifBlank { stringResource(R.string.not_set) },
            isPlaceholder = draft.timezone.isBlank(),
            icon = Icons.Rounded.Public,
            onClick = { showTimezones = true },
        )
        HcmTextField(
            value = draft.salonAddress,
            onValueChange = { draft.salonAddress = it },
            label = stringResource(R.string.address_label),
            placeholder = stringResource(R.string.placeholder_address),
            singleLine = false,
            minLines = 2,
            capitalization = KeyboardCapitalization.Words,
        )
    }

    if (showTimezones) {
        TimezonePickerDialog(
            selected = draft.timezone,
            onDismiss = { showTimezones = false },
            onPick = {
                draft.timezone = it
                showTimezones = false
            },
        )
    }
}

/** Stylist Onboarding: confirm language, timezone and salon address before the app opens. */
@Composable
fun OnboardingScreen(viewModel: AppViewModel, stylist: Stylist) {
    val draft = rememberSettingsDraft(stylist)
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { HcmSnackbarHost() }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenPadding, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            ScreenTitle(
                title = stringResource(R.string.onboarding_title),
                kicker = stringResource(R.string.app_name),
                subtitle = stringResource(R.string.onboarding_subtitle),
            )
            HcmCard {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    LanguageField(viewModel)
                    SettingsFields(draft)
                }
            }
            OptionalSetupNote()
            PrimaryButton(
                stringResource(R.string.save_onboarding),
                onClick = { viewModel.saveSettings(draft.timezone, draft.salonAddress) },
                modifier = Modifier.fillMaxWidth(),
                loading = busy,
            )
        }
    }
}

/** Profile tab: account, Stylist settings and the Service Menu entry point. */
@Composable
fun ProfileScreen(viewModel: AppViewModel, onOpenServiceMenu: () -> Unit) {
    val stylist by viewModel.stylist.collectAsStateWithLifecycle()
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val serviceMenu by viewModel.serviceMenu.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val current = stylist ?: return
    val draft = rememberSettingsDraft(current)
    val email = (authState as? AuthState.SignedIn)?.email.orEmpty()
    val changed = draft.timezone != current.timezone || draft.salonAddress.trim() != current.salonAddress

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = ScreenPadding, end = ScreenPadding, top = 28.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle(title = stringResource(R.string.settings_title), kicker = stringResource(R.string.tab_profile), modifier = Modifier.padding(bottom = 4.dp))

        HcmCard(contentPadding = PaddingValues(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Avatar(email.ifBlank { "?" })
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Kicker(stringResource(R.string.account_title))
                    Text(email, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                TextAction(stringResource(R.string.sign_out), onClick = viewModel::signOut, icon = Icons.AutoMirrored.Rounded.Logout)
            }
        }

        HcmCard(onClick = onOpenServiceMenu, contentPadding = PaddingValues(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.size(44.dp).then(Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxSize()) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.ContentCut, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.service_menu_title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (serviceMenu.isEmpty()) stringResource(R.string.service_menu_empty_title) else serviceMenu.take(3).joinToString(", ") { it.name },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
            }
        }

        HcmCard { LanguageField(viewModel) }

        HcmCard {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                SettingsFields(draft)
                PrimaryButton(
                    stringResource(R.string.save_settings),
                    onClick = { viewModel.saveSettings(draft.timezone, draft.salonAddress) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = changed,
                    loading = busy,
                )
            }
        }

        OptionalSetupNote()
    }
}

@Composable
private fun OptionalSetupNote() {
    HcmCard(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.optional_setup_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.optional_setup_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private data class ZoneOption(val id: String, val offset: String)

@Composable
private fun TimezonePickerDialog(selected: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val zones = remember {
        val now = Instant.now()
        val offsetFormat = DateTimeFormatter.ofPattern("xxx", Locale.ROOT)

        ZoneId.getAvailableZoneIds()
            .filter { it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }
            .sorted()
            .map { ZoneOption(it, "UTC" + offsetFormat.format(now.atZone(ZoneId.of(it)))) } + ZoneOption("UTC", "UTC+00:00")
    }
    var search by rememberSaveable { mutableStateOf("") }
    val filtered = remember(search) {
        val query = search.trim().replace(' ', '_')
        if (query.isEmpty()) zones else zones.filter { it.id.contains(query, ignoreCase = true) || it.offset.contains(query, ignoreCase = true) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().safeDrawingPadding()) {
            Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.timezone_label), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 20.dp))
                HcmTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = "",
                    placeholder = stringResource(R.string.timezone_search_placeholder),
                    leadingIcon = Icons.Rounded.Search,
                    capitalization = KeyboardCapitalization.None,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Hairline()
                LazyColumn(Modifier.fillMaxWidth().height(380.dp)) {
                    items(filtered, key = { it.id }) { zone ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(zone.id) }.padding(horizontal = 20.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(zone.id.replace('_', ' '), style = MaterialTheme.typography.bodyLarge)
                                Text(zone.offset, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (zone.id == selected) {
                                Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(end = 12.dp, bottom = 10.dp), horizontalArrangement = Arrangement.End) {
                    TextAction(stringResource(R.string.cancel), onClick = onDismiss)
                }
            }
        }
    }
}
