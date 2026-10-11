package com.northphoenix.hairdresserclientmanager.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.ImportContacts
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.data.ApiException
import com.northphoenix.hairdresserclientmanager.data.AppointmentPhoto
import com.northphoenix.hairdresserclientmanager.data.ClientHistoryEntry
import com.northphoenix.hairdresserclientmanager.data.ClientProfile
import com.northphoenix.hairdresserclientmanager.data.DeviceContacts
import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.data.PhotoCategory
import com.northphoenix.hairdresserclientmanager.domain.ClientRules
import com.northphoenix.hairdresserclientmanager.domain.ContactFields
import com.northphoenix.hairdresserclientmanager.domain.ContactImport
import com.northphoenix.hairdresserclientmanager.domain.CopiedContact
import com.northphoenix.hairdresserclientmanager.domain.ImportedContact
import com.northphoenix.hairdresserclientmanager.domain.Money
import com.northphoenix.hairdresserclientmanager.domain.OneLine
import com.northphoenix.hairdresserclientmanager.domain.UnsavedInput
import com.northphoenix.hairdresserclientmanager.i18n.label
import com.northphoenix.hairdresserclientmanager.i18n.messageRes
import com.northphoenix.hairdresserclientmanager.state.AppViewModel
import com.northphoenix.hairdresserclientmanager.state.LoadState
import com.northphoenix.hairdresserclientmanager.ui.LocalTimeFormat
import com.northphoenix.hairdresserclientmanager.ui.components.BottomActionBar
import com.northphoenix.hairdresserclientmanager.ui.components.ColorFormulaCard
import com.northphoenix.hairdresserclientmanager.ui.components.ConfirmDialog
import com.northphoenix.hairdresserclientmanager.ui.components.Hairline
import com.northphoenix.hairdresserclientmanager.ui.components.HcmCard
import com.northphoenix.hairdresserclientmanager.ui.components.HcmTextField
import com.northphoenix.hairdresserclientmanager.ui.components.InlineEmpty
import com.northphoenix.hairdresserclientmanager.ui.components.InlineLoadFailed
import com.northphoenix.hairdresserclientmanager.ui.components.ItemMissing
import com.northphoenix.hairdresserclientmanager.ui.components.LoadFailed
import com.northphoenix.hairdresserclientmanager.ui.components.PrimaryButton
import com.northphoenix.hairdresserclientmanager.ui.components.ScreenColumn
import com.northphoenix.hairdresserclientmanager.ui.components.SecondaryButton
import com.northphoenix.hairdresserclientmanager.ui.components.SegmentedControl
import com.northphoenix.hairdresserclientmanager.ui.components.StatusChip
import com.northphoenix.hairdresserclientmanager.ui.components.SubScreen
import com.northphoenix.hairdresserclientmanager.ui.components.Tag
import com.northphoenix.hairdresserclientmanager.ui.components.TagPadding
import com.northphoenix.hairdresserclientmanager.ui.components.TextAction
import com.northphoenix.hairdresserclientmanager.ui.components.fittedTextStyle
import com.northphoenix.hairdresserclientmanager.ui.components.tagTextStyle
import com.northphoenix.hairdresserclientmanager.ui.openUri
import com.northphoenix.hairdresserclientmanager.ui.theme.HcmColors
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ClientDialog { DUPLICATE_PHONE, DELETE, IMPORT_OVERWRITE }

/** A Contact Import is saved as its fields before it followed by its fields after it, and none as nothing. */
internal val CopiedContactSaver = listSaver<CopiedContact?, String>(
    save = { copied -> listOfNotNull(copied?.before, copied?.after).flatMap { listOf(it.name, it.phone, it.email, it.address) } },
    restore = { saved ->
        saved.takeIf { it.size == 8 }?.chunked(4) { (name, phone, email, address) -> ContactFields(name, phone, email, address) }
            ?.let { (before, after) -> CopiedContact(before, after) }
    },
)

