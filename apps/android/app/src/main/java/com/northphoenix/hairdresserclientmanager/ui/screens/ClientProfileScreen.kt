package com.northphoenix.hairdresserclientmanager.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.data.ClientHistoryEntry
import com.northphoenix.hairdresserclientmanager.data.ClientProfile
import com.northphoenix.hairdresserclientmanager.domain.ClientReminder
import com.northphoenix.hairdresserclientmanager.domain.ClientRules
import com.northphoenix.hairdresserclientmanager.domain.PhoneFormat
import com.northphoenix.hairdresserclientmanager.domain.Referrals
import com.northphoenix.hairdresserclientmanager.domain.ClientVisits
import com.northphoenix.hairdresserclientmanager.domain.ClientVisits.SpanUnit
import com.northphoenix.hairdresserclientmanager.i18n.label
import com.northphoenix.hairdresserclientmanager.state.AppViewModel
import com.northphoenix.hairdresserclientmanager.state.LoadState
import com.northphoenix.hairdresserclientmanager.ui.LocalTimeFormat
import com.northphoenix.hairdresserclientmanager.ui.components.Avatar
import com.northphoenix.hairdresserclientmanager.ui.components.Hairline
import com.northphoenix.hairdresserclientmanager.ui.components.HcmCard
import com.northphoenix.hairdresserclientmanager.ui.components.ItemMissing
import com.northphoenix.hairdresserclientmanager.ui.components.LoadFailed
import com.northphoenix.hairdresserclientmanager.ui.components.PrimaryButton
import com.northphoenix.hairdresserclientmanager.ui.components.ScreenColumn
import com.northphoenix.hairdresserclientmanager.ui.components.SubScreen
import com.northphoenix.hairdresserclientmanager.ui.components.TextAction
import com.northphoenix.hairdresserclientmanager.ui.components.sideBySideWidth
import com.northphoenix.hairdresserclientmanager.ui.openUri
import java.time.Instant
import java.time.LocalDate

/**
 * The Client Profile as it opens from the Clients View or an Appointment: read-only. The Client's
 * name, ways to call, text and find them, "Book again", how often they come, their next
 * Appointment, their details and Client Note, and their Appointment history. The pencil opens
 * the editor ([ClientEditorScreen]), which has everything that can be changed, the Profile Share
 * and deleting the Client.
 */
