package com.northphoenix.hairdresserclientmanager.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EditCalendar
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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
import com.northphoenix.hairdresserclientmanager.domain.Amount
import com.northphoenix.hairdresserclientmanager.domain.AppointmentClients
import com.northphoenix.hairdresserclientmanager.domain.AppointmentCopying
import com.northphoenix.hairdresserclientmanager.domain.ClientReminder
import com.northphoenix.hairdresserclientmanager.domain.ColorFormulaOffer
import com.northphoenix.hairdresserclientmanager.domain.Money
import com.northphoenix.hairdresserclientmanager.domain.ServiceEdit
import com.northphoenix.hairdresserclientmanager.domain.TextLimits
import com.northphoenix.hairdresserclientmanager.domain.TimeFormat
import com.northphoenix.hairdresserclientmanager.domain.UnsavedInput
import com.northphoenix.hairdresserclientmanager.i18n.clientReminderTemplate
import com.northphoenix.hairdresserclientmanager.i18n.label
import com.northphoenix.hairdresserclientmanager.i18n.inLanguage
import com.northphoenix.hairdresserclientmanager.i18n.messageRes
import com.northphoenix.hairdresserclientmanager.i18n.toLocale
import com.northphoenix.hairdresserclientmanager.state.AppViewModel
import com.northphoenix.hairdresserclientmanager.state.LoadState
import com.northphoenix.hairdresserclientmanager.state.PhotoUpload
import com.northphoenix.hairdresserclientmanager.state.Read
import com.northphoenix.hairdresserclientmanager.state.UploadState
import com.northphoenix.hairdresserclientmanager.ui.LocalTimeFormat
import com.northphoenix.hairdresserclientmanager.ui.components.AmountField
import com.northphoenix.hairdresserclientmanager.ui.components.Avatar
import com.northphoenix.hairdresserclientmanager.ui.components.ChoiceChip
import com.northphoenix.hairdresserclientmanager.ui.components.ColorFormulaCard
import com.northphoenix.hairdresserclientmanager.ui.components.ConfirmDialog
import com.northphoenix.hairdresserclientmanager.ui.components.Hairline
import com.northphoenix.hairdresserclientmanager.ui.components.HcmBottomSheet
import com.northphoenix.hairdresserclientmanager.ui.components.HcmCard
import com.northphoenix.hairdresserclientmanager.ui.components.HcmTextField
import com.northphoenix.hairdresserclientmanager.ui.components.IconLine
import com.northphoenix.hairdresserclientmanager.ui.components.InlineEmpty
import com.northphoenix.hairdresserclientmanager.ui.components.InlineLoadFailed
import com.northphoenix.hairdresserclientmanager.ui.components.ItemMissing
import com.northphoenix.hairdresserclientmanager.ui.components.Kicker
import com.northphoenix.hairdresserclientmanager.ui.components.LabeledValue
import com.northphoenix.hairdresserclientmanager.ui.components.LoadFailed
import com.northphoenix.hairdresserclientmanager.ui.components.PrimaryButton
import com.northphoenix.hairdresserclientmanager.ui.components.collectWhileOpen
import com.northphoenix.hairdresserclientmanager.ui.components.ScreenColumn
import com.northphoenix.hairdresserclientmanager.ui.components.SecondaryButton
import com.northphoenix.hairdresserclientmanager.ui.components.SegmentedControl
import com.northphoenix.hairdresserclientmanager.ui.components.SideBySideOrStacked
import com.northphoenix.hairdresserclientmanager.ui.components.StatusChip
import com.northphoenix.hairdresserclientmanager.ui.components.SubScreen
import com.northphoenix.hairdresserclientmanager.ui.components.Tag
import com.northphoenix.hairdresserclientmanager.ui.components.TagPadding
import com.northphoenix.hairdresserclientmanager.ui.components.QuietTextAction
import com.northphoenix.hairdresserclientmanager.ui.components.TextAction
import com.northphoenix.hairdresserclientmanager.ui.components.fittedTextStyle
import com.northphoenix.hairdresserclientmanager.ui.components.icon
import com.northphoenix.hairdresserclientmanager.ui.components.rememberRequestKey
import com.northphoenix.hairdresserclientmanager.ui.components.tagTextStyle
import com.northphoenix.hairdresserclientmanager.ui.openUri
import com.northphoenix.hairdresserclientmanager.ui.theme.HcmColors
import kotlinx.coroutines.flow.drop

private const val MAX_GALLERY_PHOTOS = 10

/**
 * The sheet that is open. It is saved state, so that an open sheet survives Android recreating the
 * screen, and it names what it is about by id only: the Service, Color Formula, Client or photo is
 * looked up in the Appointment as it is now.
 */
internal sealed interface DetailSheet {
    data object AddClient : DetailSheet

    data object AddService : DetailSheet

    data object CopyServices : DetailSheet

    /** Edit Appointment: the day, the times and the place. */
    data object Edit : DetailSheet

    /** A Service of the Appointment that is being edited: its name, its price and its note. */
    data class Service(val serviceId: String) : DetailSheet

    /** [formulaId] is null for a Color Formula that is being added. */
    data class Formula(val serviceId: String, val formulaId: String? = null) : DetailSheet

    /** [replacingPhotoId] is a failed photo being retried. */
    data class AddPhoto(val clientId: String, val replacingPhotoId: String? = null) : DetailSheet

    data class Photo(val photoId: String) : DetailSheet

    /** A photo that is not on the server yet: it is still being uploaded, or its upload failed. */
    data class Upload(val uploadId: String) : DetailSheet

    companion object {
        /** A sheet is saved as its kind followed by its ids, and no sheet as nothing. */
        val Saver = listSaver<DetailSheet?, String>(
            save = { sheet ->
                when (sheet) {
                    null -> emptyList()
                    AddClient -> listOf("addClient")
                    AddService -> listOf("addService")
                    CopyServices -> listOf("copyServices")
                    Edit -> listOf("edit")
                    is Service -> listOf("service", sheet.serviceId)
                    is Formula -> listOfNotNull("formula", sheet.serviceId, sheet.formulaId)
                    is AddPhoto -> listOfNotNull("addPhoto", sheet.clientId, sheet.replacingPhotoId)
                    is Photo -> listOf("photo", sheet.photoId)
                    is Upload -> listOf("upload", sheet.uploadId)
                }
            },
            restore = { saved ->
                val id = saved.getOrNull(1)

                when (saved.firstOrNull()) {
                    "addClient" -> AddClient
                    "addService" -> AddService
                    "copyServices" -> CopyServices
                    "edit" -> Edit
                    "service" -> id?.let(::Service)
                    "formula" -> id?.let { Formula(it, saved.getOrNull(2)) }
                    "addPhoto" -> id?.let { AddPhoto(it, saved.getOrNull(2)) }
                    "photo" -> id?.let(::Photo)
                    "upload" -> id?.let(::Upload)
                    else -> null
                }
            },
        )
    }
}

