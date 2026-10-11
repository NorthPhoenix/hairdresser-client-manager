package com.northphoenix.hairdresserclientmanager.ui.screens

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.auth.AccountName
import com.northphoenix.hairdresserclientmanager.auth.AuthState
import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.data.Stylist
import com.northphoenix.hairdresserclientmanager.domain.ChangedElsewhere
import com.northphoenix.hairdresserclientmanager.domain.Timezones
import com.northphoenix.hairdresserclientmanager.domain.ZoneOption
import com.northphoenix.hairdresserclientmanager.i18n.DeviceZoneNames
import com.northphoenix.hairdresserclientmanager.i18n.label
import com.northphoenix.hairdresserclientmanager.i18n.shownLanguage
import com.northphoenix.hairdresserclientmanager.state.AppViewModel
import com.northphoenix.hairdresserclientmanager.state.LoadState
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
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Editable timezone and salon address. Saveable, so a language switch mid-edit does not lose them. */
private class SettingsDraft(timezone: MutableState<String>, salonAddress: MutableState<String>) {
    var timezone by timezone
    var salonAddress by salonAddress
}

/**
 * The draft follows the Stylist's settings field by field. When they are saved again, here or on
 * another device, a field the Stylist has not changed shows what is saved now, and one they
 * changed keeps what is in it (see [ChangedElsewhere]): the form never shows a setting that is no
 * longer saved as if it were, and nothing typed is lost.
 */
@Composable
private fun rememberSettingsDraft(stylist: Stylist): SettingsDraft {
    val timezone = rememberSaveable { mutableStateOf(stylist.timezone) }
    val salonAddress = rememberSaveable { mutableStateOf(stylist.salonAddress) }
    // The settings the fields were last filled from.
    var loadedTimezone by rememberSaveable { mutableStateOf(stylist.timezone) }
    var loadedAddress by rememberSaveable { mutableStateOf(stylist.salonAddress) }

    LaunchedEffect(stylist.timezone, stylist.salonAddress) {
        timezone.value = ChangedElsewhere.field(loadedTimezone, timezone.value, stylist.timezone)
        salonAddress.value = ChangedElsewhere.field(loadedAddress, salonAddress.value, stylist.salonAddress)
        loadedTimezone = stylist.timezone
        loadedAddress = stylist.salonAddress
    }

    return remember(timezone, salonAddress) { SettingsDraft(timezone, salonAddress) }
}

/**
 * Switching language takes effect immediately; there is no Save button for it. The selected option
 * is the language the screen is in, and every tap goes to the view model, which knows what is
 * saved and what is still being saved.
 */
