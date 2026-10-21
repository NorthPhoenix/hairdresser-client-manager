package com.northphoenix.hairdresserclientmanager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.data.ServiceMenuItem
import com.northphoenix.hairdresserclientmanager.domain.Amount
import com.northphoenix.hairdresserclientmanager.domain.MenuItemValues
import com.northphoenix.hairdresserclientmanager.domain.MenuPicker
import com.northphoenix.hairdresserclientmanager.domain.Money
import com.northphoenix.hairdresserclientmanager.domain.TextLimits
import com.northphoenix.hairdresserclientmanager.state.AppViewModel
import com.northphoenix.hairdresserclientmanager.state.LoadState
import com.northphoenix.hairdresserclientmanager.ui.components.ActionChip
import com.northphoenix.hairdresserclientmanager.ui.components.AmountField
import com.northphoenix.hairdresserclientmanager.ui.components.ConfirmDialog
import com.northphoenix.hairdresserclientmanager.ui.components.EmptyState
import com.northphoenix.hairdresserclientmanager.ui.components.HcmBottomSheet
import com.northphoenix.hairdresserclientmanager.ui.components.HcmCard
import com.northphoenix.hairdresserclientmanager.ui.components.HcmTextField
import com.northphoenix.hairdresserclientmanager.ui.components.LoadFailed
import com.northphoenix.hairdresserclientmanager.ui.components.PrimaryButton
import com.northphoenix.hairdresserclientmanager.ui.components.ScreenPadding
import com.northphoenix.hairdresserclientmanager.ui.components.SubScreen
import com.northphoenix.hairdresserclientmanager.ui.components.SwitchRow
import com.northphoenix.hairdresserclientmanager.ui.components.Tag
import com.northphoenix.hairdresserclientmanager.ui.components.rememberRequestKey
import com.northphoenix.hairdresserclientmanager.ui.components.TextAction

/** The editor sheet that is open. It is saved state, so it names the Service Menu Item by id only. */
internal sealed interface MenuEditor {
    data object New : MenuEditor

    data class Edit(val itemId: String) : MenuEditor

    companion object {
        /** No editor is saved as nothing. */
        val Saver = listSaver<MenuEditor?, String>(
            save = { editor ->
                when (editor) {
                    null -> emptyList()
                    New -> listOf("new")
                    is Edit -> listOf("edit", editor.itemId)
                }
            },
            restore = { saved ->
                when (saved.firstOrNull()) {
                    "new" -> New
                    "edit" -> saved.getOrNull(1)?.let(::Edit)
                    else -> null
                }
            },
        )
    }
}

