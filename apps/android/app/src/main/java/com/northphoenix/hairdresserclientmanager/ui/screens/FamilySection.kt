package com.northphoenix.hairdresserclientmanager.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.data.ClientFamily
import com.northphoenix.hairdresserclientmanager.data.ClientProfile
import com.northphoenix.hairdresserclientmanager.domain.Families
import com.northphoenix.hairdresserclientmanager.domain.OneLine
import com.northphoenix.hairdresserclientmanager.domain.TextLimits
import com.northphoenix.hairdresserclientmanager.state.AppViewModel
import com.northphoenix.hairdresserclientmanager.state.LoadState
import com.northphoenix.hairdresserclientmanager.state.Read
import com.northphoenix.hairdresserclientmanager.ui.components.Avatar
import com.northphoenix.hairdresserclientmanager.ui.components.ChoiceChip
import com.northphoenix.hairdresserclientmanager.ui.components.ConfirmDialog
import com.northphoenix.hairdresserclientmanager.ui.components.Hairline
import com.northphoenix.hairdresserclientmanager.ui.components.HcmBottomSheet
import com.northphoenix.hairdresserclientmanager.ui.components.HcmCard
import com.northphoenix.hairdresserclientmanager.ui.components.HcmTextField
import com.northphoenix.hairdresserclientmanager.ui.components.PrimaryButton
import com.northphoenix.hairdresserclientmanager.ui.components.TextAction
import com.northphoenix.hairdresserclientmanager.ui.components.collectWhileOpen
import com.northphoenix.hairdresserclientmanager.ui.components.rememberRequestKey

/**
 * The Client's Family on the Client Profile (CONTEXT.md: Family): the other Clients in it, each with
 * their role and visits, a tap opening their profile and a menu to change their role or take them
 * out, and "Add to family", which picks an existing Client or makes a new one from a typed name
 * and then asks for the role. Without a Family it is only that button.
 *
 * Who is in the Family comes from the Client list, so a change shows at once; the visits come from
 * `clientProfile.family` and are left out until it has answered.
 */
