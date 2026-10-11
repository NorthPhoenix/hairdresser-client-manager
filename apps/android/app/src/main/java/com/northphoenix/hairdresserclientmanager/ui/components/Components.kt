package com.northphoenix.hairdresserclientmanager.ui.components

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.domain.Amount
import com.northphoenix.hairdresserclientmanager.domain.ClientRules
import com.northphoenix.hairdresserclientmanager.domain.Money
import com.northphoenix.hairdresserclientmanager.domain.OneLine
import com.northphoenix.hairdresserclientmanager.i18n.label
import com.northphoenix.hairdresserclientmanager.i18n.refusalRes
import com.northphoenix.hairdresserclientmanager.ui.theme.DisplayFamily
import com.northphoenix.hairdresserclientmanager.ui.theme.HcmColors
import com.northphoenix.hairdresserclientmanager.ui.theme.colors

val ScreenPadding = 20.dp

/** The standard raised panel: warm surface, hairline border, no shadow. */
@Composable
fun HcmCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val body: @Composable () -> Unit = { Column(Modifier.fillMaxWidth().padding(contentPadding), content = content) }

    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = color, border = BorderStroke(1.dp, borderColor), content = body)
    } else {
        Surface(modifier = modifier, shape = shape, color = color, border = BorderStroke(1.dp, borderColor), content = body)
    }
}

/** Small all-caps label used above sections and screen titles. */
@Composable
fun Kicker(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text.uppercase(), modifier = modifier, style = MaterialTheme.typography.labelSmall, color = color)
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        action?.invoke(this)
    }
}

/** Large serif screen heading with an optional kicker above and supporting line below. */
@Composable
fun ScreenTitle(title: String, modifier: Modifier = Modifier, kicker: String? = null, subtitle: String? = null) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (kicker != null) {
            Kicker(kicker, color = MaterialTheme.colorScheme.primary)
        }
        Text(title, style = MaterialTheme.typography.displaySmall)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 54.dp),
        enabled = enabled && !loading,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = HcmColors.Ink,
            contentColor = HcmColors.Surface,
            disabledContainerColor = HcmColors.Ink.copy(alpha = 0.35f),
            disabledContentColor = HcmColors.Surface,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), color = HcmColors.Surface, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    danger: Boolean = false,
) {
    val tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 50.dp),
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, if (enabled) tint.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = tint),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

@Composable
fun TextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Text field with its label above, the way forms read throughout the app. [error] is why the
 * text was refused: it is shown under the field, which scrolls into view when it appears. Where
 * the Stylist can scroll away and be refused for the same text again, count the refusals in
 * [errorAttempt]: the field then comes back into view on each of them. [onImeAction] is what the
 * keyboard's own action key ([imeAction]) does. [contentType] says what the field holds to the
 * device's password manager, which then offers what it has saved for it and to save what is typed.
 */