@Composable
fun ClientProfileScreen(
    viewModel: AppViewModel,
    clientId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onBookAgain: (ClientVisits.Rebooking?) -> Unit,
    onOpenAppointment: (String) -> Unit,
    onMerged: (keptId: String) -> Unit = {},
    onOpenClient: (String) -> Unit = {},
) {
    val clients = viewModel.clients.collectAsStateWithLifecycle().value.data.orEmpty()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val client = clients.firstOrNull { it.id == clientId }
    // The Client the merge sheet is open for.
    var mergingWith by rememberSaveable(clientId) { mutableStateOf<String?>(null) }
    // The suggested referrers the Stylist said no to ("Client:referrer"). Kept on this device, so the
    // question is not asked again at every visit of the profile.
    val appContext = LocalContext.current.applicationContext
    val refusals = remember { appContext.getSharedPreferences("referral_suggestions_refused", Context.MODE_PRIVATE) }
    var refused by remember { mutableStateOf(refusals.getStringSet("pairs", emptySet()).orEmpty()) }

    if (client == null) {
        MissingClient(viewModel, clientId, onBack, saving)
        return
    }

    val format = LocalTimeFormat.current
    val today by viewModel.today.collectAsStateWithLifecycle()
    val history by rememberClientHistory(viewModel, clientId)
    val entries = history.data
    // The day is part of the key: a profile left open over midnight says "yesterday" for the visit of today.
    val summary = remember(entries, today, format.zone) { entries?.let { ClientVisits.summary(it, Instant.now(), format.zone) } }

    SubScreen(
        title = stringResource(R.string.edit_client_title),
        onBack = onBack,
        saving = saving,
        actions = {
            IconButton(onClick = onEdit) {
                Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.client_edit_action))
            }
        },
    ) { padding ->
        ScreenColumn(padding) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Avatar(client.name, size = 64.dp)
                // A long name wraps: it is the one place on the profile where the whole of it is shown.
                Text(client.name, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
            }

            Referrer(
                client,
                clients,
                refused = refused,
                busy = busy,
                onOpenClient = onOpenClient,
                onAccept = { suggested ->
                    viewModel.saveClient(
                        client.id, client.name, client.language, client.phone, client.email, client.address, client.note,
                        client.updatedAt, "", suggested.id,
                    ) {}
                },
                onRefuse = { pair ->
                    refused = refused + pair
                    refusals.edit().putStringSet("pairs", refused).apply()
                },
            )

            // Another Client with the same number, until the Stylist merges them or says they are different people.
            ClientRules.possibleDuplicates(client, clients).forEach { other ->
                DuplicateBanner(
                    other,
                    busy = busy,
                    onMerge = { mergingWith = other.id },
                    onDistinct = { viewModel.markClientsDistinct(client.id, other.id) },
                )
            }

            QuickActions(client, onFailed = { viewModel.notify(it, isError = true) })

            // Until the history has loaded there is no last visit to go by, and the form opens as it always does.
            PrimaryButton(
                stringResource(R.string.client_book_again),
                onClick = { onBookAgain(entries?.let { ClientVisits.rebooking(it, client.id) }) },
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Rounded.Add,
            )

            if (summary != null) {
                VisitStats(summary, today)
                summary.next?.let { next -> NextAppointment(next, onOpen = { onOpenAppointment(next.id) }) }
            }

            ContactDetails(client)

            if (client.note.isNotBlank()) {
                HcmCard {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FieldLabel(stringResource(R.string.client_note_label))
                        SelectionContainer {
                            Text(client.note, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }

            Referred(client, clients, onOpenClient)

            ClientHistoryCard(history, onRetry = { viewModel.loadClientHistory(client.id) }, onOpenAppointment)
        }
    }

    clients.firstOrNull { it.id == mergingWith }?.let { other ->
        MergeClientsSheet(
            viewModel,
            client,
            other,
            onDismiss = { mergingWith = null },
            onMerged = { kept ->
                mergingWith = null
                onMerged(kept.id)
            },
        )
    }
}

/**
 * Who recommended the Client, as a tag that opens that Client. Without one, the Client the name says
 * recommended them, as a question with a Yes that sets it (see [Referrals.suggestion]); nothing is
 * saved until she taps it.
 */
@Composable
private fun Referrer(
    client: ClientProfile,
    clients: List<ClientProfile>,
    refused: Set<String>,
    busy: Boolean,
    onOpenClient: (String) -> Unit,
    onAccept: (ClientProfile) -> Unit,
    onRefuse: (String) -> Unit,
) {
    val referrer = Referrals.referrer(client, clients)

    if (referrer != null) {
        Surface(
            onClick = { onOpenClient(referrer.id) },
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Row(
                Modifier.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // A long name wraps: the tag is as wide as the screen at most.
                Text(
                    stringResource(R.string.client_referred_by_tag, referrer.name),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        return
    }

    val suggested = Referrals.suggestion(client, clients)?.takeIf { "${client.id}:${it.id}" !in refused }

    if (suggested != null) {
        HcmCard(color = MaterialTheme.colorScheme.secondaryContainer, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.client_referral_suggestion, suggested.name),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextAction(stringResource(R.string.client_referral_suggestion_no), onClick = { onRefuse("${client.id}:${suggested.id}") }, enabled = !busy)
                TextAction(stringResource(R.string.client_referral_suggestion_yes), onClick = { onAccept(suggested) }, enabled = !busy)
            }
        }
    }
}

/** "Her referrals · 2": the Clients this Client recommended, each of them opens. Nothing when there are none. */
@Composable
private fun Referred(client: ClientProfile, clients: List<ClientProfile>, onOpenClient: (String) -> Unit) {
    val referred = Referrals.referred(client, clients)

    if (referred.isEmpty()) {
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(stringResource(R.string.client_referrals_title, referred.size))
        referred.forEach { other -> ClientRow(other, onClick = { onOpenClient(other.id) }) }
    }
}

/**
 * A profile opened for a Client who is not in the list. The list may be older than the Client, or
 * the Client may have been deleted: it is loaded again, and until then there is nothing to show.
 */
@Composable
private fun MissingClient(viewModel: AppViewModel, clientId: String, onBack: () -> Unit, saving: Boolean) {
    var lookup by remember { mutableStateOf<LoadState>(LoadState.Loading) }
    var lookupAttempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(clientId, lookupAttempt) {
        lookup = LoadState.Loading
        lookup = viewModel.findClient(clientId)
    }

    SubScreen(title = stringResource(R.string.edit_client_title), onBack = onBack, saving = saving) { padding ->
        when (val state = lookup) {
            is LoadState.Failed -> LoadFailed(state.message, onRetry = { lookupAttempt++ }, Modifier.padding(padding))
            LoadState.Missing -> ItemMissing(
                stringResource(R.string.client_missing_title),
                stringResource(R.string.client_missing_body),
                onBack,
                Modifier.padding(padding),
            )
            else -> LoadingRow(Modifier.padding(padding))
        }
    }
}

/**
 * Call, text and the map, each only when the Client has what it needs: a phone with a digit to
 * dial, an address. Each opens the app on the device that does it; nothing is sent from here.
 */
@Composable
private fun QuickActions(client: ClientProfile, onFailed: (Int) -> Unit) {
    val context = LocalContext.current
    val number = ClientReminder.recipient(client.phone)
    val address = client.address.lines().map(String::trim).filter(String::isNotEmpty).joinToString(", ")

    if (number == null && address.isEmpty()) {
        return
    }

    Tiles {
        if (number != null) {
            QuickAction(Icons.Rounded.Call, stringResource(R.string.client_call)) {
                if (!context.startOrFalse(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))) onFailed(R.string.calls_unavailable)
            }
            QuickAction(Icons.Rounded.Sms, stringResource(R.string.client_sms)) {
                if (!context.startOrFalse(Intent(Intent.ACTION_SENDTO, ClientReminder.smsUri(number)))) onFailed(R.string.sms_app_unavailable)
            }
        }
        if (address.isNotEmpty()) {
            QuickAction(Icons.Rounded.Map, stringResource(R.string.client_map)) {
                // A device without a maps app may still have a browser: Google Maps opens there.
                val opened = context.startOrFalse(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(address)))) ||
                    context.openUri(Uri.parse("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(address)))

                if (!opened) onFailed(R.string.map_unavailable)
            }
        }
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
        }
    }
}