@Composable
internal fun FamilySection(
    viewModel: AppViewModel,
    client: ClientProfile,
    clients: List<ClientProfile>,
    clientsLoad: LoadState,
    busy: Boolean,
    onOpenClient: (String) -> Unit,
) {
    val members = Families.members(client, clients)
    val family by rememberClientFamily(viewModel, client.id)
    val visits = family.data?.members.orEmpty().associate { it.id to it.visits }
    var picking by rememberSaveable(client.id) { mutableStateOf(false) }
    // The role sheet: for a Client picked to add, a new one by name, or a member whose role changes.
    var addingId by rememberSaveable(client.id) { mutableStateOf<String?>(null) }
    var addingName by rememberSaveable(client.id) { mutableStateOf<String?>(null) }
    var roleOf by rememberSaveable(client.id) { mutableStateOf<String?>(null) }
    var removing by rememberSaveable(client.id) { mutableStateOf<String?>(null) }

    if (members.isEmpty()) {
        TextAction(stringResource(R.string.family_add), onClick = { picking = true }, icon = Icons.Rounded.Add, enabled = !busy)
    } else {
        HcmCard(contentPadding = PaddingValues(start = 18.dp, end = 6.dp, top = 14.dp, bottom = 6.dp)) {
            Text(
                stringResource(R.string.family_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            members.forEach { member ->
                val count = visits[member.id]
                val visitText = when {
                    count == null -> null
                    count == 0 -> stringResource(R.string.family_no_visits)
                    else -> pluralStringResource(R.plurals.family_visits, count, count)
                }

                FamilyMemberRow(
                    member,
                    subtitle = Families.subtitle(member.familyRole, visitText),
                    busy = busy,
                    onOpen = { onOpenClient(member.id) },
                    onEditRole = { roleOf = member.id },
                    onRemove = { removing = member.id },
                )
                Hairline(Modifier.padding(end = 12.dp))
            }
            TextAction(stringResource(R.string.family_add), onClick = { picking = true }, icon = Icons.Rounded.Add, enabled = !busy)
        }
    }

    if (picking) {
        ClientPickerSheet(
            title = stringResource(R.string.family_add_title),
            clients = clients,
            load = clientsLoad,
            onRetry = viewModel::retryLoad,
            selectedIds = emptySet(),
            takenIds = members.mapTo(HashSet()) { it.id } + client.id,
            onDismiss = { picking = false },
            onPick = { picked ->
                picking = false
                addingId = picked.id
            },
            onCreateInline = { name ->
                picking = false
                addingName = name
            },
        )
    }

    val picked = addingId?.let { id -> clients.firstOrNull { it.id == id } }

    if (picked != null || addingName != null) {
        // One key for the person this sheet is opened to create: a second try after a failure makes no second Client.
        val requestKey = rememberRequestKey(client.id, addingName, "family")

        RoleSheet(
            name = picked?.name ?: addingName.orEmpty(),
            initialRole = "",
            confirmText = stringResource(R.string.family_add_confirm),
            note = picked?.takeIf { Families.movesFromOtherFamily(client, it) }?.let { stringResource(R.string.family_moves_note, it.name) },
            busy = busy,
            onDismiss = {
                addingId = null
                addingName = null
            },
            onConfirm = { role ->
                val done = {
                    addingId = null
                    addingName = null
                }

                if (picked != null) {
                    viewModel.addFamilyMember(client.id, picked.id, role, onSuccess = done)
                } else {
                    viewModel.addNewFamilyMember(client.id, addingName.orEmpty(), role, requestKey, onSuccess = done)
                }
            },
        )
    }

    members.firstOrNull { it.id == roleOf }?.let { member ->
        RoleSheet(
            name = member.name,
            initialRole = member.familyRole,
            confirmText = stringResource(R.string.family_role_save),
            note = null,
            busy = busy,
            onDismiss = { roleOf = null },
            onConfirm = { role -> viewModel.setFamilyRole(member.id, role) { roleOf = null } },
        )
    }

    members.firstOrNull { it.id == removing }?.let { member ->
        ConfirmDialog(
            title = stringResource(R.string.family_remove_title, member.name),
            body = stringResource(if (Families.leavingEndsFamily(member, clients)) R.string.family_remove_body_last else R.string.family_remove_body),
            confirmText = stringResource(R.string.family_remove),
            danger = true,
            onDismiss = { removing = null },
            onConfirm = {
                removing = null
                viewModel.leaveFamily(member.id)
            },
        )
    }
}

/** A Client's Family with the visits of each, read for as long as the profile is open (as [rememberClientHistory]). */
@Composable
private fun rememberClientFamily(viewModel: AppViewModel, clientId: String): State<Read<ClientFamily>> {
    val family = remember(viewModel, clientId) { viewModel.clientFamily(clientId) }.collectWhileOpen(
        initialValue = remember(viewModel, clientId) { viewModel.shownClientFamily(clientId) },
    )

    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.loadClientFamily(clientId) }

    return family
}

/** One other Client of the Family: avatar, name, "сын · 2 визита"; a tap opens them, the menu changes their role or takes them out. */
@Composable
private fun FamilyMemberRow(
    member: ClientProfile,
    subtitle: String,
    busy: Boolean,
    onOpen: () -> Unit,
    onEditRole: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.weight(1f).clickable(role = Role.Button, onClick = onOpen).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Avatar(member.name, size = 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // A long name wraps: the row grows rather than cut it.
                Text(member.name, style = MaterialTheme.typography.titleMedium)
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }, enabled = !busy) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = stringResource(R.string.family_member_actions, member.name),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = MaterialTheme.colorScheme.surface) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.family_edit_role), style = MaterialTheme.typography.bodyLarge) },
                    onClick = {
                        menuOpen = false
                        onEditRole()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.family_remove), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        menuOpen = false
                        onRemove()
                    },
                )
            }
        }
    }
}

/**
 * The role of [name] in the Family: a field of one short line, the usual roles to tap, and [confirmText]. The role is
 * the Stylist's own words and may be left empty. [note] says what else the change does, such as moving a Client from
 * another Family.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoleSheet(
    name: String,
    initialRole: String,
    confirmText: String,
    note: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var role by rememberSaveable(name) { mutableStateOf(initialRole) }
    val suggestions = stringArrayResource(R.array.family_role_suggestions)

    HcmBottomSheet(title = stringResource(R.string.family_role_title), subtitle = name, onDismiss = onDismiss) {
        HcmTextField(
            value = role,
            onValueChange = { role = OneLine.typed(it) },
            label = stringResource(R.string.family_role_label),
            placeholder = stringResource(R.string.family_role_placeholder),
            capitalization = KeyboardCapitalization.None,
            imeAction = ImeAction.Done,
            maxLength = TextLimits.FAMILY_ROLE,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.forEach { suggestion ->
                ChoiceChip(suggestion, selected = OneLine.clean(role).equals(suggestion, ignoreCase = true), onClick = { role = suggestion })
            }
        }
        if (note != null) {
            Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PrimaryButton(confirmText, onClick = { onConfirm(OneLine.clean(role)) }, modifier = Modifier.fillMaxWidth(), loading = busy)
    }
}