/** Everything about one Appointment: outcome, Clients, Services with Color Formulas, totals, photos and note. */
@Composable
fun AppointmentDetailScreen(
    viewModel: AppViewModel,
    appointmentId: String,
    onBack: () -> Unit,
    onOpenClient: (String) -> Unit,
) {
    // Read through the cache for as long as the screen is open, also behind the camera or a locked phone (see
    // `collectWhileOpen`). A copy that a list on screen has is there in the first frame. What the cache has follows
    // a moment later: until then (null) nothing is drawn in the Appointment's place, so that one that is cached
    // never flashes a loading state.
    val shown by remember(viewModel, appointmentId) { viewModel.appointment(appointmentId) }.collectWhileOpen(
        initialValue = remember(viewModel, appointmentId) {
            viewModel.shownAppointment(appointmentId)?.let { Read<Appointment?>(LoadState.Loaded, it) }
        },
    )
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val appointment = shown?.data

    // Asked for again each time the screen comes into view, from another screen or from the
    // background: the Appointment may have changed or gone since it was loaded.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.loadAppointment(appointmentId) }

    if (appointment == null) {
        SubScreen(title = "", onBack = onBack, saving = saving) { padding ->
            when (val load = shown?.state) {
                null -> Unit
                is LoadState.Failed ->
                    LoadFailed(load.message, onRetry = { viewModel.loadAppointment(appointmentId) }, Modifier.padding(padding))
                LoadState.Missing -> ItemMissing(
                    stringResource(R.string.appointment_missing_title),
                    stringResource(R.string.appointment_missing_body),
                    onBack,
                    Modifier.padding(padding),
                )
                else -> LoadingRow(Modifier.padding(padding))
            }
        }
        return
    }

    AppointmentDetail(viewModel, appointment, saved = shown?.saved ?: appointment, busy, saving, onBack, onOpenClient)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppointmentDetail(
    viewModel: AppViewModel,
    // As the screen shows it: with the changes the Stylist does not wait for (a status chip, a delete), from the moment of the tap.
    appointment: Appointment,
    // As the server has it. The same unless such a change is still being saved.
    saved: Appointment,
    // A change the Stylist waits for is in flight: what must not be tapped meanwhile is disabled.
    busy: Boolean,
    // Any change is in flight. Only the progress line shows it.
    saving: Boolean,
    onBack: () -> Unit,
    onOpenClient: (String) -> Unit,
) {
    val context = LocalContext.current
    val format = LocalTimeFormat.current
    val clientList by viewModel.clients.collectAsStateWithLifecycle()
    val clients = clientList.data.orEmpty()
    val clientsLoad = clientList.state
    val menu by viewModel.serviceMenu.collectAsStateWithLifecycle()
    val serviceMenu = menu.data.orEmpty()
    val serviceMenuLoad = menu.state
    val copyingInto by viewModel.copyingInto.collectAsStateWithLifecycle()
    // The photos of this Appointment that are not on the server yet.
    val uploads = viewModel.photoUploads.collectAsStateWithLifecycle().value.filter { it.photo.appointmentId == appointment.id }
    // False on a server without photo storage: no upload can work there, so none is offered. The photos it has are still shown.
    val canAddPhotos = viewModel.stylist.collectAsStateWithLifecycle().value?.photoStorageConfigured != false
    val today by viewModel.today.collectAsStateWithLifecycle()
    // The other Scheduled Appointments at this one's time, until the Stylist dismisses the warning.
    val overlapping = viewModel.overlapWarnings.collectAsStateWithLifecycle().value[appointment.id].orEmpty()
    val scroll = rememberScrollState()
    val isGroup = appointment.participants.size > 1
    var sheet by rememberSaveable(stateSaver = DetailSheet.Saver) { mutableStateOf<DetailSheet?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    // The Service whose delete is being asked about. By id, like an open sheet: it is looked up again on every composition.
    var deletingServiceId by rememberSaveable { mutableStateOf<String?>(null) }
    // The Client whose removal from the Appointment is being asked about, by id as well.
    var removingClientId by rememberSaveable { mutableStateOf<String?>(null) }
    // Null for all but an Appointment that changes at the same moment once left without its primary Client among its Clients.
    val clientsFault = AppointmentClients.fault(appointment)

    // The two inline fields. Back asks before it discards what is typed into them and not saved yet.
    // A text that is being saved is the Appointment's already, so it is not unsaved. When the save
    // fails the Appointment goes back, the text stays in its field, and it is unsaved again.
    // What is typed into a field while its save is on its way stays there when the save is answered, and is unsaved then.
    var noteDraft by rememberDraft(appointment.id, saved = saved.note, shown = appointment.note, text = { it }, after = UnsavedInput::noteDraft)
    var finalTotalDraft by rememberDraft(
        appointment.id,
        saved = saved.finalTotalCentsOverride,
        shown = appointment.finalTotalCentsOverride,
        text = { it?.let(Money::centsToPrice).orEmpty() },
        after = UnsavedInput::finalTotalDraft,
    )
    val unsaved = UnsavedInput.appointmentNote(noteDraft, appointment.note) ||
        UnsavedInput.finalTotal(finalTotalDraft, appointment.finalTotalCentsOverride)

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
        } else if (uri != null) {
            // Back from the camera without a photo: what it may have written is not kept.
            viewModel.discardCameraCapture(uri)
        }
    }

    fun startPhoto(participant: AppointmentParticipant, category: PhotoCategory, replacing: AppointmentPhoto?, fromCamera: Boolean) {
        photoClientId = participant.clientId
        photoCategory = category
        photoReplacingId = replacing?.id
        sheet = null

        try {
            if (fromCamera) {
                val uri = viewModel.newCameraCapture()
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

    // A warning that arrives while the screen is scrolled down is at its top: the screen goes there.
    // Not for the one the screen opens with, which would move a screen the Stylist comes back to.
    val latestOverlapping by rememberUpdatedState(overlapping)

    LaunchedEffect(scroll) {
        snapshotFlow { latestOverlapping }.drop(1).collect { if (it.isNotEmpty()) scroll.animateScrollTo(0) }
    }

    SubScreen(
        title = format.dayTitle(format.zoned(appointment.startsAt).toLocalDate()),
        onBack = onBack,
        saving = saving,
        unsaved = unsaved,
    ) { padding ->
        ScreenColumn(padding, scroll = scroll) {
            if (overlapping.isNotEmpty()) {
                OverlapWarning(appointment, overlapping, onDismiss = { viewModel.dismissOverlapWarning(appointment.id) })
            }

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
                    SecondaryButton(
                        stringResource(R.string.edit_appointment),
                        onClick = { sheet = DetailSheet.Edit },
                        modifier = Modifier.fillMaxWidth(),
                        icon = Icons.Rounded.EditCalendar,
                    )
                }
            }

            // --- outcome ---
            HcmCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.appointment_status_label), style = MaterialTheme.typography.headlineSmall)
                    FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppointmentStatus.entries.forEach { status ->
                            ChoiceChip(
                                stringResource(status.label),
                                selected = appointment.status == status,
                                onClick = { viewModel.setAppointmentStatus(appointment.id, status) },
                            )
                        }
                    }
                }
            }

            // --- clients ---
            clientsFault?.let { fault ->
                ClientsFaultWarning(
                    fault,
                    primaryClientName = appointment.primaryClientName,
                    enabled = !busy,
                    onRestore = { viewModel.addParticipant(appointment.id, appointment.primaryClientId) },
                )
            }
            HcmCard(contentPadding = PaddingValues(top = 18.dp, bottom = 8.dp)) {
                Text(stringResource(R.string.clients_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 18.dp))
                appointment.participants.forEach { participant ->
                    ParticipantRow(
                        participant = participant,
                        showSubtotal = isGroup,
                        canRemove = isGroup,
                        // The Clients of an Appointment change one at a time: the next change is offered by what the one before it left.
                        canChange = !busy,
                        onOpen = { onOpenClient(participant.clientId) },
                        onMakePrimary = { viewModel.setAppointmentPrimary(appointment.id, participant.clientId) },
                        onRemove = {
                            // Asked only about a removal that can happen. What keeps the Client in the Appointment is said instead.
                            val kept = AppointmentClients.keptBy(appointment, participant.clientId, uploads.any { it.photo.clientId == participant.clientId })

                            if (kept == null) {
                                removingClientId = participant.clientId
                            } else {
                                viewModel.notify(kept.messageRes(), isError = true)
                            }
                        },
                    )
                }
                TextAction(
                    stringResource(R.string.add_client_to_appointment),
                    onClick = { sheet = DetailSheet.AddClient },
                    icon = Icons.Rounded.Add,
                    modifier = Modifier.padding(horizontal = 8.dp),
                    enabled = !busy,
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
                            busy = busy,
                            onEdit = { sheet = DetailSheet.Service(service.id) },
                            onDelete = { deletingServiceId = service.id },
                            onAddFormula = { sheet = DetailSheet.Formula(service.id) },
                            onEditFormula = { sheet = DetailSheet.Formula(service.id, it.id) },
                        )
                    }

                    SecondaryButton(
                        stringResource(R.string.add_appointment_service),
                        onClick = { sheet = DetailSheet.AddService },
                        modifier = Modifier.fillMaxWidth(),
                        // A Service is for one of the Appointment's Clients. Without any, the warning above the Clients says what to do first.
                        enabled = clientsFault != AppointmentClients.Fault.NO_CLIENTS,
                        icon = Icons.Rounded.Add,
                    )
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
                overrideText = finalTotalDraft,
                onOverrideText = { finalTotalDraft = it },
                onSaveOverride = { viewModel.setAppointmentFinalTotal(appointment.id, it) },
            )

            // --- photos ---
            HcmCard {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(stringResource(R.string.appointment_photos_title), style = MaterialTheme.typography.headlineSmall)

                    if (appointment.photos.isEmpty() && uploads.isEmpty()) {
                        InlineEmpty(stringResource(R.string.appointment_no_photos))
                    }
                    if (!canAddPhotos) {
                        // In the place of every 'Add photo': why there is none.
                        Text(
                            stringResource(R.string.appointment_photos_unavailable),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    appointment.participants.forEach { participant ->
                        val photos = appointment.photos.filter { it.clientId == participant.clientId }
                        val onTheirWay = uploads.filter { it.photo.clientId == participant.clientId }
                        val failed = onTheirWay.count { it.state == UploadState.FAILED }
                        val uploading = onTheirWay.size - failed

                        // Without 'Add photo' a Client who has no photos has nothing under their name.
                        if (isGroup && (canAddPhotos || photos.isNotEmpty() || onTheirWay.isNotEmpty())) {
                            Kicker(participant.name)
                        }
                        if (photos.isNotEmpty() || onTheirWay.isNotEmpty()) {
                            PhotoGrid(
                                photos,
                                onTheirWay,
                                onOpen = { sheet = DetailSheet.Photo(it.id) },
                                onOpenUpload = { sheet = DetailSheet.Upload(it.id) },
                            )
                        }
                        // What is on its way is said here, with the Client it is for and above the button that started it.
                        if (uploading > 0) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(
                                    pluralStringResource(R.plurals.appointment_photos_uploading, uploading, uploading),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (failed > 0) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    pluralStringResource(R.plurals.appointment_photos_not_saved, failed, failed),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                if (canAddPhotos) {
                                    TextAction(
                                        stringResource(R.string.retry_appointment_photo),
                                        onClick = { viewModel.retryPhotoUploads(appointment.id, participant.clientId) },
                                    )
                                }
                            }
                        }
                        // Not held back while photos upload: more pictures wait for their turn.
                        if (canAddPhotos) {
                            SecondaryButton(
                                stringResource(R.string.add_appointment_photo),
                                onClick = { sheet = DetailSheet.AddPhoto(participant.clientId) },
                                modifier = Modifier.fillMaxWidth(),
                                icon = Icons.Rounded.CameraAlt,
                            )
                        }
                    }
                }
            }

            // --- note ---
            NoteCard(appointment, note = noteDraft, onNote = { noteDraft = it }, onSave = { viewModel.saveAppointmentNote(appointment.id, it) })

            // --- actions ---
            if (ClientReminder.isOffered(appointment)) {
                SecondaryButton(
                    stringResource(R.string.compose_client_reminder),
                    onClick = { composeReminder(context, viewModel, appointment, format) },
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Rounded.Sms,
                )
            }
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
            clients = clients,
            takenIds = appointment.participants.map { it.clientId }.toSet(),
            load = clientsLoad,
            onRetry = viewModel::retryLoad,
            selectedIds = emptySet(),
            onDismiss = { sheet = null },
            onPick = {
                viewModel.addParticipant(appointment.id, it.id)
                sheet = null
            },
        )
        DetailSheet.AddService -> {
            // One key for the Services this sheet is opened to add from the menu, and one for the
            // custom Service it can add instead, so that a second try of either adds nothing more.
            val menuRequestKey = rememberRequestKey(appointment.id, "menu")
            val customRequestKey = rememberRequestKey(appointment.id, "custom")

            AddServiceSheet(
                appointment = appointment,
                menu = serviceMenu,
                menuLoad = serviceMenuLoad,
                onRetry = viewModel::retryLoad,
                busy = busy,
                onDismiss = { sheet = null },
                onMissingName = { viewModel.notify(R.string.service_name_required, isError = true) },
                onAddFromMenu = { clientId, menuItemIds ->
                    viewModel.addMenuServices(appointment.id, clientId, menuItemIds, menuRequestKey) { sheet = null }
                },
                onAddCustom = { clientId, name, priceCents, note ->
                    viewModel.addService(appointment.id, clientId, null, name, priceCents, note, customRequestKey) { sheet = null }
                },
            )
        }
        DetailSheet.CopyServices -> {
            // One key for the copy this sheet is opened for, so that it is made once however often it is sent.
            val requestKey = rememberRequestKey(appointment.id)
            val clientIds = appointment.participants.map { it.clientId }

            // What can be copied depends on the Appointment's Clients: the list is read for as long as the sheet is open,
            // and it is another list when they change under it. One the sheet showed before is there from the first frame.
            // Under `key`, so that with other Clients nothing of the list from before is drawn, not for one frame.
            val sources by key(viewModel, appointment.id, clientIds) {
                remember { viewModel.copySources(appointment.id, clientIds) }
                    .collectWhileOpen(initialValue = remember { viewModel.shownCopySources(appointment.id, clientIds) })
            }

            // Asked for when the sheet opens: a list that cannot be loaded says so as a notice too.
            LaunchedEffect(appointment.id, clientIds) { viewModel.loadCopySources(appointment.id, clientIds) }

            CopyServicesSheet(
                sources = sources.data.orEmpty(),
                load = sources.state,
                onRetry = viewModel::retryLoad,
                copying = copyingInto == appointment.id,
                busy = busy,
                format = format,
                onDismiss = { sheet = null },
                onCopy = { sourceId -> viewModel.copyServices(appointment.id, sourceId, requestKey) { sheet = null } },
            )
        }
        DetailSheet.Edit -> EditAppointmentSheet(
            appointment = appointment,
            today = today,
            busy = busy,
            onDismiss = { sheet = null },
            onRefused = { viewModel.notify(it, isError = true) },
            onSave = { times, locationType, ownAddress ->
                viewModel.editAppointment(appointment.id, times, locationType, ownAddress) { sheet = null }
            },
        )
        // A sheet about something that is no longer in the Appointment closes.
        is DetailSheet.Service -> {
            val service = appointment.services.firstOrNull { it.id == current.serviceId }

            if (service == null) {
                sheet = null
            } else {
                EditServiceSheet(
                    service = service,
                    clientName = appointment.participants.firstOrNull { it.clientId == service.clientId }?.name.takeIf { isGroup },
                    busy = busy,
                    onDismiss = { sheet = null },
                    onSave = { edit -> viewModel.updateService(appointment.id, service.id, edit) { sheet = null } },
                )
            }
        }
        is DetailSheet.Formula -> {
            val service = appointment.services.firstOrNull { it.id == current.serviceId }
            val formula = service?.colorFormulas?.firstOrNull { it.id == current.formulaId }

            if (service == null || (formula == null && current.formulaId != null)) {
                sheet = null
            } else {
                // One key for the Color Formula this sheet is opened to add, as for a Service.
                val requestKey = rememberRequestKey(service.id, current.formulaId)

                FormulaSheet(
                    service = service,
                    formula = formula,
                    busy = busy,
                    onDismiss = { sheet = null },
                    onMissingFormula = { viewModel.notify(R.string.color_formula_required, isError = true) },
                    onSave = { text, placement ->
                        viewModel.saveColorFormula(appointment.id, service.id, formula?.id, text, placement, requestKey) { sheet = null }
                    },
                    onDelete = { formulaId ->
                        // Not waited for: the sheet closes and the Color Formula is gone now. It is back when the delete fails.
                        sheet = null
                        viewModel.deleteColorFormula(appointment.id, formulaId)
                    },
                )
            }
        }
        is DetailSheet.AddPhoto -> {
            val participant = appointment.participants.firstOrNull { it.clientId == current.clientId }
            val replacing = appointment.photos.firstOrNull { it.id == current.replacingPhotoId }

            // Also a sheet that was open when the server turned out to have no photo storage.
            if (participant == null || (replacing == null && current.replacingPhotoId != null) || !canAddPhotos) {
                sheet = null
            } else {
                AddPhotoSheet(
                    participant = participant,
                    replacing = replacing,
                    onDismiss = { sheet = null },
                    onPick = { category, fromCamera -> startPhoto(participant, category, replacing, fromCamera) },
                )
            }
        }
        is DetailSheet.Photo -> {
            val photo = appointment.photos.firstOrNull { it.id == current.photoId }

            if (photo == null) {
                sheet = null
            } else {
                PhotoSheet(
                    photo = photo,
                    clientName = appointment.participants.firstOrNull { it.clientId == photo.clientId }?.name.orEmpty(),
                    canUpload = canAddPhotos,
                    onDismiss = { sheet = null },
                    onCategory = { viewModel.setPhotoCategory(appointment.id, photo.id, it) },
                    onRemove = {
                        sheet = null
                        viewModel.deletePhoto(appointment.id, photo.id)
                    },
                    onRetry = {
                        appointment.participants.firstOrNull { it.clientId == photo.clientId }?.let { sheet = DetailSheet.AddPhoto(it.clientId, replacingPhotoId = photo.id) }
                    },
                )
            }
        }
        // Closes once the photo is on the server: it is among the stored ones then.
        is DetailSheet.Upload -> {
            val upload = uploads.firstOrNull { it.id == current.uploadId }

            if (upload == null) {
                sheet = null
            } else {
                UploadSheet(
                    upload = upload,
                    clientName = appointment.participants.firstOrNull { it.clientId == upload.photo.clientId }?.name.orEmpty(),
                    canUpload = canAddPhotos,
                    onDismiss = { sheet = null },
                    onRetry = { viewModel.retryPhotoUpload(upload.id) },
                    onRemove = {
                        sheet = null
                        viewModel.removePhotoUpload(upload.id)
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

    removingClientId?.let { clientId ->
        val participant = appointment.participants.firstOrNull { it.clientId == clientId }

        if (participant == null || !isGroup) {
            // Removed on another device by now, or the only Client left. The question is over, also
            // if that Client is added again later: unlike a Service, a Client can come back.
            removingClientId = null
        } else {
            ConfirmDialog(
                title = stringResource(R.string.remove_client_from_appointment) + "?",
                body = stringResource(
                    if (participant.isPrimary) R.string.remove_primary_client_confirm_body else R.string.remove_client_confirm_body,
                    participant.name,
                ),
                confirmText = stringResource(R.string.remove_client_confirm),
                danger = true,
                onConfirm = {
                    removingClientId = null
                    viewModel.removeParticipant(appointment.id, participant.clientId)
                },
                onDismiss = { removingClientId = null },
            )
        }
    }

    // A Service that is gone by now has nothing left to ask about.
    appointment.services.firstOrNull { it.id == deletingServiceId }?.let { service ->
        ConfirmDialog(
            title = stringResource(R.string.delete_appointment_service) + "?",
            body = stringResource(R.string.delete_appointment_service_confirm_body, service.name),
            confirmText = stringResource(R.string.delete),
            danger = true,
            onConfirm = {
                deletingServiceId = null
                viewModel.deleteService(appointment.id, service.id)
            },
            onDismiss = { deletingServiceId = null },
        )
    }
}

/**
 * The overlap warning of this Appointment: the other Scheduled Appointments at its time, each by
 * its time and its Clients. It is on screen until the Stylist dismisses it, and a screen reader
 * reads it out when it appears.
 */
@Composable
private fun OverlapWarning(appointment: Appointment, overlapping: List<Appointment>, onDismiss: () -> Unit) {
    val format = LocalTimeFormat.current
    val day = format.zoned(appointment.startsAt).toLocalDate()

    WarningCard(
        title = stringResource(R.string.appointment_overlap_title),
        action = stringResource(R.string.appointment_overlap_dismiss),
        onAction = onDismiss,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(stringResource(R.string.appointment_overlap_intro), style = MaterialTheme.typography.bodyMedium, color = HcmColors.Ink)
        overlapping.forEach { other ->
            val clients = other.participants.joinToString(", ") { it.name }.ifEmpty { other.primaryClientName }

            Text(format.timeRangeSeenFrom(day, other) + " · " + clients, style = MaterialTheme.typography.titleSmall, color = HcmColors.Ink)
        }
    }
}

/**
 * Shown above the Clients of an Appointment whose primary Client is not one of them: what is wrong
 * and how to put it right. [onRestore] adds the primary Client back, which mends either [fault];
 * 'Make primary' on a Client that is listed mends it as well.
 */
@Composable
private fun ClientsFaultWarning(fault: AppointmentClients.Fault, primaryClientName: String, enabled: Boolean, onRestore: () -> Unit) {
    val (title, body) = when (fault) {
        AppointmentClients.Fault.NO_CLIENTS -> R.string.appointment_no_clients_title to R.string.appointment_no_clients_body
        AppointmentClients.Fault.NO_PRIMARY -> R.string.appointment_no_primary_client_title to R.string.appointment_no_primary_client_body
    }

    WarningCard(
        title = stringResource(title),
        action = stringResource(R.string.restore_primary_client),
        onAction = onRestore,
        actionEnabled = enabled,
    ) {
        Text(stringResource(body, primaryClientName), style = MaterialTheme.typography.bodyMedium, color = HcmColors.Ink)
    }
}

/** A warning about the Appointment: its title, what [content] says about it, and one action under that. */
@Composable
private fun WarningCard(
    title: String,
    action: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    actionEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    HcmCard(
        modifier,
        color = HcmColors.WarningSoft,
        borderColor = HcmColors.Warning,
        contentPadding = PaddingValues(start = 18.dp, top = 16.dp, end = 8.dp, bottom = 6.dp),
    ) {
        Column(Modifier.padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.WarningAmber, contentDescription = null, modifier = Modifier.size(20.dp), tint = HcmColors.Warning)
                Text(title, style = MaterialTheme.typography.titleMedium, color = HcmColors.Warning)
            }
            content()
        }
        TextAction(action, onClick = onAction, modifier = Modifier.align(Alignment.End), enabled = actionEnabled)
    }
}

/**
 * The text of an inline field of the Appointment, whose value is a [T] and is written as [text].
 * It starts from what the Appointment shows ([shown]). Text that differs from that is kept in
 * saved state, so it survives Android recreating the screen; an untouched field is not kept and
 * shows what is saved by then.
 *
 * [saved] is what the server has. The two differ while a text is being saved without the Stylist
 * waiting: [shown] is that text already. That does not start the field again, and neither does
 * going back to [saved] when the save fails: what was typed stays in the field, for another try.
 *
 * When [saved] changes, the save was answered or a reload brought another value. [after] decides
 * what the field holds then (see [UnsavedInput.noteDraft]): a field that still holds what the
 * Appointment showed until then follows it, and text typed since stays, as unsaved input.
 */
@Composable
private fun <T> rememberDraft(
    appointmentId: String,
    saved: T,
    shown: T,
    text: (T) -> String,
    after: (draft: String, shownBefore: T, shown: T) -> String,
): MutableState<String> {
    // The field, and what the Appointment showed, when this last ran: what `after` goes by once `saved` is another value.
    // The field as a state, not its text: a letter typed since then is in it.
    val before = remember(appointmentId) { DraftBefore<T>() }
    val shownText = text(shown)
    val draft = rememberSaveable(
        appointmentId,
        saved,
        saver = Saver<MutableState<String>, String>(save = { draft -> draft.value.takeIf { it != shownText } }, restore = { mutableStateOf(it) }),
    ) {
        mutableStateOf(before.last?.let { (field, shownBefore) -> after(field.value, shownBefore, shown) } ?: shownText)
    }

    SideEffect { before.last = draft to shown }

    return draft
}

/** What [rememberDraft] had the last time it ran: the field, and what the Appointment showed then. Null before the first time. */
private class DraftBefore<T>(var last: Pair<MutableState<String>, T>? = null)

/** Opens the SMS app with a reminder in the primary Client's language. Nothing is sent automatically. */
private fun composeReminder(context: Context, viewModel: AppViewModel, appointment: Appointment, stylistFormat: TimeFormat) {
    val recipient = appointment.participants.firstOrNull { it.isPrimary }
    val number = recipient?.let { ClientReminder.recipient(it.phone) }

    if (recipient == null || number == null) {
        // No primary Client among the Appointment's Clients is not a Client without a phone.
        viewModel.notify(if (recipient == null) R.string.client_reminder_no_primary_client else R.string.client_reminder_missing_phone, isError = true)
        return
    }

    // Written in the Client's language, whatever language the app is in.
    val template = context.inLanguage(recipient.language).getString(clientReminderTemplate(appointment))
    val message = ClientReminder.message(template, appointment, recipient.language.toLocale(), stylistFormat.zone)

    try {
        context.startActivity(Intent(Intent.ACTION_SENDTO, ClientReminder.smsUri(number)).putExtra("sms_body", message))
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
    /** False while a change is running: 'Make primary' and 'Remove from Appointment' wait for it. */
    canChange: Boolean,
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
                        enabled = canChange,
                    )
                }
                if (canRemove) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.remove_client_from_appointment),
                                style = MaterialTheme.typography.bodyLarge,
                                // Its own colour only while it can be tapped: disabled, it is as pale as the menu makes the others.
                                color = if (canChange) MaterialTheme.colorScheme.error else Color.Unspecified,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onRemove()
                        },
                        enabled = canChange,
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
    busy: Boolean,
    onEdit: () -> Unit,
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
            IconButton(onClick = onEdit) {
                Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.edit_appointment_service), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDelete, enabled = !busy) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = stringResource(R.string.delete_appointment_service), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (service.note.isNotBlank()) {
            Text(service.note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        service.colorFormulas.forEach { formula ->
            ColorFormulaCard(formula, onClick = { onEditFormula(formula) })
        }
        // Pull the text button's own padding back so its label lines up with the Service name.
        if (ColorFormulaOffer.isProminent(service)) {
            TextAction(stringResource(R.string.add_color_formula), onClick = onAddFormula, icon = Icons.Rounded.Add, modifier = Modifier.offset(x = (-10).dp))
        } else {
            QuietTextAction(stringResource(R.string.add_color_formula_quiet), onClick = onAddFormula, modifier = Modifier.offset(x = (-8).dp))
        }
    }
}

