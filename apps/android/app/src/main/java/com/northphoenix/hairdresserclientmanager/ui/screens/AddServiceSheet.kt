package com.northphoenix.hairdresserclientmanager.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.ClientProfile
import com.northphoenix.hairdresserclientmanager.data.ServiceMenuItem
import com.northphoenix.hairdresserclientmanager.domain.Amount
import com.northphoenix.hairdresserclientmanager.domain.AppointmentClients
import com.northphoenix.hairdresserclientmanager.domain.Families
import com.northphoenix.hairdresserclientmanager.domain.MenuPicker
import com.northphoenix.hairdresserclientmanager.domain.Money
import com.northphoenix.hairdresserclientmanager.domain.ServicePicks
import com.northphoenix.hairdresserclientmanager.domain.TextLimits
import com.northphoenix.hairdresserclientmanager.state.LoadState
import com.northphoenix.hairdresserclientmanager.ui.components.ActionChip
import com.northphoenix.hairdresserclientmanager.ui.components.AmountField
import com.northphoenix.hairdresserclientmanager.ui.components.ChoiceChip
import com.northphoenix.hairdresserclientmanager.ui.components.Hairline
import com.northphoenix.hairdresserclientmanager.ui.components.HcmTallBottomSheet
import com.northphoenix.hairdresserclientmanager.ui.components.HcmTextField
import com.northphoenix.hairdresserclientmanager.ui.components.InlineEmpty
import com.northphoenix.hairdresserclientmanager.ui.components.InlineLoadFailed
import com.northphoenix.hairdresserclientmanager.ui.components.PrimaryButton
import com.northphoenix.hairdresserclientmanager.ui.components.TextAction
import com.northphoenix.hairdresserclientmanager.ui.components.rememberRequestKey