@Composable
private fun LanguageField(viewModel: AppViewModel) {
    val selected = LocalContext.current.shownLanguage()

    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(stringResource(R.string.language_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SegmentedControl(
            options = Language.entries,
            selected = selected,
            onSelect = viewModel::setLanguage,
            label = { stringResource(it.label) },
        )
    }
}

@Composable
private fun SettingsFields(draft: SettingsDraft) {
    var showTimezones by rememberSaveable { mutableStateOf(false) }
    val locale = shownLocale()
    // By its city and offset, as the picker lists it. The id that is saved ("Europe/Moscow") is not for the Stylist to read.
    val timezone = remember(draft.timezone, locale) { Timezones.option(DeviceZoneNames, locale, draft.timezone, Instant.now()) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PickerField(
            label = stringResource(R.string.timezone_label),
            value = timezone?.label ?: stringResource(R.string.not_set),
            isPlaceholder = timezone == null,
            icon = Icons.Rounded.Public,
            onClick = { showTimezones = true },
            wraps = true,
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
    val serviceMenuLoad by viewModel.serviceMenuLoad.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val accountName by viewModel.accountName.collectAsStateWithLifecycle()
    val current = stylist ?: return
    val draft = rememberSettingsDraft(current)
    val email = (authState as? AuthState.SignedIn)?.email.orEmpty()
    val changed = draft.timezone != current.timezone || draft.salonAddress.trim() != current.salonAddress
    val focus = LocalFocusManager.current

    Column(
        Modifier
            .fillMaxSize()
            // The keyboard takes its part of the screen from the scrolling area, so the field being typed in stays above it.
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(start = ScreenPadding, end = ScreenPadding, top = 28.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle(title = stringResource(R.string.settings_title), kicker = stringResource(R.string.tab_profile), modifier = Modifier.padding(bottom = 4.dp))

        HcmCard(contentPadding = PaddingValues(start = 16.dp, top = 12.dp, bottom = 4.dp, end = 6.dp)) {
            Row(Modifier.padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Avatar(email.ifBlank { "?" })
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Kicker(stringResource(R.string.account_title))
                    // Whole, on as many lines as it takes: it is how the Stylist tells which account is signed in.
                    Text(email, style = MaterialTheme.typography.titleMedium)
                }
            }
            // On a line of its own, which leaves the email the width of the card at every font size.
            TextAction(stringResource(R.string.sign_out), onClick = viewModel::signOut, modifier = Modifier.align(Alignment.End), icon = Icons.AutoMirrored.Rounded.Logout)
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
                    // "No Service Menu Items" is only true of a menu that has loaded. The Service Menu screen has the reason and 'Try again'.
                    val menuFailed = serviceMenu.isEmpty() && serviceMenuLoad is LoadState.Failed

                    Text(
                        when {
                            serviceMenu.isNotEmpty() -> serviceMenu.take(3).joinToString(", ") { it.name }
                            serviceMenuLoad == LoadState.Loaded -> stringResource(R.string.service_menu_empty_title)
                            menuFailed -> stringResource(R.string.load_failed_title)
                            else -> ""
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (menuFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
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
                    onClick = {
                        // The notice of how the save went appears over the tab bar, which the keyboard covers.
                        focus.clearFocus()
                        viewModel.saveSettings(draft.timezone, draft.salonAddress)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = changed,
                    loading = busy,
                )
            }
        }

        // Beside the salon address: the two are how a Client knows who a Profile Share is from.
        accountName?.let { AccountNameCard(saved = it, busy = busy, onSave = viewModel::saveAccountName) }

        OptionalSetupNote()
    }
}

/**
 * The Stylist's name as Clients see it on Profile Shares and Share Images. It is the name on the
 * sign-in account and is saved there, so it has a Save of its own. An empty field takes that
 * part of the name away, and with both empty no name is shown.
 */
@Composable
private fun AccountNameCard(saved: AccountName, busy: Boolean, onSave: (String, String) -> Unit) {
    // Each field starts from the saved name, and again when that changes, as the settings do.
    var first by rememberSaveable(saved.first) { mutableStateOf(saved.first) }
    var last by rememberSaveable(saved.last) { mutableStateOf(saved.last) }
    val focus = LocalFocusManager.current

    HcmCard {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.account_name_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.account_name_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HcmTextField(
                first,
                { first = AccountName.typed(it) },
                label = stringResource(R.string.account_first_name_label),
                placeholder = stringResource(R.string.placeholder_account_first_name),
                capitalization = KeyboardCapitalization.Words,
            )
            HcmTextField(
                last,
                { last = AccountName.typed(it) },
                label = stringResource(R.string.account_last_name_label),
                placeholder = stringResource(R.string.placeholder_account_last_name),
                capitalization = KeyboardCapitalization.Words,
            )
            PrimaryButton(
                stringResource(R.string.save_account_name),
                onClick = {
                    // As for the settings: the notice appears over the tab bar, which the keyboard covers.
                    focus.clearFocus()
                    onSave(first, last)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = AccountName.of(first, last) != saved,
                loading = busy,
            )
        }
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

/** The locale of the language the screen is in: timezones are named in it. */
@Composable
private fun shownLocale(): Locale {
    val language = LocalContext.current.shownLanguage()

    return remember(language) { Locale.forLanguageTag(language.tag) }
}

@Composable
private fun TimezonePickerDialog(selected: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val locale = shownLocale()
    // Several hundred timezones, each with its names in both languages: put together off the main thread, so the dialog opens at once.
    val zones by produceState<List<ZoneOption>?>(initialValue = null, selected, locale) {
        value = withContext(Dispatchers.Default) {
            Timezones.options(DeviceZoneNames, locale, Language.entries.map { Locale.forLanguageTag(it.tag) }, selected, Instant.now())
        }
    }
    var search by rememberSaveable { mutableStateOf("") }
    val found = remember(zones, search) { zones?.let { Timezones.search(it, search) } }

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
                // 380 dp where there is room for it. Above the keyboard there is not: the list gives way and Cancel stays on screen.
                Box(Modifier.fillMaxWidth().weight(1f, fill = false).height(380.dp)) {
                    when {
                        found == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), strokeWidth = 2.5.dp)
                        found.isEmpty() -> Text(
                            stringResource(R.string.no_timezone_results),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 13.dp),
                        )
                        // A list of its own for every search: what was found starts at its top, and the whole list at the chosen timezone.
                        else -> key(search) {
                            val start = if (search.isBlank()) found.indexOfFirst { it.id == selected }.coerceAtLeast(0) else 0

                            LazyColumn(Modifier.fillMaxSize().selectableGroup(), state = rememberLazyListState(initialFirstVisibleItemIndex = start)) {
                                items(found, key = { it.id }) { zone ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            // The check mark is for the eye; a screen reader is told which timezone is the chosen one.
                                            .selectable(selected = zone.id == selected, role = Role.RadioButton) { onPick(zone.id) }
                                            .padding(horizontal = 20.dp, vertical = 13.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(zone.title, style = MaterialTheme.typography.bodyLarge)
                                            if (zone.detail.isNotEmpty()) {
                                                Text(zone.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                        if (zone.id == selected) {
                                            Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
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