/** Service Menu Items: the Stylist's standard services with default prices. */
@Composable
fun ServiceMenuScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val menu by viewModel.serviceMenu.collectAsStateWithLifecycle()
    val items = menu.data.orEmpty()
    val load = menu.state
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    var editor by rememberSaveable(stateSaver = MenuEditor.Saver) { mutableStateOf<MenuEditor?>(null) }

    SubScreen(
        title = stringResource(R.string.service_menu_title),
        onBack = onBack,
        saving = saving,
        floatingActionButton = {
            NewFab(stringResource(R.string.create_service_menu_item), Icons.Rounded.Add) { editor = MenuEditor.New }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = 4.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.service_menu_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            when (val state = load) {
                is LoadState.Failed -> item { LoadFailed(state.message, onRetry = viewModel::retryLoad) }
                LoadState.Loaded -> if (items.isEmpty()) {
                    item { EmptyState(Icons.Rounded.ContentCut, stringResource(R.string.service_menu_empty_title), body = stringResource(R.string.service_menu_empty_body)) }
                }
                else -> item { LoadingRow() }
            }

            // In the order the Stylist made them, in their groups as the add-service sheet shows them (see MenuPicker).
            val layout = MenuPicker.layout(items)

            layout.groups.forEach { group ->
                item(key = "group:" + group.key) { MenuGroupHeading(group.name) }
                items(group.items, key = { it.id }) { item -> MenuItemCard(item) { editor = MenuEditor.Edit(item.id) } }
            }
            if (layout.hasGroups && layout.ungrouped.isNotEmpty()) {
                item(key = "group:") { MenuGroupHeading(stringResource(R.string.service_group_other)) }
            }
            items(layout.ungrouped, key = { it.id }) { item -> MenuItemCard(item) { editor = MenuEditor.Edit(item.id) } }
        }
    }

    editor?.let { current ->
        val editedId = (current as? MenuEditor.Edit)?.itemId
        // Filled from the item when the sheet opens. What is typed after that is saved state of its
        // own, so it comes back after Android has recreated the screen, also before the menu has loaded.
        var name by rememberSaveable(current) { mutableStateOf(items.firstOrNull { it.id == editedId }?.name.orEmpty()) }
        var group by rememberSaveable(current) { mutableStateOf(items.firstOrNull { it.id == editedId }?.group.orEmpty()) }
        var travelFee by rememberSaveable(current) { mutableStateOf(items.firstOrNull { it.id == editedId }?.travelFee ?: false) }
        var price by rememberSaveable(current) {
            mutableStateOf(items.firstOrNull { it.id == editedId }?.let { Money.centsToPrice(it.defaultPriceCents) }.orEmpty())
        }
        // The price text a save was refused for. The reason stays under the field for as long as the text is that one.
        var refusedPrice by rememberSaveable(current) { mutableStateOf<String?>(null) }
        var confirmDelete by rememberSaveable(current) { mutableStateOf(false) }
        // Names the Service Menu Item this sheet creates, so that a second try of it creates no second one. Another one for a sheet opened again.
        val requestKey = rememberRequestKey(current)

        HcmBottomSheet(
            title = stringResource(if (editedId == null) R.string.create_service_menu_item else R.string.save_service_menu_item),
            onDismiss = { editor = null },
        ) {
            HcmTextField(name, { name = it }, label = stringResource(R.string.service_menu_name_label), placeholder = stringResource(R.string.placeholder_service_name), maxLength = TextLimits.NAME)
            GroupField(group, { group = it }, suggestions = MenuPicker.groupSuggestions(items, group))
            AmountField(
                price,
                { price = it },
                label = stringResource(R.string.service_menu_price_label),
                placeholder = "85.00",
                refusal = Money.parse(price).takeIf { price == refusedPrice },
            )
            TravelFeeField(travelFee, { travelFee = it }, replaces = items.firstOrNull { it.travelFee && it.id != editedId }?.takeIf { travelFee })
            PrimaryButton(
                stringResource(if (editedId == null) R.string.create_service_menu_item else R.string.save_service_menu_item),
                onClick = {
                    // A Service Menu Item states its default price: an empty field is refused like any text that is not an amount.
                    val amount = Money.parse(price)

                    refusedPrice = price.takeIf { amount.isRefused() }
                    if (name.isBlank()) {
                        viewModel.notify(R.string.service_name_required, isError = true)
                    } else if (amount is Amount.Valid) {
                        MenuItemValues.of(name, amount.cents, group, travelFee)?.let { values ->
                            viewModel.saveServiceMenuItem(editedId, values, requestKey) { editor = null }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                loading = busy,
            )
            if (editedId != null) {
                TextAction(
                    stringResource(R.string.delete_service_menu_item),
                    onClick = { confirmDelete = true },
                    icon = Icons.Rounded.DeleteOutline,
                    danger = true,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }

        if (confirmDelete && editedId != null) {
            ConfirmDialog(
                title = stringResource(R.string.delete_service_menu_item) + "?",
                // Named as it is saved, not as the field has it by now.
                body = stringResource(R.string.delete_service_menu_item_confirm_body, items.firstOrNull { it.id == editedId }?.name ?: name),
                confirmText = stringResource(R.string.delete),
                danger = true,
                onConfirm = {
                    // Not waited for: the sheet closes and the item leaves the menu now. It is back when the delete fails.
                    confirmDelete = false
                    editor = null
                    viewModel.deleteServiceMenuItem(editedId)
                },
                onDismiss = { confirmDelete = false },
            )
        }
    }
}

/** The name of a group above its items. */
@Composable
private fun MenuGroupHeading(name: String) {
    Text(
        name,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp).semantics { heading() },
    )
}

/** A Service Menu Item with its whole name, however long, and its default price. */
@Composable
private fun MenuItemCard(item: ServiceMenuItem, onClick: () -> Unit) {
    HcmCard(Modifier.fillMaxWidth(), onClick = onClick, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleLarge)
                if (item.travelFee) {
                    Tag(stringResource(R.string.service_menu_travel_fee_label), content = MaterialTheme.colorScheme.tertiary, container = MaterialTheme.colorScheme.tertiaryContainer)
                }
            }
            Text(Money.format(item.defaultPriceCents), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * Whether the item is the Travel Fee, which is added by itself to at-home Appointments. [replaces]
 * is the item that is the Travel Fee now and stops being it when this one is saved as it.
 */
@Composable
private fun TravelFeeField(checked: Boolean, onCheckedChange: (Boolean) -> Unit, replaces: ServiceMenuItem?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SwitchRow(stringResource(R.string.service_menu_travel_fee_label), checked, onCheckedChange)
        Text(
            if (replaces != null) {
                stringResource(R.string.service_menu_travel_fee_replaces, replaces.name)
            } else {
                stringResource(R.string.service_menu_travel_fee_hint)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The group of a Service Menu Item, with the Stylist's other groups to take with a tap: the same
 * group typed twice in two ways would be two groups.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupField(value: String, onValueChange: (String) -> Unit, suggestions: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HcmTextField(
            value,
            onValueChange,
            label = stringResource(R.string.service_menu_group_label),
            placeholder = stringResource(R.string.service_menu_group_placeholder),
            maxLength = TextLimits.NAME,
        )
        if (suggestions.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { suggestion -> ActionChip(suggestion, Icons.Rounded.Add, onClick = { onValueChange(suggestion) }) }
            }
        }
        Text(stringResource(R.string.service_menu_group_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
