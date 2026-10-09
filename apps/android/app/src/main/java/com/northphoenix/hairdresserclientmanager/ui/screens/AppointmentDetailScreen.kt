package com.northphoenix.hairdresserclientmanager.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentParticipant
import com.northphoenix.hairdresserclientmanager.data.AppointmentPhoto
import com.northphoenix.hairdresserclientmanager.data.AppointmentService
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.data.ColorFormula
import com.northphoenix.hairdresserclientmanager.data.PhotoCategory
import com.northphoenix.hairdresserclientmanager.data.PhotoStatus
import com.northphoenix.hairdresserclientmanager.data.ServiceMenuItem
import com.northphoenix.hairdresserclientmanager.domain.ClientReminder
import com.northphoenix.hairdresserclientmanager.domain.Money
import com.northphoenix.hairdresserclientmanager.domain.TimeFormat
import com.northphoenix.hairdresserclientmanager.i18n.label
import com.northphoenix.hairdresserclientmanager.i18n.inLanguage
import com.northphoenix.hairdresserclientmanager.i18n.toLocale
import com.northphoenix.hairdresserclientmanager.state.AppViewModel
import com.northphoenix.hairdresserclientmanager.ui.LocalTimeFormat
import com.northphoenix.hairdresserclientmanager.ui.components.Avatar
import com.northphoenix.hairdresserclientmanager.ui.components.ChoiceChip
import com.northphoenix.hairdresserclientmanager.ui.components.ConfirmDialog
import com.northphoenix.hairdresserclientmanager.ui.components.Hairline
import com.northphoenix.hairdresserclientmanager.ui.components.HcmBottomSheet
import com.northphoenix.hairdresserclientmanager.ui.components.HcmCard
import com.northphoenix.hairdresserclientmanager.ui.components.HcmTextField
import com.northphoenix.hairdresserclientmanager.ui.components.IconLine
import com.northphoenix.hairdresserclientmanager.ui.components.InlineEmpty
import com.northphoenix.hairdresserclientmanager.ui.components.Kicker
import com.northphoenix.hairdresserclientmanager.ui.components.LabeledValue
import com.northphoenix.hairdresserclientmanager.ui.components.PrimaryButton
import com.northphoenix.hairdresserclientmanager.ui.components.ScreenColumn
import com.northphoenix.hairdresserclientmanager.ui.components.SecondaryButton
import com.northphoenix.hairdresserclientmanager.ui.components.SegmentedControl
import com.northphoenix.hairdresserclientmanager.ui.components.StatusChip
import com.northphoenix.hairdresserclientmanager.ui.components.SubScreen
import com.northphoenix.hairdresserclientmanager.ui.components.Tag
import com.northphoenix.hairdresserclientmanager.ui.components.TextAction
import com.northphoenix.hairdresserclientmanager.ui.components.icon
import com.northphoenix.hairdresserclientmanager.ui.openUri
import com.northphoenix.hairdresserclientmanager.ui.theme.HcmColors
import java.io.File

private const val MAX_GALLERY_PHOTOS = 10

private sealed interface DetailSheet {
    data object AddClient : DetailSheet

    data object AddService : DetailSheet

    data object CopyServices : DetailSheet

    data class Formula(val service: AppointmentService, val formula: ColorFormula?) : DetailSheet

    /** [replacing] is a failed photo being retried. */
    data class AddPhoto(val participant: AppointmentParticipant, val replacing: AppointmentPhoto? = null) : DetailSheet

    data class Photo(val photoId: String) : DetailSheet
}