@Composable
fun HcmTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    imeAction: ImeAction = ImeAction.Default,
    leadingIcon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    prefix: String? = null,
    enabled: Boolean = true,
    error: String? = null,
    errorAttempt: Int = 0,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    onImeAction: (() -> Unit)? = null,
    contentType: ContentType? = null,
) {
    val intoView = remember { BringIntoViewRequester() }

    LaunchedEffect(error, errorAttempt) {
        if (error != null) {
            intoView.bringIntoView()
        }
    }

    Column(modifier.bringIntoViewRequester(intoView), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        if (label.isNotEmpty()) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(
            value = value,
            // A one-line field shows one line only; text pasted with a line break or a control character must not hold more.
            onValueChange = { onValueChange(if (singleLine) OneLine.typed(it) else it) },
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = label
                // The reason itself is what a screen reader says, in place of Material's "Invalid input".
                if (error != null) {
                    this.error(error)
                }
                if (contentType != null) {
                    this.contentType = contentType
                }
            },
            enabled = enabled,
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            placeholder = placeholder?.let { { Text(it, color = HcmColors.Faint) } },
            leadingIcon = leadingIcon?.let { { Icon(it, contentDescription = null, modifier = Modifier.size(20.dp)) } },
            trailingIcon = trailing,
            prefix = prefix?.let { { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
            singleLine = singleLine,
            minLines = minLines,
            maxLines = if (singleLine) 1 else 8,
            textStyle = MaterialTheme.typography.bodyLarge,
            shape = MaterialTheme.shapes.medium,
            visualTransformation = visualTransformation,
            keyboardOptions = KeyboardOptions(capitalization = capitalization, keyboardType = keyboardType, imeAction = imeAction),
            keyboardActions = onImeAction?.let { action -> KeyboardActions { action() } } ?: KeyboardActions.Default,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedLeadingIconColor = MaterialTheme.colorScheme.primary,
                unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
    }
}

/**
 * A field for an amount of money. [refusal] is what a save made of its text when it refused it,
 * and null otherwise: see [Money.parse]. Nothing but an [Amount.Valid] is ever saved.
 */
@Composable
fun AmountField(value: String, onValueChange: (String) -> Unit, label: String, placeholder: String, modifier: Modifier = Modifier, refusal: Amount? = null) {
    val error = when (val reason = refusal?.refusalRes()) {
        null -> null
        R.string.amount_too_large -> stringResource(reason, Money.format(Money.MAX_CENTS))
        else -> stringResource(reason)
    }

    HcmTextField(value, onValueChange, label, modifier, placeholder = placeholder, prefix = "$", keyboardType = KeyboardType.Decimal, error = error)
}

/**
 * A read-only field that opens a picker, styled to sit beside [HcmTextField].
 * With [wraps] the value is shown whole, on as many lines as it takes: a date or a time cut short says another one.
 */
@Composable
fun PickerField(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    isPlaceholder: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    wraps: Boolean = false,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Row(
                Modifier.heightIn(min = 56.dp).padding(start = 14.dp, end = if (trailing != null) 4.dp else 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    value,
                    // The padding only shows once the value takes more lines than the field's least height holds.
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isPlaceholder) HcmColors.Faint else MaterialTheme.colorScheme.onSurface,
                    maxLines = if (wraps) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                trailing?.invoke()
            }
        }
    }
}

/** Two-or-more option pill selector. A screen reader is told which option is the chosen one. */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val container by animateColorAsState(if (isSelected) HcmColors.Ink else Color.Transparent, label = "segment")
            val content by animateColorAsState(if (isSelected) HcmColors.Surface else MaterialTheme.colorScheme.onSurface, label = "segmentText")

            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(container)
                    .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(option) }
                    .defaultMinSize(minHeight = 44.dp)
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(option), style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * A tappable chip for picking one of a few values inline. A screen reader is told whether it is
 * the chosen one: put the chips of one choice in a `selectableGroup()`. A chip with [wraps] shows
 * all of its text on as many lines as that takes; the others cut it at one line.
 */
@Composable
fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, wraps: Boolean = false) {
    Chip(
        text,
        highlighted = selected,
        onClick = onClick,
        modifier = modifier.semantics {
            role = Role.RadioButton
            this.selected = selected
        },
        wraps = wraps,
    )
}

/**
 * A chip that does something when tapped ('Add Client', a chosen Client that the tap takes away
 * again): a button, where a [ChoiceChip] is an option. [description] is read out in place of
 * [text] when the text alone does not say what the tap does.
 */
@Composable
fun ActionChip(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, highlighted: Boolean = false, description: String? = null) {
    Chip(
        text,
        highlighted = highlighted,
        onClick = onClick,
        modifier = modifier.semantics {
            role = Role.Button
            if (description != null) contentDescription = description
        },
        icon = icon,
    )
}