/** Last visit, how often the Client usually comes (from two visit days on), and the number of visits. */
@Composable
private fun VisitStats(summary: ClientVisits.Summary, today: LocalDate) {
    Tiles {
        StatTile(
            stringResource(R.string.client_stat_last_visit),
            summary.lastVisit?.let { spanAgo(ClientVisits.ago(it, today)) } ?: stringResource(R.string.client_stat_no_visits),
        )
        summary.usualInterval?.let { StatTile(stringResource(R.string.client_stat_usually), spanEvery(it)) }
        StatTile(stringResource(R.string.client_stat_visits), summary.visits.toString())
    }
}

@Composable
private fun spanAgo(span: ClientVisits.Span): String = when {
    span.unit == SpanUnit.DAYS && span.count == 0 -> stringResource(R.string.client_stat_today)
    span.unit == SpanUnit.DAYS && span.count == 1 -> stringResource(R.string.client_stat_yesterday)
    else -> {
        val res = when (span.unit) {
            SpanUnit.DAYS -> R.plurals.client_ago_days
            SpanUnit.WEEKS -> R.plurals.client_ago_weeks
            SpanUnit.MONTHS -> R.plurals.client_ago_months
            SpanUnit.YEARS -> R.plurals.client_ago_years
        }

        pluralStringResource(res, span.count, span.count)
    }
}

