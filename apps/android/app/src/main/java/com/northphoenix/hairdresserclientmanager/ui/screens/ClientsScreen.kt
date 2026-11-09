package com.northphoenix.hairdresserclientmanager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.ImportContacts
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.data.ClientProfile
import com.northphoenix.hairdresserclientmanager.domain.ClientRowVisits
import com.northphoenix.hairdresserclientmanager.domain.ClientRules
import com.northphoenix.hairdresserclientmanager.domain.ClientVisits
import com.northphoenix.hairdresserclientmanager.domain.PhoneFormat
import com.northphoenix.hairdresserclientmanager.i18n.label
import com.northphoenix.hairdresserclientmanager.state.AppViewModel
import com.northphoenix.hairdresserclientmanager.state.LoadState
import com.northphoenix.hairdresserclientmanager.ui.LocalTimeFormat
import com.northphoenix.hairdresserclientmanager.ui.components.Avatar
import com.northphoenix.hairdresserclientmanager.ui.components.EmptyState
import com.northphoenix.hairdresserclientmanager.ui.components.HcmCard
import com.northphoenix.hairdresserclientmanager.ui.components.HcmTextField
import com.northphoenix.hairdresserclientmanager.ui.components.LoadFailed
import com.northphoenix.hairdresserclientmanager.ui.components.ScreenPadding
import com.northphoenix.hairdresserclientmanager.ui.components.ScreenTitle
import com.northphoenix.hairdresserclientmanager.ui.components.SecondaryButton
import com.northphoenix.hairdresserclientmanager.ui.components.Tag
import com.northphoenix.hairdresserclientmanager.ui.components.collectWhileOpen
import java.time.Instant

/** Clients View: searchable list of Client Profiles. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientsScreen(viewModel: AppViewModel, onOpenClient: (String) -> Unit, onImport: () -> Unit) {
    // The list and its load state in one value: "no Clients" is only said of a list that has loaded.
    val read by viewModel.clients.collectAsStateWithLifecycle()
    val clients = read.data.orEmpty()
    val load = read.state
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    var search by rememberSaveable { mutableStateOf("") }
    val filtered = remember(clients, search) { ClientRules.filter(clients, search) }
    val duplicates = remember(clients) { ClientRules.withPossibleDuplicates(clients) }
    val visits = rememberClientRowVisits(viewModel, clients)

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = 28.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ScreenTitle(title = stringResource(R.string.clients_title), modifier = Modifier.padding(bottom = 8.dp))
            }

            item {
                SecondaryButton(
                    stringResource(R.string.import_contact),
                    onClick = onImport,
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Rounded.ImportContacts,
                )
            }

            when (val state = load) {
                is LoadState.Failed -> item { LoadFailed(state.message, onRetry = viewModel::retryLoad) }
                LoadState.Loaded -> if (clients.isEmpty()) {
                    item { EmptyState(Icons.Rounded.Group, stringResource(R.string.no_clients_title), body = stringResource(R.string.no_clients_body)) }
                }
                else -> item { LoadingRow() }
            }

            // Clients known before the list has loaded (one that was just created) are listed all the same.
            if (clients.isNotEmpty()) {
                item {
                    HcmTextField(
                        value = search,
                        onValueChange = { search = it },
                        label = "",
                        placeholder = stringResource(R.string.client_search_label),
                        leadingIcon = Icons.Rounded.Search,
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Search,
                        trailing = if (search.isNotEmpty()) {
                            { IconButton(onClick = { search = "" }) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.clear)) } }
                        } else {
                            null
                        },
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }

                if (filtered.isEmpty() && load == LoadState.Loaded) {
                    item { EmptyState(Icons.Rounded.SearchOff, stringResource(R.string.no_client_results)) }
                }

                items(filtered, key = { it.id }) { client ->
                    ClientRow(client, onClick = { onOpenClient(client.id) }, possibleDuplicate = client.id in duplicates, visits = visits?.get(client.id))
                }
            }
        }
    }
}

@Composable
fun ClientRow(
    client: ClientProfile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    possibleDuplicate: Boolean = false,
    visits: ClientRowVisits.Line? = null,
) {
    HcmCard(modifier.fillMaxWidth(), onClick = onClick, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Avatar(client.name)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(client.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // No line limit: where the phone and the language do not fit on one line, the language goes on
                // the next one whole. The no-break space keeps it with its dot.
                Text(
                    listOf(PhoneFormat.display(client.phone), stringResource(client.language.label)).filter { it.isNotBlank() }.joinToString(" ·\u00A0"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                visits?.let { VisitsLine(it) }
                // Another Client has the same number: the profile offers to merge them.
                if (possibleDuplicate) {
                    Tag(
                        stringResource(R.string.client_duplicate_tag),
                        modifier = Modifier.padding(top = 4.dp),
                        content = MaterialTheme.colorScheme.onSecondaryContainer,
                        container = MaterialTheme.colorScheme.secondaryContainer,
                    )
                }
            }
            if (trailing != null) {
                trailing()
            } else {
                if (client.activeProfileShare != null) {
                    Icon(Icons.Rounded.Link, contentDescription = stringResource(R.string.profile_share_title), tint = MaterialTheme.colorScheme.primary)
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

/**
 * What each Client's row says of their visits, by Client id, read for as long as the Clients View
 * is open (as the Client Profile reads its history). Null until the dates have loaded: a row then
 * says nothing of them, rather than "no visits yet" of a Client who has had some.
 */
