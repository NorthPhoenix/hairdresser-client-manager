package com.northphoenix.hairdresserclientmanager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.data.LocationType
import com.northphoenix.hairdresserclientmanager.domain.AppointmentClients
import com.northphoenix.hairdresserclientmanager.domain.HomeOutcomes
import com.northphoenix.hairdresserclientmanager.domain.Money
import com.northphoenix.hairdresserclientmanager.i18n.label
import com.northphoenix.hairdresserclientmanager.ui.LocalTimeFormat
import com.northphoenix.hairdresserclientmanager.ui.theme.colors

val LocationType.icon get() = if (this == LocationType.AT_HOME) Icons.Rounded.Home else Icons.Rounded.Storefront

/**
 * One Appointment in a list: time on the left, who/where/what on the right. With [onOutcome] the
 * card ends in a row of outcome chips ([HomeOutcomes.choices]), and a tap on one gives it that
 * outcome; a tap anywhere else still opens the Appointment.
 */
@Composable
fun AppointmentCard(
    appointment: Appointment,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showDate: Boolean = false,
    onOutcome: ((AppointmentStatus) -> Unit)? = null,
) {
    val format = LocalTimeFormat.current
    // Named by a Client who is in it, also when its primary Client is not (AppointmentClients.Fault).
    val name = AppointmentClients.leadName(appointment)
    val others = AppointmentClients.othersCount(appointment)

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
                        name,
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
                StatusAndAmount {
                    StatusChip(appointment.status)
                    if (appointment.finalTotalCents > 0 || appointment.services.isNotEmpty()) {
                        Text(Money.format(appointment.finalTotalCents), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        if (onOutcome != null) {
            OutcomeChips(name, onOutcome, Modifier.padding(start = 18.dp, end = 18.dp, bottom = 16.dp))
        }
    }
}

/** The outcomes a card offers, under a hairline. Each chip is read out with the Client the card is of, as the chips of several cards sound alike. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OutcomeChips(name: String, onOutcome: (AppointmentStatus) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Hairline()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HomeOutcomes.choices.forEach { outcome ->
                val label = stringResource(outcome.label)

                ActionChip(
                    label,
                    icon = outcome.outcomeIcon,
                    onClick = { onOutcome(outcome) },
                    description = stringResource(R.string.home_outcome_chip_description, label, name),
                )
            }
        }
    }
}

private val AppointmentStatus.outcomeIcon: ImageVector
    get() = when (this) {
        AppointmentStatus.COMPLETED -> Icons.Rounded.Check
        AppointmentStatus.CANCELED -> Icons.Rounded.EventBusy
        AppointmentStatus.NO_SHOW -> Icons.Rounded.PersonOff
        AppointmentStatus.SCHEDULED -> Icons.Rounded.Schedule
    }

/**
 * The last line of a card: the status chip at the start and the amount at the end. When the two
 * do not fit beside each other, the amount goes on a line of its own under the chip. Each is
 * measured with the whole width, so the amount is never broken to fit what the chip leaves over;
 * in a Row it was, in the middle of the number, with a long chip text or a large font.
 */
@Composable
private fun StatusAndAmount(content: @Composable () -> Unit) = Layout(content, measurePolicy = StatusAndAmountPolicy)

private object StatusAndAmountPolicy : MeasurePolicy {
    private val between = 8.dp
    private val lineSpacing = 6.dp

    /** Whether items of these widths go on one line of [width]. */
    private fun Density.onOneLine(widths: List<Int>, width: Int) = widths.sum().toLong() + between.roundToPx() * (widths.size - 1) <= width

    private fun Density.heightOf(heights: List<Int>, onOneLine: Boolean) =
        if (onOneLine) heights.maxOrNull() ?: 0 else heights.sum() + lineSpacing.roundToPx() * (heights.size - 1)

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val onOneLine = onOneLine(placeables.map { it.width }, constraints.maxWidth)
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placeables.sumOf { it.width } + between.roundToPx() * (placeables.size - 1).coerceAtLeast(0)
        val height = heightOf(placeables.map { it.height }, onOneLine)

        return layout(width, height) {
            var y = 0

            placeables.forEachIndexed { index, placeable ->
                val x = if (index == 0) 0 else width - placeable.width

                placeable.placeRelative(x, if (onOneLine) (height - placeable.height) / 2 else y)
                y += placeable.height + lineSpacing.roundToPx()
            }
        }
    }

    // The card takes its height from this (IntrinsicSize.Min), so it has to be the height measure() arrives at.
    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int {
        val widths = measurables.map { minOf(it.maxIntrinsicWidth(Constraints.Infinity), width) }

        return heightOf(measurables.map { it.minIntrinsicHeight(width) }, onOneLine(widths, width))
    }

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int = minIntrinsicHeight(measurables, width)
}