/** Everything about one Appointment: outcome, Clients, Services with Color Formulas, totals, photos and note. */
@Composable
fun AppointmentDetailScreen(
    viewModel: AppViewModel,
    appointmentId: String,
    startsAt: String,
    onBack: () -> Unit,
    onOpenClient: (String) -> Unit,
) {
    val appointments by viewModel.appointments.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val appointment = appointments[appointmentId]

    LaunchedEffect(appointmentId) {
        if (startsAt.isNotEmpty()) {
            viewModel.ensureAppointment(appointmentId, startsAt)
        }
    }

    if (appointment == null) {
        SubScreen(title = "", onBack = onBack, busy = busy) { padding -> LoadingRow(Modifier.padding(padding)) }
        return
    }

    AppointmentDetail(viewModel, appointment, busy, onBack, onOpenClient)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppointmentDetail(
    viewModel: AppViewModel,
    appointment: Appointment,
    busy: Boolean,
    onBack: () -> Unit,
    onOpenClient: (String) -> Unit,
) {
    val context = LocalContext.current
    val format = LocalTimeFormat.current
    val clients by viewModel.clients.collectAsStateWithLifecycle()
    val serviceMenu by viewModel.serviceMenu.collectAsStateWithLifecycle()
    val completedSources by viewModel.completedSources.collectAsStateWithLifecycle()
    val uploadingPhoto by viewModel.uploadingPhoto.collectAsStateWithLifecycle()
    val isGroup = appointment.participants.size > 1
    var sheet by remember { mutableStateOf<DetailSheet?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    // The photo target has to survive the trip to the camera or gallery and back.
    var photoClientId by rememberSaveable { mutableStateOf("") }
    var photoCategory by rememberSaveable { mutableStateOf(PhotoCategory.AFTER) }
    var photoReplacingId by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }

    fun upload(uris: List<Uri>) = viewModel.addPhotos(appointment.id, photoClientId, photoCategory, uris, photoReplacingId)

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_GALLERY_PHOTOS), ::upload)
    // A retry replaces one failed photo, so it picks exactly one.
    val galleryRetry = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { upload(listOf(it)) } }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = cameraUri
        if (saved && uri != null) {
            upload(listOf(uri))
        }
    }

    fun startPhoto(participant: AppointmentParticipant, category: PhotoCategory, replacing: AppointmentPhoto?, fromCamera: Boolean) {
        photoClientId = participant.clientId
        photoCategory = category
        photoReplacingId = replacing?.id
        sheet = null

        try {
            if (fromCamera) {
                val file = File(File(context.cacheDir, "camera").apply { mkdirs() }, "capture-${System.currentTimeMillis()}.jpg")
                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                cameraUri = uri
                camera.launch(uri)
            } else {
                val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                if (replacing == null) gallery.launch(request) else galleryRetry.launch(request)
            }
        } catch (_: ActivityNotFoundException) {
            viewModel.notify(R.string.appointment_photo_permission_denied, isError = true)
        }
    }

    SubScreen(
        title = format.longDay(format.zoned(appointment.startsAt).toLocalDate()),
        onBack = onBack,
        busy = busy,
    ) { padding ->
        ScreenColumn(padding) {
            // --- summary ---
            HcmCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Stacked rather than side by side: a 12-hour time range needs the full width.
                    StatusChip(appointment.status)
                    Text(format.timeRange(appointment), style = MaterialTheme.typography.displaySmall)
                    Text(
                        appointment.participants.sortedByDescending { it.isPrimary }.joinToString(", ") { it.name }.ifEmpty { appointment.primaryClientName },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    IconLine(
                        appointment.locationType.icon,
                        listOf(stringResource(appointment.locationType.label), appointment.locationAddress).filter { it.isNotBlank() }.joinToString(" · "),
                        maxLines = 3,
                    )
                    appointment.mapUrl?.let { mapUrl ->
                        SecondaryButton(
                            stringResource(R.string.open_in_maps),
                            onClick = { if (!context.openUri(Uri.parse(mapUrl))) viewModel.notify(R.string.link_open_failed, isError = true) },
                            modifier = Modifier.fillMaxWidth(),
                            icon = Icons.Rounded.Map,
                        )
                    }
                }
            }

            // --- outcome ---
            HcmCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.appointment_status_label), style = MaterialTheme.typography.headlineSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppointmentStatus.entries.forEach { status ->
                            ChoiceChip(
                                stringResource(status.label),
                                selected = appointment.status == status,
                                onClick = { if (appointment.status != status) viewModel.setAppointmentStatus(appointment.id, status) },
                            )
                        }
                    }
                }
            }

            // --- clients ---
            HcmCard(contentPadding = PaddingValues(top = 18.dp, bottom = 8.dp)) {
                Text(stringResource(R.string.clients_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 18.dp))
                appointment.participants.forEach { participant ->
                    ParticipantRow(
                        participant = participant,
                        showSubtotal = isGroup,
                        canRemove = isGroup,
                        onOpen = { onOpenClient(participant.clientId) },
                        onMakePrimary = { viewModel.setAppointmentPrimary(appointment.id, participant.clientId) },
                        onRemove = { viewModel.removeParticipant(appointment.id, participant.clientId) },
                    )
                }
                TextAction(
                    stringResource(R.string.add_client_to_appointment),
                    onClick = { sheet = DetailSheet.AddClient },
                    icon = Icons.Rounded.Add,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }

            // --- services ---
            HcmCard {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(stringResource(R.string.appointment_services_title), style = MaterialTheme.typography.headlineSmall)

                    if (appointment.services.isEmpty()) {
                        InlineEmpty(stringResource(R.string.appointment_no_services))
                    }

                    appointment.services.forEachIndexed { index, service ->
                        if (index > 0) {
                            Hairline()
                        }
                        ServiceBlock(
                            service = service,
                            clientName = appointment.participants.firstOrNull { it.clientId == service.clientId }?.name.takeIf { isGroup },
                            onDelete = { viewModel.deleteService(appointment.id, service.id) },
                            onAddFormula = { sheet = DetailSheet.Formula(service, null) },
                            onEditFormula = { sheet = DetailSheet.Formula(service, it) },
                        )
                    }

                    SecondaryButton(stringResource(R.string.add_appointment_service), onClick = { sheet = DetailSheet.AddService }, modifier = Modifier.fillMaxWidth(), icon = Icons.Rounded.Add)
                    TextAction(
                        stringResource(R.string.copy_completed_services),
                        onClick = { sheet = DetailSheet.CopyServices },
                        icon = Icons.Rounded.ContentCopy,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
            }

            // --- totals ---
            TotalsCard(
                appointment = appointment,
                onSaveOverride = { viewModel.setAppointmentFinalTotal(appointment.id, it) },
            )

            // --- photos ---
            HcmCard {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(stringResource(R.string.appointment_photos_title), style = MaterialTheme.typography.headlineSmall)

                    if (appointment.photos.isEmpty() && !uploadingPhoto) {
                        InlineEmpty(stringResource(R.string.appointment_no_photos))
                    }

                    appointment.participants.forEach { participant ->
                        val photos = appointment.photos.filter { it.clientId == participant.clientId }

                        if (isGroup) {
                            Kicker(participant.name)
                        }
                        if (photos.isNotEmpty()) {
                            PhotoGrid(photos, onOpen = { sheet = DetailSheet.Photo(it.id) })
                        }
                        SecondaryButton(
                            stringResource(R.string.add_appointment_photo),
                            onClick = { sheet = DetailSheet.AddPhoto(participant) },
                            modifier = Modifier.fillMaxWidth(),
                            icon = Icons.Rounded.CameraAlt,
                            enabled = !uploadingPhoto,
                        )
                    }

                    if (uploadingPhoto) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.appointment_photo_uploading), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // --- note ---
            NoteCard(appointment, onSave = { viewModel.saveAppointmentNote(appointment.id, it) })

            // --- actions ---
            SecondaryButton(
                stringResource(R.string.compose_client_reminder),
                onClick = { composeReminder(context, viewModel, appointment, format) },
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Rounded.Sms,
            )
            TextAction(
                stringResource(R.string.delete_appointment),
                onClick = { confirmDelete = true },
                icon = Icons.Rounded.DeleteOutline,
                danger = true,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }

    when (val current = sheet) {
        DetailSheet.AddClient -> ClientPickerSheet(
            title = stringResource(R.string.add_client_to_appointment),
            clients = clients.filter { client -> appointment.participants.none { it.clientId == client.id } },
            selectedIds = emptySet(),
            onDismiss = { sheet = null },
            onPick = {
                viewModel.addParticipant(appointment.id, it.id)
                sheet = null
            },
        )
        DetailSheet.AddService -> AddServiceSheet(
            appointment = appointment,
            menu = serviceMenu,
            busy = busy,
            onDismiss = { sheet = null },
            onMissingName = { viewModel.notify(R.string.service_name_required, isError = true) },
            onAdd = { clientId, menuItemId, name, priceCents, note ->
                viewModel.addService(appointment.id, clientId, menuItemId, name, priceCents, note) { sheet = null }
            },
        )
        DetailSheet.CopyServices -> CopyServicesSheet(
            sources = completedSources.filter { it.id != appointment.id },
            format = format,
            onDismiss = { sheet = null },
            onCopy = { sourceId -> viewModel.copyServices(appointment.id, sourceId) { sheet = null } },
        )
        is DetailSheet.Formula -> FormulaSheet(
            current = current,
            busy = busy,
            onDismiss = { sheet = null },
            onMissingFormula = { viewModel.notify(R.string.color_formula_required, isError = true) },
            onSave = { formula, placement ->
                viewModel.saveColorFormula(appointment.id, current.service.id, current.formula?.id, formula, placement) { sheet = null }
            },
            onDelete = { formulaId -> viewModel.deleteColorFormula(appointment.id, formulaId) { sheet = null } },
        )
        is DetailSheet.AddPhoto -> AddPhotoSheet(
            current = current,
            onDismiss = { sheet = null },
            onPick = { category, fromCamera -> startPhoto(current.participant, category, current.replacing, fromCamera) },
        )
        is DetailSheet.Photo -> {
            val photo = appointment.photos.firstOrNull { it.id == current.photoId }

            if (photo == null) {
                sheet = null
            } else {
                PhotoSheet(
                    photo = photo,
                    clientName = appointment.participants.firstOrNull { it.clientId == photo.clientId }?.name.orEmpty(),
                    onDismiss = { sheet = null },
                    onCategory = { viewModel.setPhotoCategory(appointment.id, photo.id, it) },
                    onRemove = {
                        sheet = null
                        viewModel.deletePhoto(appointment.id, photo.id)
                    },
                    onRetry = {
                        appointment.participants.firstOrNull { it.clientId == photo.clientId }?.let { sheet = DetailSheet.AddPhoto(it, replacing = photo) }
                    },
                )
            }
        }
        null -> Unit
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.delete_appointment) + "?",
            body = stringResource(R.string.delete_appointment_confirm_body),
            confirmText = stringResource(R.string.delete),
            danger = true,
            onConfirm = {
                confirmDelete = false
                viewModel.deleteAppointment(appointment.id, onSuccess = onBack)
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** Opens the SMS app with a reminder in the primary Client's language. Nothing is sent automatically. */
private fun composeReminder(context: Context, viewModel: AppViewModel, appointment: Appointment, stylistFormat: TimeFormat) {
    val recipient = appointment.participants.firstOrNull { it.isPrimary }

    if (recipient == null || recipient.phone.isBlank()) {
        viewModel.notify(R.string.client_reminder_missing_phone, isError = true)
        return
    }

    // Written in the Client's language, whatever language the app is in.
    val clientContext = context.inLanguage(recipient.language)
    val message = clientContext.getString(
        R.string.client_reminder_message,
        TimeFormat(recipient.language.toLocale(), stylistFormat.zone).dateAndTimeRange(appointment),
        appointment.locationAddress.ifBlank { clientContext.getString(appointment.locationType.label) },
    )

    try {
        context.startActivity(Intent(Intent.ACTION_SENDTO, ClientReminder.smsUri(recipient.phone)).putExtra("sms_body", message))
    } catch (_: ActivityNotFoundException) {
        viewModel.notify(R.string.sms_app_unavailable, isError = true)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParticipantRow(
    participant: AppointmentParticipant,
    showSubtotal: Boolean,
    canRemove: Boolean,
    onOpen: () -> Unit,
    onMakePrimary: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 18.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(participant.name, size = 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(participant.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Wraps, so a long badge never squeezes the phone number out.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                if (participant.isPrimary) {
                    Tag(stringResource(R.string.primary_badge), content = MaterialTheme.colorScheme.primary, container = MaterialTheme.colorScheme.primaryContainer)
                }
                if (participant.phone.isNotBlank()) {
                    Text(participant.phone, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
        if (showSubtotal) {
            Text(Money.format(participant.subtotalCents), style = MaterialTheme.typography.titleSmall)
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.more_actions), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = MaterialTheme.colorScheme.surface) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.edit_client_title), style = MaterialTheme.typography.bodyLarge) },
                    onClick = {
                        menuOpen = false
                        onOpen()
                    },
                )
                if (!participant.isPrimary) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.make_primary), style = MaterialTheme.typography.bodyLarge) },
                        onClick = {
                            menuOpen = false
                            onMakePrimary()
                        },
                    )
                }
                if (canRemove) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.remove_client_from_appointment), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuOpen = false
                            onRemove()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ServiceBlock(
    service: AppointmentService,
    clientName: String?,
    onDelete: () -> Unit,
    onAddFormula: () -> Unit,
    onEditFormula: (ColorFormula) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(service.name, style = MaterialTheme.typography.titleLarge)
                if (clientName != null) {
                    Text(clientName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(Money.format(service.priceCents), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = stringResource(R.string.delete_appointment_service), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (service.note.isNotBlank()) {
            Text(service.note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        service.colorFormulas.forEach { formula ->
            Surface(
                onClick = { onEditFormula(formula) },
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Kicker(formula.placement.ifBlank { stringResource(R.string.color_formula_label) }, color = MaterialTheme.colorScheme.primary)
                    Text(formula.formula, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        // Pull the text button's own padding back so its label lines up with the Service name.
        TextAction(stringResource(R.string.add_color_formula), onClick = onAddFormula, icon = Icons.Rounded.Add, modifier = Modifier.offset(x = (-10).dp))
    }
}

@Composable
private fun TotalsCard(appointment: Appointment, onSaveOverride: (Int?) -> Unit) {
    var overrideText by remember(appointment.id, appointment.finalTotalCentsOverride) {
        mutableStateOf(appointment.finalTotalCentsOverride?.let(Money::centsToPrice).orEmpty())
    }

    HcmCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.appointment_totals_title), style = MaterialTheme.typography.headlineSmall)
            LabeledValue(stringResource(R.string.appointment_service_total), Money.format(appointment.serviceTotalCents))
            Hairline()
            LabeledValue(stringResource(R.string.appointment_final_total), Money.format(appointment.finalTotalCents), emphasized = true)
            HcmTextField(
                value = overrideText,
                onValueChange = { overrideText = it },
                label = stringResource(R.string.appointment_final_total_override),
                placeholder = Money.centsToPrice(appointment.finalTotalCents),
                prefix = "$",
                keyboardType = KeyboardType.Decimal,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(
                    stringResource(R.string.save_final_total),
                    onClick = { onSaveOverride(Money.priceToCents(overrideText)) },
                    modifier = Modifier.weight(1f),
                    enabled = overrideText.isNotBlank(),
                )
                if (appointment.finalTotalCentsOverride != null) {
                    SecondaryButton(stringResource(R.string.clear_final_total_override), onClick = { onSaveOverride(null) }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun NoteCard(appointment: Appointment, onSave: (String) -> Unit) {
    var note by remember(appointment.id, appointment.note) { mutableStateOf(appointment.note) }

    HcmCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.appointment_note_title), style = MaterialTheme.typography.headlineSmall)
            HcmTextField(note, { note = it }, label = "", placeholder = stringResource(R.string.appointment_note_placeholder), singleLine = false, minLines = 3)
            SecondaryButton(
                stringResource(R.string.save_appointment_note),
                onClick = { onSave(note) },
                modifier = Modifier.fillMaxWidth(),
                enabled = note.trim() != appointment.note,
            )
        }
    }
}

@Composable
private fun PhotoGrid(photos: List<AppointmentPhoto>, onOpen: (AppointmentPhoto) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        photos.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { photo ->
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onOpen(photo) },
                    ) {
                        if (photo.status == PhotoStatus.STORED && photo.url.isNotBlank()) {
                            AsyncImage(
                                model = photo.thumbnailUrl.ifBlank { photo.url },
                                contentDescription = stringResource(photo.category.label),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Icon(
                                Icons.Rounded.BrokenImage,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                        Tag(
                            stringResource(photo.category.label),
                            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                            content = HcmColors.Surface,
                            container = HcmColors.Ink.copy(alpha = 0.72f),
                        )
                    }
                }
                // Keep cells square when the last row is short.
                repeat(3 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddServiceSheet(
    appointment: Appointment,
    menu: List<ServiceMenuItem>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onMissingName: () -> Unit,
    onAdd: (clientId: String, menuItemId: String?, name: String?, priceCents: Int?, note: String?) -> Unit,
) {
    var clientId by remember { mutableStateOf(appointment.primaryClientId) }
    var menuItemId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val selectedItem = menu.firstOrNull { it.id == menuItemId }

    HcmBottomSheet(title = stringResource(R.string.add_appointment_service), onDismiss = onDismiss) {
        if (appointment.participants.size > 1) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.appointment_service_client_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    appointment.participants.forEach { participant ->
                        ChoiceChip(participant.name, selected = clientId == participant.clientId, onClick = { clientId = participant.clientId })
                    }
                }
            }
        }

        if (menu.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.add_menu_service), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    menu.forEach { item ->
                        ChoiceChip(
                            item.name + " · " + Money.format(item.defaultPriceCents),
                            selected = menuItemId == item.id,
                            onClick = {
                                // Picking a menu item takes its name and default price; the price stays editable.
                                menuItemId = item.id
                                name = ""
                                price = Money.centsToPrice(item.defaultPriceCents)
                            },
                        )
                    }
                }
            }
        }

        HcmTextField(
            value = name,
            onValueChange = {
                name = it
                // Typing a name makes this an ad hoc Service rather than a menu one.
                menuItemId = null
            },
            label = stringResource(R.string.appointment_service_name_label),
            placeholder = selectedItem?.name ?: stringResource(R.string.placeholder_service_name),
        )
        HcmTextField(price, { price = it }, label = stringResource(R.string.appointment_service_price_label), placeholder = "85.00", prefix = "$", keyboardType = KeyboardType.Decimal)
        HcmTextField(note, { note = it }, label = stringResource(R.string.appointment_service_note_label), placeholder = stringResource(R.string.optional), singleLine = false, minLines = 2)
        PrimaryButton(
            stringResource(if (menuItemId != null) R.string.add_menu_service else R.string.add_ad_hoc_service),
            onClick = {
                if (name.isBlank() && selectedItem == null) {
                    onMissingName()
                } else {
                    onAdd(clientId, selectedItem?.id, name.ifBlank { null }, price.takeIf { it.isNotBlank() }?.let(Money::priceToCents), note.ifBlank { null })
                }
            },
            modifier = Modifier.fillMaxWidth(),
            loading = busy,
        )
    }
}

@Composable
private fun CopyServicesSheet(sources: List<Appointment>, format: TimeFormat, onDismiss: () -> Unit, onCopy: (String?) -> Unit) {
    HcmBottomSheet(title = stringResource(R.string.copy_completed_services), onDismiss = onDismiss) {
        if (sources.isEmpty()) {
            InlineEmpty(stringResource(R.string.copy_services_empty))
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(stringResource(R.string.copy_services_latest), onClick = { onCopy(null) }, modifier = Modifier.fillMaxWidth(), icon = Icons.Rounded.ContentCopy)
                sources.forEach { source ->
                    HcmCard(Modifier.fillMaxWidth(), onClick = { onCopy(source.id) }, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(source.primaryClientName, style = MaterialTheme.typography.titleMedium)
                        Text(format.dateAndTimeRange(source), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (source.services.isNotEmpty()) {
                            Text(
                                source.services.joinToString(", ") { it.name },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FormulaSheet(
    current: DetailSheet.Formula,
    busy: Boolean,
    onDismiss: () -> Unit,
    onMissingFormula: () -> Unit,
    onSave: (formula: String, placement: String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var formula by remember(current) { mutableStateOf(current.formula?.formula.orEmpty()) }
    var placement by remember(current) { mutableStateOf(current.formula?.placement.orEmpty()) }

    HcmBottomSheet(title = stringResource(R.string.color_formula_label), subtitle = current.service.name, onDismiss = onDismiss) {
        HcmTextField(formula, { formula = it }, label = stringResource(R.string.color_formula_label), placeholder = "7N + 20 vol", singleLine = false, minLines = 2)
        HcmTextField(placement, { placement = it }, label = stringResource(R.string.color_formula_placement_label), placeholder = stringResource(R.string.placeholder_formula_placement))
        PrimaryButton(
            stringResource(if (current.formula != null) R.string.save_color_formula else R.string.add_color_formula),
            onClick = { if (formula.isBlank()) onMissingFormula() else onSave(formula, placement) },
            modifier = Modifier.fillMaxWidth(),
            loading = busy,
        )
        current.formula?.let { existing ->
            TextAction(
                stringResource(R.string.delete_color_formula),
                onClick = { onDelete(existing.id) },
                icon = Icons.Rounded.DeleteOutline,
                danger = true,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun AddPhotoSheet(current: DetailSheet.AddPhoto, onDismiss: () -> Unit, onPick: (PhotoCategory, fromCamera: Boolean) -> Unit) {
    var category by remember(current) { mutableStateOf(current.replacing?.category ?: PhotoCategory.AFTER) }

    HcmBottomSheet(title = stringResource(R.string.appointment_photos_title), subtitle = current.participant.name, onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(stringResource(R.string.appointment_photo_category_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SegmentedControl(PhotoCategory.entries, category, { category = it }, label = { stringResource(it.label) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(stringResource(R.string.add_photo_from_camera), onClick = { onPick(category, true) }, modifier = Modifier.weight(1f), icon = Icons.Rounded.CameraAlt)
            PrimaryButton(stringResource(R.string.add_photo_from_gallery), onClick = { onPick(category, false) }, modifier = Modifier.weight(1f), icon = Icons.Rounded.PhotoLibrary)
        }
    }
}

@Composable
private fun PhotoSheet(
    photo: AppointmentPhoto,
    clientName: String,
    onDismiss: () -> Unit,
    onCategory: (PhotoCategory) -> Unit,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
) {
    val stored = photo.status == PhotoStatus.STORED && photo.url.isNotBlank()

    HcmBottomSheet(title = stringResource(photo.category.label), subtitle = clientName, onDismiss = onDismiss) {
        if (stored) {
            AsyncImage(
                model = photo.url,
                contentDescription = stringResource(photo.category.label),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceVariant),
            )
        } else {
            Text(
                photo.uploadError.ifBlank { stringResource(R.string.appointment_photo_upload_failed) },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
            SecondaryButton(stringResource(R.string.retry_appointment_photo), onClick = onRetry, modifier = Modifier.fillMaxWidth())
        }
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(stringResource(R.string.appointment_photo_category_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SegmentedControl(PhotoCategory.entries, photo.category, { if (it != photo.category) onCategory(it) }, label = { stringResource(it.label) })
        }
        TextAction(
            stringResource(R.string.remove_appointment_photo),
            onClick = onRemove,
            icon = Icons.Rounded.DeleteOutline,
            danger = true,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}