@Composable
private fun TotalsCard(appointment: Appointment, overrideText: String, onOverrideText: (String) -> Unit, onSaveOverride: (Long?) -> Unit) {
    // The text a save was refused for. The reason stays under the field for as long as the text is that one.
    var refusedText by rememberSaveable(appointment.id) { mutableStateOf<String?>(null) }

    HcmCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.appointment_totals_title), style = MaterialTheme.typography.headlineSmall)
            LabeledValue(stringResource(R.string.appointment_service_total), Money.format(appointment.serviceTotalCents))
            Hairline()
            LabeledValue(stringResource(R.string.appointment_final_total), Money.format(appointment.finalTotalCents), emphasized = true)
            AmountField(
                value = overrideText,
                onValueChange = onOverrideText,
                label = stringResource(R.string.appointment_final_total_override),
                placeholder = Money.centsToPrice(appointment.finalTotalCents),
                refusal = Money.parse(overrideText).takeIf { overrideText == refusedText },
            )
            // As tall as the taller of the two, whichever label takes more lines.
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(
                    stringResource(R.string.save_final_total),
                    // An empty field is no override, and the button is off for it. Anything else is saved only as the amount it states.
                    onClick = {
                        when (val amount = Money.parse(overrideText)) {
                            is Amount.Valid -> onSaveOverride(amount.cents)
                            else -> refusedText = overrideText
                        }
                    },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    enabled = overrideText.isNotBlank(),
                )
                if (appointment.finalTotalCentsOverride != null) {
                    SecondaryButton(
                        stringResource(R.string.clear_final_total_override),
                        // The override is gone at once, and its text with it.
                        onClick = {
                            onOverrideText("")
                            onSaveOverride(null)
                        },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun NoteCard(appointment: Appointment, note: String, onNote: (String) -> Unit, onSave: (String) -> Unit) {
    HcmCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.appointment_note_title), style = MaterialTheme.typography.headlineSmall)
            HcmTextField(note, onNote, label = "", placeholder = stringResource(R.string.appointment_note_placeholder), singleLine = false, minLines = 3, maxLength = TextLimits.NOTE)
            SecondaryButton(
                stringResource(R.string.save_appointment_note),
                onClick = { onSave(note) },
                modifier = Modifier.fillMaxWidth(),
                enabled = UnsavedInput.appointmentNote(note, appointment.note),
            )
        }
    }
}