@Composable
private fun spanEvery(span: ClientVisits.Span): String {
    val res = when (span.unit) {
        SpanUnit.DAYS -> R.plurals.client_every_days
        SpanUnit.WEEKS -> R.plurals.client_every_weeks
        SpanUnit.MONTHS -> R.plurals.client_every_months
        SpanUnit.YEARS -> R.plurals.client_every_years
    }

    return pluralStringResource(res, span.count, span.count)
}

@Composable
private fun StatTile(label: String, value: String) {
    // Read out as one: "Last visit, 7 weeks ago".
    HcmCard(Modifier.semantics(mergeDescendants = true) {}, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FieldLabel(label)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** The Client's next Appointment: "Fri, Oct 23 · 6:00 PM". A tap opens it. */
@Composable
private fun NextAppointment(entry: ClientHistoryEntry, onOpen: () -> Unit) {
    val format = LocalTimeFormat.current
    val day = format.zoned(entry.startsAt).toLocalDate()

    HcmCard(onClick = onOpen, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FieldLabel(stringResource(R.string.client_next_appointment))
                Text(
                    format.shortWeekday(day) + ", " + format.shortDate(day) + " · " + format.time(entry.startsAt),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

/** Phone, address, email (only when there is one) and language, each under its name, to read and to copy. */
@Composable
private fun ContactDetails(client: ClientProfile) {
    HcmCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ContactRow(stringResource(R.string.client_phone_label), PhoneFormat.display(client.phone))
            Hairline()
            ContactRow(stringResource(R.string.client_address_label), client.address)
            if (client.email.isNotBlank()) {
                Hairline()
                ContactRow(stringResource(R.string.client_email_label), client.email)
            }
            Hairline()
            ContactRow(stringResource(R.string.client_language_label), stringResource(client.language.label))
        }
    }
}

@Composable
private fun ContactRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FieldLabel(label)
        if (value.isBlank()) {
            Text(stringResource(R.string.client_not_given), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            SelectionContainer {
                Text(value.trim(), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * Tiles side by side in equal widths while the longest word of each fits in its share of the
 * width, all as tall as the tallest; one under the other at full width when one does not, as at
 * a large font size on a narrow screen, so that no word is broken inside.
 */
@Composable
private fun Tiles(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier.fillMaxWidth()) { measurables, constraints ->
        val gap = 10.dp.roundToPx()
        val width = constraints.maxWidth
        val each = sideBySideWidth(measurables.map { it.minIntrinsicWidth(Constraints.Infinity) }, width, gap)

        if (each != null) {
            val height = measurables.maxOf { it.maxIntrinsicHeight(each) }
            val placeables = measurables.map { it.measure(Constraints(minWidth = each, maxWidth = each, minHeight = height, maxHeight = height)) }

            layout(width, height) {
                placeables.forEachIndexed { index, placeable -> placeable.placeRelative(index * (each + gap), 0) }
            }
        } else {
            val placeables = measurables.map { it.measure(Constraints(minWidth = width, maxWidth = width)) }

            layout(width, placeables.sumOf { it.height } + gap * (placeables.size - 1).coerceAtLeast(0)) {
                var y = 0

                placeables.forEach { placeable ->
                    placeable.placeRelative(0, y)
                    y += placeable.height + gap
                }
            }
        }
    }
}

/** Starts another app's screen. False when the device has no app for it. */
private fun Context.startOrFalse(intent: Intent): Boolean =
    try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