/** Client Profile editor: details, Contact Import, Profile Share and the Client's Appointment history. */
@Composable
fun ClientEditorScreen(
    viewModel: AppViewModel,
    clientId: String?,
    onBack: () -> Unit,
    onOpenAppointment: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clients by viewModel.clients.collectAsStateWithLifecycle()
    val stylist by viewModel.stylist.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    // A new Client becomes an existing one in place after the first save.
    var currentId by rememberSaveable { mutableStateOf(clientId) }
    val existing = clients.firstOrNull { it.id == currentId }
    val defaultLanguage = stylist?.language ?: Language.RU

    var loadedFor by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var language by rememberSaveable { mutableStateOf(defaultLanguage) }
    var phone by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    // The form as it was when it was opened, filled or last saved. Back asks before it discards what was changed since.
    var savedContent by rememberSaveable { mutableStateOf(UnsavedInput.clientForm(name, language, phone, email, address, note)) }
    // The email a save was refused for: the reason shows under the field until the text is another one.
    var refusedEmail by rememberSaveable { mutableStateOf<String?>(null) }
    // How many saves it was refused for: each one brings the field back into view, however far the Stylist scrolled.
    var emailRefusals by remember { mutableIntStateOf(0) }
    // The Contact Import made since the last save: the next one takes its place (see ContactImport.copy).
    var lastImport by rememberSaveable(stateSaver = CopiedContactSaver) { mutableStateOf<CopiedContact?>(null) }
    var dialog by remember { mutableStateOf<ClientDialog?>(null) }
    var pendingImport by remember { mutableStateOf<ImportedContact?>(null) }
    var shareImageSvg by remember { mutableStateOf<String?>(null) }
    // How looking for a Client who is not in the list went (see below).
    var lookup by remember { mutableStateOf<LoadState>(LoadState.Loading) }
    var lookupAttempt by remember { mutableIntStateOf(0) }

    // Fill the form once per Client; later list refreshes must not overwrite what is being typed.
    LaunchedEffect(existing?.id) {
        if (existing != null && loadedFor != existing.id) {
            // One line each, as a save stores them, also when they were saved before names were cleaned.
            name = OneLine.clean(existing.name)
            language = existing.language
            phone = OneLine.clean(existing.phone)
            email = existing.email
            address = existing.address
            note = existing.note
            loadedFor = existing.id
            savedContent = UnsavedInput.clientForm(name, language, phone, email, address, note)
        }
    }

    // Only what the contact has is copied: every other field, the Client Note among them, stays as it is.
    fun applyImport(imported: ImportedContact) {
        val copied = ContactImport.copy(ContactFields(name, phone, email, address), imported, lastImport)

        name = copied.after.name
        phone = copied.after.phone
        email = copied.after.email
        address = copied.after.address
        lastImport = copied
    }

    fun save() {
        val submitted = UnsavedInput.clientForm(name, language, phone, email, address, note)

        viewModel.saveClient(currentId, name, language, phone, email, address, note) { saved ->
            currentId = saved.id
            loadedFor = saved.id
            savedContent = submitted
            // What a contact put in the form is the Client's own from here on.
            lastImport = null
        }
    }

    val contactPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        if (uri != null) {
            scope.launch {
                val imported = DeviceContacts.read(context, uri)?.let(ContactImport::normalize)

                when {
                    imported == null || !imported.hasFields -> viewModel.notify(R.string.import_contact_empty, isError = true)
                    // Whether the Client exists is read from the id, not from the list: after Android has
                    // re-created the app the contact arrives before the list does. The question then
                    // waits for the form, which comes back as it was left.
                    ContactImport.asksFirst(ContactFields(name, phone, email, address), imported, existingClient = currentId != null, lastImport) -> {
                        pendingImport = imported
                        dialog = ClientDialog.IMPORT_OVERWRITE
                    }
                    else -> applyImport(imported)
                }
            }
        }
    }
    val contactsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            contactPicker.launch(null)
        } else {
            viewModel.notify(R.string.import_contact_permission_denied, isError = true)
        }
    }

    // A profile opened for a Client who is not in the list. The list may be older than the Client,
    // or the Client may have been deleted: it is loaded again, and until then this is not a form.
    val absentId = currentId.takeIf { existing == null }

    if (absentId != null) {
        LaunchedEffect(absentId, lookupAttempt) {
            lookup = LoadState.Loading
            lookup = viewModel.findClient(absentId)
        }

        SubScreen(title = stringResource(R.string.edit_client_title), onBack = onBack, busy = busy) { padding ->
            when (val state = lookup) {
                is LoadState.Failed -> LoadFailed(state.message, onRetry = { lookupAttempt++ }, Modifier.padding(padding))
                LoadState.Missing -> ItemMissing(
                    stringResource(R.string.client_missing_title),
                    stringResource(R.string.client_missing_body),
                    onBack,
                    Modifier.padding(padding),
                )
                else -> LoadingRow(Modifier.padding(padding))
            }
        }
        return
    }

    SubScreen(
        title = stringResource(if (existing != null) R.string.edit_client_title else R.string.new_client_title),
        onBack = onBack,
        busy = busy,
        unsaved = UnsavedInput.clientForm(name, language, phone, email, address, note) != savedContent,
        bottomBar = {
            BottomActionBar {
                PrimaryButton(
                    stringResource(if (existing != null) R.string.save_client else R.string.create_client),
                    onClick = {
                        refusedEmail = email.takeUnless(ClientRules::isEmail)

                        when {
                            !OneLine.hasVisibleCharacter(name) -> viewModel.notify(R.string.client_name_required, isError = true)
                            // The reason is under the field.
                            refusedEmail != null -> emailRefusals++
                            ClientRules.isDuplicatePhone(clients, currentId, phone) -> dialog = ClientDialog.DUPLICATE_PHONE
                            else -> save()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    loading = busy,
                )
            }
        },
    ) { padding ->
        ScreenColumn(padding) {
            SecondaryButton(
                stringResource(R.string.import_contact),
                onClick = { contactsPermission.launch(Manifest.permission.READ_CONTACTS) },
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Rounded.ImportContacts,
            )

            HcmCard {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    HcmTextField(name, { name = it }, label = stringResource(R.string.client_name_label), placeholder = stringResource(R.string.placeholder_client_name), capitalization = KeyboardCapitalization.Words)
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(stringResource(R.string.client_language_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SegmentedControl(Language.entries, language, { language = it }, label = { stringResource(it.label) })
                    }
                    HcmTextField(phone, { phone = it }, label = stringResource(R.string.client_phone_label), placeholder = "+1 555 0100", keyboardType = KeyboardType.Phone)
                    HcmTextField(
                        email,
                        { email = it },
                        label = stringResource(R.string.client_email_label),
                        placeholder = "client@example.com",
                        keyboardType = KeyboardType.Email,
                        capitalization = KeyboardCapitalization.None,
                        error = stringResource(R.string.client_email_invalid).takeIf { email == refusedEmail },
                        errorAttempt = emailRefusals,
                    )
                    HcmTextField(
                        address,
                        { address = it },
                        label = stringResource(R.string.client_address_label),
                        placeholder = stringResource(R.string.placeholder_address),
                        singleLine = false,
                        minLines = 2,
                        capitalization = KeyboardCapitalization.Words,
                    )
                    HcmTextField(note, { note = it }, label = stringResource(R.string.client_note_label), placeholder = stringResource(R.string.placeholder_client_note), singleLine = false, minLines = 3)
                }
            }

            if (existing != null) {
                ProfileShareCard(
                    client = existing,
                    shareUrl = existing.activeProfileShare?.let { viewModel.profileShareUrl(it.token) },
                    onCreate = { viewModel.createProfileShare(existing.id) },
                    onRevoke = { viewModel.revokeProfileShare(existing.id) },
                    onGenerateImage = { imageLanguage -> viewModel.buildShareImage(existing.id, imageLanguage) { shareImageSvg = it } },
                    onCopied = { viewModel.notify(R.string.profile_share_link_copied) },
                    onOpenFailed = { viewModel.notify(R.string.link_open_failed, isError = true) },
                )
                ClientHistoryCard(viewModel, existing.id, onOpenAppointment)
                TextAction(
                    stringResource(R.string.delete_client),
                    onClick = { dialog = ClientDialog.DELETE },
                    icon = Icons.Rounded.DeleteOutline,
                    danger = true,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }

    when (dialog) {
        ClientDialog.DUPLICATE_PHONE -> ConfirmDialog(
            title = stringResource(R.string.duplicate_phone_title),
            body = stringResource(R.string.duplicate_phone_body),
            confirmText = stringResource(R.string.duplicate_phone_continue),
            onConfirm = {
                dialog = null
                save()
            },
            onDismiss = { dialog = null },
        )
        ClientDialog.DELETE -> ConfirmDialog(
            title = stringResource(R.string.delete_client) + "?",
            body = stringResource(R.string.delete_client_confirm_body),
            confirmText = stringResource(R.string.delete),
            danger = true,
            onConfirm = {
                dialog = null
                currentId?.let { viewModel.deleteClient(it, onSuccess = onBack) }
            },
            onDismiss = { dialog = null },
        )
        ClientDialog.IMPORT_OVERWRITE -> ConfirmDialog(
            title = stringResource(R.string.import_contact_confirm_title),
            body = stringResource(R.string.import_contact_confirm_body),
            confirmText = stringResource(R.string.import_contact_apply),
            onConfirm = {
                pendingImport?.let(::applyImport)
                pendingImport = null
                dialog = null
            },
            onDismiss = {
                pendingImport = null
                dialog = null
            },
        )
        null -> Unit
    }

    shareImageSvg?.let { svg ->
        ShareImageDialog(svg = svg, onDismiss = { shareImageSvg = null }, onShareFailed = { viewModel.notify(R.string.link_open_failed, isError = true) })
    }
}

@Composable
private fun ProfileShareCard(
    client: ClientProfile,
    shareUrl: String?,
    onCreate: () -> Unit,
    onRevoke: () -> Unit,
    onGenerateImage: (Language) -> Unit,
    onCopied: () -> Unit,
    onOpenFailed: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // Share Image language defaults to the Client's language and is chosen per generation.
    var imageLanguage by remember(client.id) { mutableStateOf(client.language) }

    HcmCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.profile_share_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.profile_share_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (shareUrl == null) {
                SecondaryButton(stringResource(R.string.create_profile_share), onClick = onCreate, modifier = Modifier.fillMaxWidth(), icon = Icons.Rounded.Link)
            } else {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Text(
                            shareUrl,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                SecondaryButton(
                    stringResource(R.string.open_profile_share),
                    onClick = { if (!context.openUri(Uri.parse(shareUrl))) onOpenFailed() },
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.AutoMirrored.Rounded.OpenInNew,
                )
                SecondaryButton(
                    stringResource(R.string.copy_profile_share_link),
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipData.newPlainText(null, shareUrl).toClipEntry())
                            onCopied()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Rounded.ContentCopy,
                )
                Hairline()
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(stringResource(R.string.share_image_language_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SegmentedControl(Language.entries, imageLanguage, { imageLanguage = it }, label = { stringResource(it.label) })
                }
                SecondaryButton(stringResource(R.string.generate_share_image), onClick = { onGenerateImage(imageLanguage) }, modifier = Modifier.fillMaxWidth(), icon = Icons.Rounded.Image)
                TextAction(
                    stringResource(R.string.revoke_profile_share),
                    onClick = onRevoke,
                    icon = Icons.Rounded.LinkOff,
                    danger = true,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}

/**
 * The Client's Appointment history, newest first: each Appointment with this Client's part of it,
 * the Services with their prices, notes and Color Formulas, and the Client's photos.
 */
@Composable
private fun ClientHistoryCard(viewModel: AppViewModel, clientId: String, onOpenAppointment: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var history by remember(clientId) { mutableStateOf<List<ClientHistoryEntry>?>(null) }
    // Why there is no history to show; null while it is loading.
    var failure by remember(clientId) { mutableStateOf<Int?>(null) }

    fun load() {
        scope.launch {
            failure = null
            try {
                history = viewModel.clientHistory(clientId, refreshing = history != null)
            } catch (error: ApiException) {
                failure = error.messageRes()
            }
        }
    }

    // Each time the profile comes into view, from an Appointment opened here or from the background.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { load() }

    HcmCard(contentPadding = PaddingValues(vertical = 18.dp)) {
        Text(stringResource(R.string.appointment_history_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 18.dp))

        val entries = history
        val failed = failure

        when {
            // History that is on screen stays there when loading it again fails.
            entries == null && failed != null -> InlineLoadFailed(failed, onRetry = ::load, Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp))
            entries == null -> LoadingRow(Modifier.heightIn(max = 96.dp))
            entries.isEmpty() -> InlineEmpty(stringResource(R.string.appointment_history_empty), Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp))
            else -> entries.forEachIndexed { index, entry ->
                if (index > 0) {
                    Hairline(Modifier.padding(horizontal = 18.dp))
                }
                ClientHistoryRow(entry, onOpen = { onOpenAppointment(entry.id) })
            }
        }
    }
}

private val HistoryInset = 18.dp
private val HistoryPhotoSize = 96.dp
private val HistoryPhotoTagMargin = 6.dp

/**
 * One Appointment of the history: when it was and how it ended, then what the Client had in it.
 * The Services and photos are this Client's alone, also in a group Appointment. The whole row
 * opens the Appointment.
 */
@Composable
private fun ClientHistoryRow(entry: ClientHistoryEntry, onOpen: () -> Unit) {
    val format = LocalTimeFormat.current

    Surface(onClick = onOpen, color = Color.Transparent) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.padding(horizontal = HistoryInset),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    format.mediumDate(format.zoned(entry.startsAt).toLocalDate()) + ", " + format.time(entry.startsAt),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                )
                StatusChip(entry.status)
            }
            // In the order they were added, as on the Appointment.
            entry.services.forEach { service ->
                Column(Modifier.padding(horizontal = HistoryInset), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(service.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Text(Money.format(service.priceCents), style = MaterialTheme.typography.bodyLarge)
                    }
                    if (service.note.isNotBlank()) {
                        Text(service.note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    service.colorFormulas.forEach { formula -> ColorFormulaCard(formula) }
                }
            }
            if (entry.photos.isNotEmpty()) {
                HistoryPhotos(entry.photos)
            }
        }
    }
}

/**
 * The Client's photos of one Appointment, oldest first, in a row that scrolls sideways, so a row
 * of the history is no taller for many photos.
 * Only pictures that are in view are loaded, down the history as well as along the row: a
 * picture is a whole photo to download, and a long history has hundreds of them. The history is
 * part of a page that is not lazy itself, so each row of photos looks for itself whether it is in
 * view. While it is not, its pictures keep their places and are not loaded.
 */
@Composable
private fun HistoryPhotos(photos: List<AppointmentPhoto>) {
    var inView by remember { mutableStateOf(false) }
    // Every tag in one size, in which the longest category takes one line of a picture (see `photoTagWidth`).
    val tagStyle = fittedTextStyle(
        PhotoCategory.entries.map { stringResource(it.label) },
        tagTextStyle,
        HistoryPhotoSize - (HistoryPhotoTagMargin + TagPadding) * 2 - 2.dp,
    )

    LazyRow(
        // What the page has scrolled out of view is clipped away, which leaves nothing of these bounds.
        Modifier.fillMaxWidth().onGloballyPositioned { inView = !it.boundsInWindow().isEmpty },
        contentPadding = PaddingValues(horizontal = HistoryInset),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(photos, key = { it.id }) { photo ->
            val category = stringResource(photo.category.label)
            val description = stringResource(R.string.appointment_photo_description, category)

            Box(
                Modifier
                    .size(HistoryPhotoSize)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .semantics { contentDescription = description },
            ) {
                if (inView) {
                    AsyncImage(
                        model = photo.thumbnailUrl.ifBlank { photo.url },
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // The description names the category already.
                Tag(
                    category,
                    modifier = Modifier.align(Alignment.BottomStart).padding(HistoryPhotoTagMargin).clearAndSetSemantics {},
                    content = HcmColors.Surface,
                    container = HcmColors.Ink.copy(alpha = 0.72f),
                    style = tagStyle,
                )
            }
        }
    }
}

/** Preview of a generated Share Image with a button to send it through the system share sheet. */
@Composable
private fun ShareImageDialog(svg: String, onDismiss: () -> Unit, onShareFailed: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val svgBytes = remember(svg) { svg.toByteArray() }
    var sharing by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.share_image_title), style = MaterialTheme.typography.headlineSmall)
                AsyncImage(
                    model = ImageRequest.Builder(context).data(svgBytes).build(),
                    contentDescription = stringResource(R.string.share_image_title),
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .verticalScroll(rememberScrollState()),
                )
                PrimaryButton(
                    stringResource(R.string.share_action),
                    onClick = {
                        scope.launch {
                            sharing = true
                            val shared = shareSvgAsPng(context, svgBytes)
                            sharing = false
                            if (!shared) onShareFailed()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Rounded.Share,
                    loading = sharing,
                )
                TextAction(stringResource(R.string.close), onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

/** Rasterises the SVG at its full size and hands the PNG to other apps through the FileProvider. */
private suspend fun shareSvgAsPng(context: Context, svgBytes: ByteArray): Boolean =
    try {
        val request = ImageRequest.Builder(context).data(svgBytes).size(Size.ORIGINAL).allowHardware(false).build()
        val bitmap = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
            ?: return false
        val file = withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "shared").apply { mkdirs() }
            File(directory, "share-image.png").also { target ->
                target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        send.clipData = ClipData.newRawUri(null, uri)
        context.startActivity(Intent.createChooser(send, null))
        true
    } catch (_: Exception) {
        false
    }