/** The stored photos, then the ones that are not on the server yet: those are the newest. */
@Composable
private fun PhotoGrid(
    photos: List<AppointmentPhoto>,
    uploads: List<PhotoUpload>,
    onOpen: (AppointmentPhoto) -> Unit,
    onOpenUpload: (PhotoUpload) -> Unit,
) {
    BoxWithConstraints {
        // Every tag in one size, in which the longest category takes one line of a tile.
        val tagStyle = fittedTextStyle(PhotoCategory.entries.map { stringResource(it.label) }, tagTextStyle, photoTagWidth(maxWidth))

        Column(verticalArrangement = Arrangement.spacedBy(PhotoGridGap)) {
            (0 until photos.size + uploads.size).chunked(PHOTOS_IN_A_ROW).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(PhotoGridGap)) {
                    row.forEach { index ->
                        if (index < photos.size) {
                            StoredPhotoTile(photos[index], tagStyle, onOpen)
                        } else {
                            UploadTile(uploads[index - photos.size], tagStyle, onOpenUpload)
                        }
                    }
                    // Keep cells square when the last row is short.
                    repeat(PHOTOS_IN_A_ROW - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private const val PHOTOS_IN_A_ROW = 3
private val PhotoGridGap = 8.dp
private val PhotoTagMargin = 6.dp

/**
 * The width the category has in the tag of a photo tile, in a grid [gridWidth] wide: a tile less
 * the tag's margin and padding, and 2 dp more for the rounding to whole pixels (see `segmentLabelWidth`).
 */
internal fun photoTagWidth(gridWidth: Dp): Dp =
    (gridWidth - PhotoGridGap * (PHOTOS_IN_A_ROW - 1)) / PHOTOS_IN_A_ROW - (PhotoTagMargin + TagPadding) * 2 - 2.dp

/**
 * Whether there is a stored picture to show. A photo that is recorded as failed has none, and
 * neither has one whose address is not a web address ([isWebAddress]).
 */
internal val AppointmentPhoto.hasPicture: Boolean get() = status == PhotoStatus.STORED && isWebAddress(url)

/** What a tile shows of a photo that has a picture: the smaller one, when it has one. */
internal val AppointmentPhoto.tilePicture: String get() = thumbnailUrl.takeIf(::isWebAddress) ?: url

/**
 * An address that is fetched from the web, which is the only kind a stored photo has. The server
 * records only addresses in its own photo storage, but a photo recorded before it checked can
 * hold any text, and the image loader reads whatever it is handed: a file or other content of
 * the device as well. Anything else is a photo without a picture.
 */
internal fun isWebAddress(address: String): Boolean =
    address.startsWith("https://", ignoreCase = true) || address.startsWith("http://", ignoreCase = true)

/**
 * What a screen reader is told a photo tile is, with the category as its argument: a photo, and
 * the state it is in unless it is simply stored. A photo that failed must not sound like a stored one.
 */
@StringRes
internal fun photoTileDescription(photo: AppointmentPhoto): Int =
    if (photo.hasPicture) R.string.appointment_photo_description else R.string.appointment_photo_failed_description

/** The same for a photo that is not on the server yet: it is on its way there or it failed. */
@StringRes
internal fun photoTileDescription(upload: PhotoUpload): Int =
    if (upload.state == UploadState.FAILED) R.string.appointment_photo_failed_description else R.string.appointment_photo_uploading_description

/**
 * One square of the photo grid: a button. [description] is all a screen reader is told (see
 * [photoTileDescription]): the picture, the mark over it and the tag are not read out as well.
 */
@Composable
private fun RowScope.PhotoTile(description: String, category: String, tagStyle: TextStyle, onClick: () -> Unit, picture: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        picture()
        Tag(
            category,
            modifier = Modifier.align(Alignment.BottomStart).padding(PhotoTagMargin).clearAndSetSemantics {},
            content = HcmColors.Surface,
            container = HcmColors.Ink.copy(alpha = 0.72f),
            style = tagStyle,
        )
    }
}

@Composable
private fun RowScope.StoredPhotoTile(photo: AppointmentPhoto, tagStyle: TextStyle, onOpen: (AppointmentPhoto) -> Unit) {
    val category = stringResource(photo.category.label)

    PhotoTile(stringResource(photoTileDescription(photo), category), category, tagStyle, onClick = { onOpen(photo) }) {
        if (photo.hasPicture) {
            StoredPicture(photo.tilePicture, contentDescription = null, ContentScale.Crop, Modifier.fillMaxSize())
        } else {
            Icon(
                Icons.Rounded.BrokenImage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/**
 * The picture of a stored photo, from its web [address]. A picture that does not load (no
 * connection, a file that is gone, an address that serves no image) leaves no empty place: it is
 * marked like a photo without a picture, with [failedText] under the mark where there is room for it.
 */
@Composable
internal fun StoredPicture(address: String, contentDescription: String?, contentScale: ContentScale, modifier: Modifier = Modifier, failedText: String? = null) {
    var failed by remember(address) { mutableStateOf(false) }

    Box(modifier, contentAlignment = Alignment.Center) {
        AsyncImage(
            model = address,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(),
            onSuccess = { failed = false },
            onError = { failed = true },
        )
        if (failed) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.BrokenImage, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                failedText?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** A photo that is not on the server yet: the picture as it is on the device, dimmed, under a spinner or a failure mark. */
@Composable
private fun RowScope.UploadTile(upload: PhotoUpload, tagStyle: TextStyle, onOpen: (PhotoUpload) -> Unit) {
    val category = stringResource(upload.photo.category.label)

    PhotoTile(stringResource(photoTileDescription(upload), category), category, tagStyle, onClick = { onOpen(upload) }) {
        AsyncImage(model = upload.picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(HcmColors.Ink.copy(alpha = 0.5f)))
        if (upload.state == UploadState.FAILED) {
            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = HcmColors.Surface, modifier = Modifier.align(Alignment.Center))
        } else {
            // The description says that it is uploading: the spinner adds nothing for a screen reader.
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp).clearAndSetSemantics {}, color = HcmColors.Surface, strokeWidth = 2.dp)
        }
    }
}

/**
 * Edit Service: the name, the price and the note of a Service that is on the Appointment. They are
 * saved together, as the form shows them. Its Client and its Color Formulas are not part of it.
 */
@Composable
private fun EditServiceSheet(
    service: AppointmentService,
    clientName: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (ServiceEdit.Values) -> Unit,
) {
    // Filled from the Service when the sheet opens; what is typed after that is kept as it is.
    var name by rememberSaveable(service.id) { mutableStateOf(service.name) }
    var price by rememberSaveable(service.id) { mutableStateOf(Money.centsToPrice(service.priceCents)) }
    var note by rememberSaveable(service.id) { mutableStateOf(service.note) }
    // The texts a save was refused for. Each reason stays under its field for as long as the text is that one.
    var refusedName by rememberSaveable(service.id) { mutableStateOf<String?>(null) }
    var refusedPrice by rememberSaveable(service.id) { mutableStateOf<String?>(null) }

    HcmBottomSheet(title = stringResource(R.string.edit_appointment_service), subtitle = clientName, onDismiss = onDismiss) {
        HcmTextField(
            name,
            { name = it },
            label = stringResource(R.string.appointment_service_name_label),
            placeholder = stringResource(R.string.placeholder_service_name),
            error = stringResource(R.string.service_name_required).takeIf { name == refusedName },
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
        PrimaryButton(
            stringResource(R.string.save_appointment_service),
            onClick = {
                refusedName = name.takeIf(ServiceEdit::isNameRefused)
                refusedPrice = price.takeIf(ServiceEdit::isPriceRefused)
                ServiceEdit.values(name, price, note)?.let(onSave)
            },
            modifier = Modifier.fillMaxWidth(),
            loading = busy,
        )
    }
}

/**
 * Appointment Copying: the Completed Appointments that have something to copy into this one,
 * each with whose Services and how many a copy from it adds. [copying] is a copy into this
 * Appointment whose request is running: the sheet says so and takes no second tap. [busy] is
 * any change in flight.
 */
@Composable
private fun CopyServicesSheet(
    sources: List<Appointment>,
    load: LoadState,
    onRetry: () -> Unit,
    copying: Boolean,
    busy: Boolean,
    format: TimeFormat,
    onDismiss: () -> Unit,
    onCopy: (String?) -> Unit,
) {
    HcmBottomSheet(title = stringResource(R.string.copy_completed_services), onDismiss = onDismiss) {
        if (load is LoadState.Failed) {
            InlineLoadFailed(load.message, onRetry)
        } else if (load != LoadState.Loaded) {
            LoadingRow(Modifier.heightIn(max = 96.dp))
        } else if (sources.isEmpty()) {
            // "Nothing to copy" is only true of a list that has loaded.
            InlineEmpty(stringResource(R.string.copy_services_empty))
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (copying) {
                    // In the button's place and as tall as it, so that nothing moves under a second tap.
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 50.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.copy_services_running), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    // The first source of the list is the one a copy takes when it names none.
                    SecondaryButton(
                        stringResource(R.string.copy_services_latest),
                        onClick = { onCopy(null) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                        icon = Icons.Rounded.ContentCopy,
                    )
                }
                sources.forEach { source ->
                    HcmCard(
                        // Off, and dimmed to show it, while the copy runs and until the screen is loaded again after it,
                        // which is also after a copy that was refused: the sources stay where they are and take no tap.
                        Modifier.fillMaxWidth().alpha(if (busy) 0.5f else 1f),
                        onClick = { onCopy(source.id) },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        enabled = !busy,
                    ) {
                        // What the copy takes of the source: in a group Appointment that is not all of it.
                        Text(AppointmentCopying.clientNames(source).joinToString(", "), style = MaterialTheme.typography.titleMedium)
                        Text(format.dateAndTimeRange(source), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            AppointmentCopying.services(source).joinToString(", ") { it.name },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // How many, because the names above are cut off after two lines.
                        Text(
                            pluralStringResource(R.plurals.copy_services_count, source.copyableServiceCount, source.copyableServiceCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FormulaSheet(
    service: AppointmentService,
    formula: ColorFormula?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onMissingFormula: () -> Unit,
    onSave: (formula: String, placement: String) -> Unit,
    onDelete: (String) -> Unit,
) {
    // Filled from the Color Formula when the sheet opens; what is typed after that is kept as it is.
    var text by rememberSaveable(service.id, formula?.id) { mutableStateOf(formula?.formula.orEmpty()) }
    var placement by rememberSaveable(service.id, formula?.id) { mutableStateOf(formula?.placement.orEmpty()) }
    var confirmDelete by rememberSaveable(service.id, formula?.id) { mutableStateOf(false) }

    HcmBottomSheet(title = stringResource(R.string.color_formula_label), subtitle = service.name, onDismiss = onDismiss) {
        HcmTextField(text, { text = it }, label = stringResource(R.string.color_formula_label), placeholder = stringResource(R.string.placeholder_formula_text), singleLine = false, minLines = 2, maxLength = TextLimits.FORMULA)
        HcmTextField(placement, { placement = it }, label = stringResource(R.string.color_formula_placement_label), placeholder = stringResource(R.string.placeholder_formula_placement), maxLength = TextLimits.PLACEMENT)
        PrimaryButton(
            stringResource(if (formula != null) R.string.save_color_formula else R.string.add_color_formula),
            onClick = { if (text.isBlank()) onMissingFormula() else onSave(text, placement) },
            modifier = Modifier.fillMaxWidth(),
            loading = busy,
        )
        formula?.let { existing ->
            TextAction(
                stringResource(R.string.delete_color_formula),
                onClick = { confirmDelete = true },
                icon = Icons.Rounded.DeleteOutline,
                danger = true,
                enabled = !busy,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }

    if (confirmDelete && formula != null) {
        ConfirmDialog(
            title = stringResource(R.string.delete_color_formula) + "?",
            body = stringResource(R.string.delete_color_formula_confirm_body, service.name),
            confirmText = stringResource(R.string.delete),
            danger = true,
            onConfirm = {
                confirmDelete = false
                onDelete(formula.id)
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun AddPhotoSheet(
    participant: AppointmentParticipant,
    replacing: AppointmentPhoto?,
    onDismiss: () -> Unit,
    onPick: (PhotoCategory, fromCamera: Boolean) -> Unit,
) {
    var category by rememberSaveable(participant.clientId, replacing?.id) { mutableStateOf(replacing?.category ?: PhotoCategory.AFTER) }

    HcmBottomSheet(title = stringResource(R.string.appointment_photos_title), subtitle = participant.name, onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(stringResource(R.string.appointment_photo_category_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SegmentedControl(PhotoCategory.entries, category, { category = it }, label = { stringResource(it.label) })
        }
        // One under the other where half the sheet is too narrow for 'Camera' or 'Gallery' on one line.
        SideBySideOrStacked(Modifier.fillMaxWidth()) {
            PrimaryButton(stringResource(R.string.add_photo_from_camera), onClick = { onPick(category, true) }, icon = Icons.Rounded.CameraAlt)
            PrimaryButton(stringResource(R.string.add_photo_from_gallery), onClick = { onPick(category, false) }, icon = Icons.Rounded.PhotoLibrary)
        }
    }
}

@Composable
private fun PhotoSheet(
    photo: AppointmentPhoto,
    clientName: String,
    canUpload: Boolean,
    onDismiss: () -> Unit,
    onCategory: (PhotoCategory) -> Unit,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
) {
    var confirmRemove by rememberSaveable(photo.id) { mutableStateOf(false) }

    HcmBottomSheet(title = stringResource(photo.category.label), subtitle = clientName, onDismiss = onDismiss) {
        if (photo.hasPicture) {
            StoredPicture(
                photo.url,
                contentDescription = stringResource(photo.category.label),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceVariant),
                failedText = stringResource(R.string.appointment_photo_not_loaded),
            )
        } else {
            // Worded here, in the Stylist's language. A text that a record carries as its reason is never shown.
            NotSavedPhoto(canUpload, onRetry)
        }
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(stringResource(R.string.appointment_photo_category_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Not compared with the category shown: while a change is being saved, a tap back to it is a change again.
            SegmentedControl(PhotoCategory.entries, photo.category, onCategory, label = { stringResource(it.label) })
        }
        TextAction(
            stringResource(R.string.remove_appointment_photo),
            onClick = { confirmRemove = true },
            icon = Icons.Rounded.DeleteOutline,
            danger = true,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }

    if (confirmRemove) {
        ConfirmRemovePhoto(
            onConfirm = {
                confirmRemove = false
                onRemove()
            },
            onDismiss = { confirmRemove = false },
        )
    }
}

/**
 * What the sheet of a photo that was not saved says and offers: 'Upload again', or, on a server
 * without photo storage ([canUpload] is false), why another try cannot work.
 */
@Composable
private fun NotSavedPhoto(canUpload: Boolean, onRetry: () -> Unit) {
    Text(
        stringResource(if (canUpload) R.string.appointment_photo_upload_failed else R.string.appointment_photos_unavailable),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.error,
    )
    if (canUpload) {
        SecondaryButton(stringResource(R.string.retry_appointment_photo), onClick = onRetry, modifier = Modifier.fillMaxWidth())
    }
}

/** 'Remove photo' asks first, for a stored photo and for one that is not on the server yet: a slip must not cost the photo. */
@Composable
private fun ConfirmRemovePhoto(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.remove_appointment_photo) + "?",
        body = stringResource(R.string.remove_appointment_photo_confirm_body),
        confirmText = stringResource(R.string.delete),
        danger = true,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/** A photo that is not on the server yet. One that failed is uploaded again or removed from here; one on its way can be removed. */
@Composable
private fun UploadSheet(
    upload: PhotoUpload,
    clientName: String,
    canUpload: Boolean,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    var confirmRemove by rememberSaveable(upload.id) { mutableStateOf(false) }

    HcmBottomSheet(title = stringResource(upload.photo.category.label), subtitle = clientName, onDismiss = onDismiss) {
        AsyncImage(
            model = upload.picture,
            contentDescription = stringResource(upload.photo.category.label),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceVariant),
        )
        if (upload.state == UploadState.FAILED) {
            NotSavedPhoto(canUpload, onRetry)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.appointment_photo_uploading), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextAction(
            stringResource(R.string.remove_appointment_photo),
            onClick = { confirmRemove = true },
            icon = Icons.Rounded.DeleteOutline,
            danger = true,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }

    // Gone with the sheet when the photo is stored while the question is open: there is nothing to remove from the device then.
    if (confirmRemove) {
        ConfirmRemovePhoto(
            onConfirm = {
                confirmRemove = false
                onRemove()
            },
            onDismiss = { confirmRemove = false },
        )
    }
}
