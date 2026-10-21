package com.northphoenix.hairdresserclientmanager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.data.ClientProfile
import com.northphoenix.hairdresserclientmanager.domain.PhoneFormat
import com.northphoenix.hairdresserclientmanager.state.AppViewModel
import com.northphoenix.hairdresserclientmanager.ui.components.ChoiceChip
import com.northphoenix.hairdresserclientmanager.ui.components.HcmBottomSheet
import com.northphoenix.hairdresserclientmanager.ui.components.HcmCard
import com.northphoenix.hairdresserclientmanager.ui.components.PrimaryButton
import com.northphoenix.hairdresserclientmanager.ui.components.SecondaryButton
import com.northphoenix.hairdresserclientmanager.ui.components.SideBySideOrStacked

/**
 * "Possible duplicate: «Jessica Brown» has the same number", with Merge and Different people. Different
 * people hides it for this pair for good, also on the other Client's profile.
 */
@Composable
internal fun DuplicateBanner(other: ClientProfile, busy: Boolean, onMerge: () -> Unit, onDistinct: () -> Unit) {
    HcmCard(color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.client_duplicate_banner, other.name), style = MaterialTheme.typography.titleMedium)
                if (other.phone.isNotBlank()) {
                    Text(PhoneFormat.display(other.phone), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SideBySideOrStacked {
                SecondaryButton(stringResource(R.string.client_duplicate_merge), onClick = onMerge, enabled = !busy)
                SecondaryButton(stringResource(R.string.client_duplicate_distinct), onClick = onDistinct, enabled = !busy)
            }
        }
    }
}

/**
 * Merging [client] (whose profile is open) and [other]: the Stylist picks the name to keep, which is the
 * Client that stays; the other one's Appointments go to them, and the other one is deleted. Nothing changes
 * until the server has done all of it, so a merge that cannot be sent leaves both as they were.
 */
@Composable
internal fun MergeClientsSheet(viewModel: AppViewModel, client: ClientProfile, other: ClientProfile, onDismiss: () -> Unit, onMerged: (ClientProfile) -> Unit) {
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    var keepId by rememberSaveable(client.id, other.id) { mutableStateOf(client.id) }
    val kept = if (keepId == other.id) other else client
    val removed = if (keepId == other.id) client else other
    val history by rememberClientHistory(viewModel, client.id)
    val otherHistory by rememberClientHistory(viewModel, other.id)
    val both = history.data?.let { mine -> otherHistory.data?.let { theirs -> (mine.map { it.id } + theirs.map { it.id }).toSet().size } }

    HcmBottomSheet(title = stringResource(R.string.client_merge_title), onDismiss = { if (!busy) onDismiss() }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.client_merge_keep_name), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf(client, other).forEach { option ->
                ChoiceChip(option.name, selected = option.id == keepId, onClick = { keepId = option.id }, wraps = true)
            }
        }
        Text(
            when (both) {
                null -> stringResource(R.string.client_merge_counting)
                0 -> stringResource(R.string.client_merge_no_appointments)
                else -> pluralStringResource(R.plurals.client_merge_appointments, both, both, kept.name)
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.client_merge_details, kept.name, removed.name),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PrimaryButton(
            stringResource(R.string.client_merge_confirm),
            onClick = { viewModel.mergeClients(kept.id, removed.id, onSuccess = onMerged) },
            modifier = Modifier.fillMaxWidth(),
            loading = busy,
        )
    }
}
