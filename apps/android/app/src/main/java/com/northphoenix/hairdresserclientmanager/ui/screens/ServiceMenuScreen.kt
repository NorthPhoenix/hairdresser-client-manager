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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.data.ServiceMenuItem
import com.northphoenix.hairdresserclientmanager.domain.Money
import com.northphoenix.hairdresserclientmanager.state.AppViewModel
import com.northphoenix.hairdresserclientmanager.ui.components.EmptyState
import com.northphoenix.hairdresserclientmanager.ui.components.HcmBottomSheet
import com.northphoenix.hairdresserclientmanager.ui.components.HcmCard
import com.northphoenix.hairdresserclientmanager.ui.components.HcmTextField
import com.northphoenix.hairdresserclientmanager.ui.components.PrimaryButton
import com.northphoenix.hairdresserclientmanager.ui.components.ScreenPadding
import com.northphoenix.hairdresserclientmanager.ui.components.SubScreen
import com.northphoenix.hairdresserclientmanager.ui.components.TextAction

private sealed interface MenuEditor {
    data object New : MenuEditor

    data class Edit(val item: ServiceMenuItem) : MenuEditor
}

/** Service Menu Items: the Stylist's standard services with default prices. */
@Composable
fun ServiceMenuScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val items by viewModel.serviceMenu.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    var editor by remember { mutableStateOf<MenuEditor?>(null) }

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

            if (items.isEmpty()) {
                item { EmptyState(Icons.Rounded.ContentCut, stringResource(R.string.service_menu_empty_title), body = stringResource(R.string.service_menu_empty_body)) }
            }

            items(items, key = { it.id }) { item ->
                HcmCard(Modifier.fillMaxWidth(), onClick = { editor = MenuEditor.Edit(item) }, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(item.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(Money.format(item.defaultPriceCents), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }

    editor?.let { current ->
        val existing = (current as? MenuEditor.Edit)?.item
        var name by remember(current) { mutableStateOf(existing?.name.orEmpty()) }
        var price by remember(current) { mutableStateOf(existing?.let { Money.centsToPrice(it.defaultPriceCents) }.orEmpty()) }

        HcmBottomSheet(
            title = stringResource(if (existing == null) R.string.create_service_menu_item else R.string.save_service_menu_item),
            onDismiss = { editor = null },
        ) {
            HcmTextField(name, { name = it }, label = stringResource(R.string.service_menu_name_label), placeholder = stringResource(R.string.placeholder_service_name))
            HcmTextField(price, { price = it }, label = stringResource(R.string.service_menu_price_label), placeholder = "85.00", prefix = "$", keyboardType = KeyboardType.Decimal)
            PrimaryButton(
                stringResource(if (existing == null) R.string.create_service_menu_item else R.string.save_service_menu_item),
                onClick = {
                    if (name.isBlank()) {
                        viewModel.notify(R.string.service_name_required, isError = true)
                    } else {
                        viewModel.saveServiceMenuItem(existing?.id, name, Money.priceToCents(price)) { editor = null }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                loading = busy,
            )
            if (existing != null) {
                TextAction(
                    stringResource(R.string.delete_service_menu_item),
                    onClick = { viewModel.deleteServiceMenuItem(existing.id) { editor = null } },
                    icon = Icons.Rounded.DeleteOutline,
                    danger = true,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}