/**
 * Add Services: Services from the Service Menu, several at once, or one custom Service.
 *
 * The menu is searched, its most used items are offered first, and its groups are folded until
 * one is opened (see [MenuPicker]). Chips are picked and unpicked by a tap, and the button under
 * the list adds the picked ones at their default prices, in the order they were picked, in one
 * request ([onAddFromMenu]). "Custom service" opens the form of an ad hoc Service ([onAddCustom]),
 * which is all the sheet has when the menu is empty.
 *
 * Who the Services are for is picked above both, under "For whom": one chip per Client of the
 * Appointment, [initialClientId] or else the primary Client picked when the sheet opens, and a
 * chip that adds a person to the Appointment. That person is a Client who exists ([onAddPerson])
 * or a new one made from just a typed name ([onCreatePerson]), and is picked once they are in. The
 * family of the Appointment's Clients who are not in it yet are offered beside them, one tap each
 * ([Families.suggestions]).
 *
 * Each person has their own picks from the menu (see [ServicePicks]): the chip picked shows and
 * changes that person's, a chip with picks shows how many, and switching chips keeps them all.
 * The button adds the picks of everyone at once, per person in the order of the chips.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AddServiceSheet(
    appointment: Appointment,
    initialClientId: String?,
    menu: List<ServiceMenuItem>,
    menuLoad: LoadState,
    onRetry: () -> Unit,
    clients: List<ClientProfile>,
    clientsLoad: LoadState,
    busy: Boolean,
    onDismiss: () -> Unit,
    onMissingName: () -> Unit,
    onAddFromMenu: (picks: List<Pair<String, List<String>>>) -> Unit,
    onAddCustom: (clientId: String, name: String, priceCents: Long, note: String?) -> Unit,
    onAddPerson: (clientId: String, onAdded: () -> Unit) -> Unit,
    onCreatePerson: (name: String, requestKey: String, onAdded: (clientId: String) -> Unit) -> Unit,
) {
    // The Client picked here, if any. They can have been removed from the Appointment while the sheet is open.
    var pickedClientId by rememberSaveable { mutableStateOf(initialClientId) }
    val clientId = AppointmentClients.serviceClientId(appointment, pickedClientId)
    var personOpen by rememberSaveable { mutableStateOf(false) }
    // The person just added, until the Appointment read again lists them. Only while that is under way.
    var addedName by rememberSaveable { mutableStateOf<String?>(null) }
    val pending = addedName?.takeIf { busy && pickedClientId != null && !AppointmentClients.isIn(appointment, pickedClientId!!) }
    var customOpen by rememberSaveable { mutableStateOf(false) }
    // A menu that has loaded empty has nothing to pick from: the custom form is all there is.
    val custom = customOpen || menuLoad == LoadState.Loaded && menu.isEmpty()

    // The custom form keeps what is typed into it while the Stylist goes back to the menu and returns.
    var name by rememberSaveable { mutableStateOf("") }
    var price by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    // The price text a save was refused for. The reason stays under the field for as long as the text is that one.
    var refusedPrice by rememberSaveable { mutableStateOf<String?>(null) }

    // The ids of the picked items per person, each in the order they were tapped; an item that left the menu meanwhile is not picked.
    var picks by rememberSaveable(stateSaver = PicksSaver) { mutableStateOf(emptyMap<String, List<String>>()) }
    // Whose picks the menu shows: the person picked, also the one just added before the Appointment lists them.
    val pickingFor = if (pending != null) pickedClientId ?: clientId else clientId
    val people = appointment.participants.sortedByDescending { it.isPrimary }
    val toAdd = ServicePicks.toAdd(picks, menu, people.map { it.clientId })
    val pickedCount = toAdd.sumOf { it.items.size }
    // The family of the people, to add with one tap. The one being added is shown as picked meanwhile.
    val family = remember(people, clients) { Families.suggestions(people.map { it.clientId }, clients) }
        .filterNot { pending != null && it.id == pickedClientId }

    HcmTallBottomSheet(
        title = stringResource(if (custom) R.string.add_appointment_service else R.string.add_services_title),
        onDismiss = onDismiss,
        fillHeight = !custom,
        bottomBar = {
            if (custom) {
                PrimaryButton(
                    stringResource(R.string.add_ad_hoc_service),
                    onClick = {
                        val amount = Money.parse(price)

                        refusedPrice = price.takeIf { amount.isRefused() }
                        if (name.isBlank()) {
                            onMissingName()
                        } else if (amount is Amount.Valid) {
                            onAddCustom(clientId, name, amount.cents, note.ifBlank { null })
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    loading = busy,
                )
            } else {
                PrimaryButton(
                    if (pickedCount == 0) {
                        stringResource(R.string.add_services_pick)
                    } else {
                        pluralStringResource(R.plurals.add_services_count, pickedCount, pickedCount) + " · " +
                            Money.formatShort(toAdd.sumOf { person -> person.items.sumOf { it.defaultPriceCents } })
                    },
                    onClick = { onAddFromMenu(toAdd.map { person -> person.clientId to person.items.map { it.id } }) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = pickedCount > 0,
                    loading = busy,
                )
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.service_for_whom), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                people.forEach { participant ->
                    val count = ServicePicks.of(picks, menu, participant.clientId).size

                    ChoiceChip(
                        if (count > 0) participant.name + " · " + count else participant.name,
                        selected = pending == null && clientId == participant.clientId,
                        wraps = true,
                        onClick = { pickedClientId = participant.clientId },
                    )
                }
                pending?.let { pendingName ->
                    val count = ServicePicks.of(picks, menu, pickingFor).size

                    ChoiceChip(if (count > 0) pendingName + " · " + count else pendingName, selected = true, wraps = true, onClick = {})
                }
                family.forEach { member ->
                    ActionChip(
                        if (member.familyRole.isBlank()) member.name else member.name + " · " + member.familyRole,
                        icon = Icons.Rounded.Add,
                        onClick = {
                            if (!busy) {
                                onAddPerson(member.id) {
                                    pickedClientId = member.id
                                    addedName = member.name
                                }
                            }
                        },
                        description = stringResource(R.string.family_suggestion_description, member.name),
                        dashed = true,
                    )
                }
                ActionChip(
                    stringResource(R.string.service_for_whom_add),
                    icon = Icons.Rounded.Add,
                    onClick = { personOpen = true },
                    description = stringResource(R.string.service_for_whom_add_description),
                    dashed = true,
                )
            }
        }

        if (custom) {
            HcmTextField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.appointment_service_name_label),
                placeholder = stringResource(R.string.placeholder_service_name),
                maxLength = TextLimits.NAME,
            )
            AmountField(
                price,
                { price = it },
                label = stringResource(R.string.appointment_service_price_label),
                placeholder = "0.00",
                refusal = Money.parse(price).takeIf { price == refusedPrice },
            )
            HcmTextField(note, { note = it }, label = stringResource(R.string.appointment_service_note_label), placeholder = stringResource(R.string.optional), singleLine = false, minLines = 2, maxLength = TextLimits.NOTE)
            if (menu.isNotEmpty()) {
                TextAction(
                    stringResource(R.string.custom_service_back),
                    onClick = { customOpen = false },
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        } else {
            MenuChoices(
                menu = menu,
                menuLoad = menuLoad,
                onRetry = onRetry,
                pickedIds = picks[pickingFor].orEmpty(),
                onToggle = { id -> picks = ServicePicks.toggle(picks, menu, pickingFor, id) },
            )
            TextAction(
                stringResource(R.string.custom_service),
                onClick = { customOpen = true },
                icon = Icons.Rounded.EditNote,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }

    if (personOpen) {
        // One key for the person this picker is opened to create: a second try after a failure makes no second Client.
        val requestKey = rememberRequestKey(appointment.id, "person")

        // Over the sheet, which keeps what is picked in it meanwhile.
        ClientPickerSheet(
            title = stringResource(R.string.service_for_whom_add_title),
            clients = clients,
            load = clientsLoad,
            onRetry = onRetry,
            // The Clients of the Appointment are offered too, ticked: picking one of them only picks them here.
            selectedIds = appointment.participants.mapTo(HashSet()) { it.clientId },
            onDismiss = { personOpen = false },
            onPick = { client ->
                if (AppointmentClients.isIn(appointment, client.id)) {
                    pickedClientId = client.id
                    personOpen = false
                } else if (!busy) {
                    onAddPerson(client.id) {
                        pickedClientId = client.id
                        addedName = client.name
                        personOpen = false
                    }
                }
            },
            onCreateInline = { name ->
                if (!busy) {
                    onCreatePerson(name, requestKey) { id ->
                        pickedClientId = id
                        addedName = name
                        personOpen = false
                    }
                }
            },
            busy = busy,
        )
    }
}

/** The picks per person, saved as a map that a saved state can hold. */
private val PicksSaver = Saver<Map<String, List<String>>, HashMap<String, ArrayList<String>>>(
    save = { picks -> picks.mapValuesTo(HashMap()) { ArrayList(it.value) } },
    restore = { it },
)

