package com.northphoenix.hairdresserclientmanager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.LocationType
import com.northphoenix.hairdresserclientmanager.domain.Money
import com.northphoenix.hairdresserclientmanager.i18n.label
import com.northphoenix.hairdresserclientmanager.ui.LocalTimeFormat
import com.northphoenix.hairdresserclientmanager.ui.theme.colors

val LocationType.icon get() = if (this == LocationType.AT_HOME) Icons.Rounded.Home else Icons.Rounded.Storefront

/** One Appointment in a list: time on the left, who/where/what on the right. */
@Composable
fun AppointmentCard(appointment: Appointment, onClick: () -> Unit, modifier: Modifier = Modifier, showDate: Boolean = false) {
    val format = LocalTimeFormat.current
    val others = appointment.participants.count { !it.isPrimary }

    HcmCard(modifier.fillMaxWidth(), onClick = onClick, contentPadding = PaddingValues(0.dp)) {
        Row(Modifier.height(IntrinsicSize.Min).padding(vertical = 16.dp)) {
            Column(
                Modifier.widthIn(min = 84.dp).padding(start = 18.dp, end = 14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (showDate) {
                    Kicker(format.shortDate(format.zoned(appointment.startsAt).toLocalDate()), color = appointment.status.colors().content)
                }
                Text(format.time(appointment.startsAt), style = MaterialTheme.typography.headlineMedium)
                appointment.endsAt?.let {
                    Text(format.time(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box(Modifier.width(3.dp).fillMaxHeight().clip(CircleShape).background(appointment.status.colors().content.copy(alpha = 0.7f)))
            Column(Modifier.weight(1f).padding(start = 14.dp, end = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        appointment.primaryClientName,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (others > 0) {
                        Tag("+$others")
                    }
                }
                IconLine(
                    appointment.locationType.icon,
                    listOf(stringResource(appointment.locationType.label), appointment.locationAddress).filter { it.isNotBlank() }.joinToString(" · "),
                    maxLines = 1,
                )
                if (appointment.services.isNotEmpty()) {
                    IconLine(Icons.Rounded.ContentCut, appointment.services.joinToString(", ") { it.name }, maxLines = 1)
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(appointment.status)
                    Spacer(Modifier.weight(1f))
                    if (appointment.finalTotalCents > 0 || appointment.services.isNotEmpty()) {
                        Text(Money.format(appointment.finalTotalCents), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}
