package com.northphoenix.hairdresserclientmanager.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.domain.Amount
import com.northphoenix.hairdresserclientmanager.domain.Money
import com.northphoenix.hairdresserclientmanager.domain.TextLimits
import com.northphoenix.hairdresserclientmanager.state.AppViewModel
import com.northphoenix.hairdresserclientmanager.state.LoadState
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
    var editor by rememberSaveable(stateSaver = MenuEditor.Saver) { mutableStateOf<MenuEditor?>(null) }

    SubScreen(
        title = stringResource(R.string.service_menu_title),
        onBack = onBack,
        busy = busy,
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

            items(items, key = { it.id }) { item ->
                HcmCard(Modifier.fillMaxWidth(), onClick = { editor = MenuEditor.Edit(item.id) }, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(item.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(Money.format(item.defaultPriceCents), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }

    editor?.let { current ->
        val editedId = (current as? MenuEditor.Edit)?.itemId
        // Filled from the item when the sheet opens. What is typed after that is saved state of its
        // own, so it comes back after Android has recreated the screen, also before the menu has loaded.
        var name by rememberSaveable(current) { mutableStateOf(items.firstOrNull { it.id == editedId }?.name.orEmpty()) }
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
            AmountField(
                price,
                { price = it },
                label = stringResource(R.string.service_menu_price_label),
                placeholder = "85.00",
                refusal = Money.parse(price).takeIf { price == refusedPrice },
            )
            PrimaryButton(
                stringResource(if (editedId == null) R.string.create_service_menu_item else R.string.save_service_menu_item),
                onClick = {
                    // A Service Menu Item states its default price: an empty field is refused like any text that is not an amount.
                    val amount = Money.parse(price)

                    refusedPrice = price.takeIf { amount.isRefused() }
                    if (name.isBlank()) {
                        viewModel.notify(R.string.service_name_required, isError = true)
                    } else if (amount is Amount.Valid) {
                        viewModel.saveServiceMenuItem(editedId, name, amount.cents, requestKey) { editor = null }
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
                    confirmDelete = false
                    viewModel.deleteServiceMenuItem(editedId) { editor = null }
                },
                onDismiss = { confirmDelete = false },
            )
        }
    }
}