/** The Service Menu to pick from: search, the most used items, and the groups. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MenuChoices(menu: List<ServiceMenuItem>, menuLoad: LoadState, onRetry: () -> Unit, pickedIds: List<String>, onToggle: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    // The groups the Stylist has opened, by their key. Every group is folded when the sheet opens.
    var openGroups by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val searching = query.isNotBlank()
    val whole = remember(menu) { MenuPicker.layout(menu) }
    val found = remember(menu, query) { MenuPicker.search(menu, query) }
    // Which items are the most used is taken once, from the menu the sheet opens with. The menu is
    // read again after each add, and a reply that came in while the sheet is open would otherwise
    // move these chips under the Stylist's finger.
    val frequentIds = rememberSaveable(menu.isNotEmpty()) { MenuPicker.frequent(menu).map { it.id } }
    val frequent = MenuPicker.picked(menu, frequentIds)

    if (menu.isEmpty()) {
        if (menuLoad is LoadState.Failed) {
            InlineLoadFailed(menuLoad.message, onRetry)
        } else {
            LoadingRow(Modifier.heightIn(max = 96.dp))
        }
        return
    }

    @Composable
    fun ItemChip(item: ServiceMenuItem, label: String) {
        ChoiceChip(
            // The price stays with its dot when a long name takes the chip onto more lines.
            label + " · " + Money.formatShort(item.defaultPriceCents),
            selected = item.id in pickedIds,
            wraps = true,
            onClick = { onToggle(item.id) },
        )
    }

    HcmTextField(
        value = query,
        onValueChange = { query = it },
        label = stringResource(R.string.service_search_placeholder),
        labelVisible = false,
        placeholder = stringResource(R.string.service_search_placeholder),
        leadingIcon = Icons.Rounded.Search,
        imeAction = ImeAction.Search,
        trailing = if (query.isEmpty()) null else {
            { IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.clear_search)) } }
        },
        maxLength = TextLimits.NAME,
    )

    if (!searching && frequent.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.service_group_frequent), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Out of their groups, so by their full names.
                frequent.forEach { ItemChip(it, it.name) }
            }
        }
    }

    when {
        found.isEmpty -> InlineEmpty(stringResource(R.string.service_search_empty, query.trim()))
        // A menu without groups is one list of chips.
        !whole.hasGroups -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            found.ungrouped.forEach { ItemChip(it, it.name) }
        }
        else -> Column {
            fun toggle(key: String) {
                openGroups = if (key in openGroups) openGroups - key else openGroups + key
            }

            found.groups.forEach { group ->
                // The price range is of the whole group, also while a search shows only some of it.
                val all = whole.groups.firstOrNull { it.key == group.key } ?: group

                GroupSection(
                    name = group.name,
                    range = Money.formatRange(all.lowestPriceCents, all.highestPriceCents),
                    open = searching || group.key in openGroups,
                    onToggle = { toggle(group.key) },
                ) {
                    group.items.forEach { ItemChip(it, MenuPicker.variantLabel(it)) }
                }
            }
            if (found.ungrouped.isNotEmpty()) {
                GroupSection(
                    name = stringResource(R.string.service_group_other),
                    range = Money.formatRange(whole.ungrouped.minOf { it.defaultPriceCents }, whole.ungrouped.maxOf { it.defaultPriceCents }),
                    open = searching || OTHER_GROUP in openGroups,
                    onToggle = { toggle(OTHER_GROUP) },
                ) {
                    found.ungrouped.forEach { ItemChip(it, it.name) }
                }
            }
        }
    }
}

/** A group of the menu: its name and price range on a header that opens and folds it, and its chips while it is open. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupSection(name: String, range: String, open: Boolean, onToggle: () -> Unit, chips: @Composable () -> Unit) {
    val state = stringResource(if (open) R.string.service_group_expanded else R.string.service_group_collapsed)

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics {
                    heading()
                    stateDescription = state
                }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null, modifier = Modifier.size(22.dp))
            Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(range, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(open) {
            FlowRow(
                Modifier.fillMaxWidth().padding(bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = { chips() },
            )
        }
        Hairline()
    }
}

/** The key of the items without a group among the open groups. No group has it: a group's key is never empty. */
private const val OTHER_GROUP = ""