@Composable
private fun rememberClientRowVisits(viewModel: AppViewModel, clients: List<ClientProfile>): Map<String, ClientRowVisits.Line>? {
    val read by remember(viewModel) { viewModel.clientVisitDates() }.collectWhileOpen(
        initialValue = remember(viewModel) { viewModel.shownClientVisitDates() },
    )
    val today by viewModel.today.collectAsStateWithLifecycle()
    val zone = LocalTimeFormat.current.zone

    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.loadClientVisitDates() }

    // The day is part of the key: a list left open over midnight says "yesterday" for the visit of today.
    return remember(read.data, clients, today, zone) {
        read.data?.let { dates ->
            val byClient = ClientRowVisits.byClient(dates)
            val now = Instant.now()

            clients.associate { it.id to ClientRowVisits.line(byClient[it.id], now, zone) }
        }
    }
}

/** "Booked: tomorrow, 10:00", "Last visit: 3 weeks ago", "No visits yet". */
@Composable
private fun VisitsLine(line: ClientRowVisits.Line) {
    val format = LocalTimeFormat.current
    val text = when (line) {
        is ClientRowVisits.Line.Next -> {
            val time = format.time(line.startsAt)

            when (line.day) {
                ClientRowVisits.Day.TODAY -> stringResource(R.string.client_row_next_today, time)
                ClientRowVisits.Day.TOMORROW -> stringResource(R.string.client_row_next_tomorrow, time)
                ClientRowVisits.Day.LATER -> stringResource(R.string.client_row_next, format.shortDate(line.date), time)
            }
        }
        is ClientRowVisits.Line.Last -> when {
            line.ago.unit == ClientVisits.SpanUnit.DAYS && line.ago.count == 0 -> stringResource(R.string.client_row_last_visit_today)
            line.ago.unit == ClientVisits.SpanUnit.DAYS && line.ago.count == 1 -> stringResource(R.string.client_row_last_visit_yesterday)
            else -> stringResource(R.string.client_row_last_visit, agoText(line.ago))
        }
        ClientRowVisits.Line.None -> stringResource(R.string.client_row_no_visits)
    }

    Text(
        keepValueTogether(text),
        style = MaterialTheme.typography.bodyMedium,
        // An Appointment ahead is what the Stylist looks for: in the accent colour. The rest is quieter.
        color = if (line is ClientRowVisits.Line.Next) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Where the line does not fit, as next to a Profile Share icon, what follows the label goes on the
 * next line whole: "7 дней назад" under "Последний визит:", never "назад" alone.
 */
private fun keepValueTogether(text: String): String {
    val colon = text.indexOf(": ")

    return if (colon < 0) text else text.substring(0, colon + 2) + text.substring(colon + 2).replace(' ', '\u00A0')
}

@Composable
private fun agoText(span: ClientVisits.Span): String {
    val res = when (span.unit) {
        ClientVisits.SpanUnit.DAYS -> R.plurals.client_ago_days
        ClientVisits.SpanUnit.WEEKS -> R.plurals.client_ago_weeks
        ClientVisits.SpanUnit.MONTHS -> R.plurals.client_ago_months
        ClientVisits.SpanUnit.YEARS -> R.plurals.client_ago_years
    }

    return pluralStringResource(res, span.count, span.count)
}