@Composable
private fun Chip(text: String, highlighted: Boolean, onClick: () -> Unit, modifier: Modifier, icon: ImageVector? = null, wraps: Boolean = false) {
    val container by animateColorAsState(
        if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
        label = "chip",
    )
    val border = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val content = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface

    Surface(
        onClick = onClick,
        modifier = modifier,
        // The corners of a one-line chip, kept at that size when the chip grows taller.
        shape = if (wraps) RoundedCornerShape(20.dp) else CircleShape,
        color = container,
        border = BorderStroke(if (highlighted) 1.5.dp else 1.dp, border),
    ) {
        Row(
            Modifier.heightIn(min = 40.dp).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = content)
            }
            Text(text, style = MaterialTheme.typography.labelMedium, color = content, maxLines = if (wraps) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun StatusChip(status: AppointmentStatus, modifier: Modifier = Modifier) {
    val colors = status.colors()

    Tag(stringResource(status.label), modifier, content = colors.content, container = colors.container, dot = true)
}

@Composable
fun Tag(
    text: String,
    modifier: Modifier = Modifier,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    dot: Boolean = false,
) {
    Row(
        modifier.clip(CircleShape).background(container).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(content))
        }
        Text(text, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = MaterialTheme.typography.labelMedium.letterSpacing), color = content, maxLines = 1)
    }
}

private val avatarPalette = listOf(
    Color(0xFFF3E0D6) to Color(0xFF7D2F1B),
    Color(0xFFDCEBE0) to Color(0xFF2E6B4A),
    Color(0xFFF7E7C9) to Color(0xFF8F5600),
    Color(0xFFE2E4F1) to Color(0xFF3F4A86),
    Color(0xFFEFDDEA) to Color(0xFF7A3566),
    Color(0xFFDDEBEE) to Color(0xFF256273),
)

/** Initials on a tint derived from the name, so the same Client always gets the same colour. */
@Composable
fun Avatar(name: String, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val (container, content) = remember(name) { avatarPalette[Math.floorMod(name.hashCode(), avatarPalette.size)] }
    val initials = remember(name) { ClientRules.initials(name) }

    Box(modifier.size(size).clip(CircleShape).background(container), contentAlignment = Alignment.Center) {
        Text(
            initials,
            color = content,
            fontFamily = DisplayFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = MaterialTheme.typography.titleMedium.fontSize * (size.value / 44f),
            // Initials wider than the circle (two emoji at a large font size) show the first one whole, not two cut lines.
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, modifier: Modifier = Modifier, body: String? = null, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(2.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        if (body != null) {
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        if (action != null) {
            Spacer(Modifier.height(6.dp))
            action()
        }
    }
}

/** Quieter empty hint for use inside a card. */
@Composable
fun InlineEmpty(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Stands where data that failed to load would be: why it failed and a way to try again. Never an empty state. */
@Composable
fun LoadFailed(@StringRes message: Int, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    EmptyState(Icons.Rounded.CloudOff, stringResource(R.string.load_failed_title), modifier, body = stringResource(message)) {
        PrimaryButton(stringResource(R.string.retry), onClick = onRetry)
    }
}

/** [LoadFailed] for the inside of a card or sheet. */
@Composable
fun InlineLoadFailed(@StringRes message: Int, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(stringResource(message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        // Pull the text button's own padding back so its label lines up with the message.
        TextAction(stringResource(R.string.retry), onClick = onRetry, modifier = Modifier.offset(x = (-10).dp))
    }
}

/** Stands where one item would be that the server no longer has, with the way back. */
@Composable
fun ItemMissing(title: String, body: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    EmptyState(Icons.Rounded.SearchOff, title, modifier, body = body) {
        SecondaryButton(stringResource(R.string.back), onClick = onBack)
    }
}

@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
    }
}

/** A 44 dp circle. It takes the 48 dp of a touch target wherever it stands: do not give it a smaller size. */
@Composable
fun CircleIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        // The size is the circle's own, inside the Surface: on the Surface it would also cut the touch target down to 44 dp.
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false,
    dismissText: String = stringResource(R.string.cancel),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = { Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        confirmButton = { TextAction(confirmText, onClick = onConfirm, danger = danger) },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge,
    )
}

/** Label on the left, value on the right. */
@Composable
fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier, emphasized: Boolean = false) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = if (emphasized) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            color = if (emphasized) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = if (emphasized) MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp) else MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

/** Icon followed by a line of supporting text. */
@Composable
fun IconLine(icon: ImageVector, text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines: Int = 2) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = color)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

/** Swallows clicks without a ripple, for scrims over content. */
fun Modifier.consumeClicks(): Modifier = clickable(interactionSource = MutableInteractionSource(), indication = null) {}

@Composable
fun dashedBorderModifier(): Modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
