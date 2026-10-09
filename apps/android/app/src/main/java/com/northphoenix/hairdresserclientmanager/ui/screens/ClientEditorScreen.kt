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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.data.ClientHistoryEntry
import com.northphoenix.hairdresserclientmanager.data.ClientProfile
import com.northphoenix.hairdresserclientmanager.data.DeviceContacts
import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.domain.ClientRules
import com.northphoenix.hairdresserclientmanager.domain.ContactImport
import com.northphoenix.hairdresserclientmanager.domain.ImportedContact
import com.northphoenix.hairdresserclientmanager.i18n.label
import com.northphoenix.hairdresserclientmanager.state.AppViewModel
import com.northphoenix.hairdresserclientmanager.ui.LocalTimeFormat
import com.northphoenix.hairdresserclientmanager.ui.components.BottomActionBar
import com.northphoenix.hairdresserclientmanager.ui.components.ConfirmDialog
import com.northphoenix.hairdresserclientmanager.ui.components.Hairline
import com.northphoenix.hairdresserclientmanager.ui.components.HcmCard
import com.northphoenix.hairdresserclientmanager.ui.components.HcmTextField
import com.northphoenix.hairdresserclientmanager.ui.components.InlineEmpty
import com.northphoenix.hairdresserclientmanager.ui.components.PrimaryButton
import com.northphoenix.hairdresserclientmanager.ui.components.ScreenColumn
import com.northphoenix.hairdresserclientmanager.ui.components.SecondaryButton
import com.northphoenix.hairdresserclientmanager.ui.components.SegmentedControl
import com.northphoenix.hairdresserclientmanager.ui.components.StatusChip
import com.northphoenix.hairdresserclientmanager.ui.components.SubScreen
import com.northphoenix.hairdresserclientmanager.ui.components.TextAction
import com.northphoenix.hairdresserclientmanager.ui.openUri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ClientDialog { DUPLICATE_PHONE, DELETE, IMPORT_OVERWRITE }

/** Client Profile editor: details, Contact Import, Profile Share and the Client's Appointment history. */
@Composable
fun ClientEditorScreen(
    viewModel: AppViewModel,
    clientId: String?,
    onBack: () -> Unit,
    onOpenAppointment: (String, String) -> Unit,
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
    var dialog by remember { mutableStateOf<ClientDialog?>(null) }
    var pendingImport by remember { mutableStateOf<ImportedContact?>(null) }
    var shareImageSvg by remember { mutableStateOf<String?>(null) }

    // Fill the form once per Client; later list refreshes must not overwrite what is being typed.
    LaunchedEffect(existing?.id) {
        if (existing != null && loadedFor != existing.id) {
            name = existing.name
            language = existing.language
            phone = existing.phone
            email = existing.email
            address = existing.address
            note = existing.note
            loadedFor = existing.id
        }
    }

    fun applyImport(imported: ImportedContact) {
        imported.name?.let { name = it }
        imported.phone?.let { phone = it }
        imported.email?.let { email = it }
        imported.address?.let { address = it }
    }

    fun save() {
        viewModel.saveClient(currentId, name, language, phone, email, address, note) { saved ->
            currentId = saved.id
            loadedFor = saved.id
        }
    }

    val contactPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        if (uri != null) {
            scope.launch {
                val imported = DeviceContacts.read(context, uri)?.let(ContactImport::normalize)

                when {
                    imported == null || !imported.hasFields -> viewModel.notify(R.string.import_contact_empty, isError = true)
                    existing != null -> {
                        pendingImport = imported
                        dialog = ClientDialog.IMPORT_OVERWRITE
                    }
                    else -> {
                        name = ""
                        phone = ""
                        email = ""
                        address = ""
                        note = ""
                        applyImport(imported)
                    }
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

    SubScreen(
        title = stringResource(if (existing != null) R.string.edit_client_title else R.string.new_client_title),
        onBack = onBack,
        busy = busy,
        bottomBar = {
            BottomActionBar {
                PrimaryButton(
                    stringResource(if (existing != null) R.string.save_client else R.string.create_client),
                    onClick = {
                        when {
                            name.isBlank() -> viewModel.notify(R.string.client_name_required, isError = true)
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

@Composable
private fun ClientHistoryCard(viewModel: AppViewModel, clientId: String, onOpenAppointment: (String, String) -> Unit) {
    val format = LocalTimeFormat.current
    var history by remember(clientId) { mutableStateOf<List<ClientHistoryEntry>?>(null) }

    LaunchedEffect(clientId) { history = viewModel.clientHistory(clientId) }

    HcmCard(contentPadding = PaddingValues(vertical = 18.dp)) {
        Text(stringResource(R.string.appointment_history_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 18.dp))

        val entries = history

        when {
            entries == null -> LoadingRow(Modifier.heightIn(max = 96.dp))
            entries.isEmpty() -> InlineEmpty(stringResource(R.string.appointment_history_empty), Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp))
            else -> entries.forEachIndexed { index, entry ->
                if (index > 0) {
                    Hairline(Modifier.padding(horizontal = 18.dp))
                }
                Surface(onClick = { onOpenAppointment(entry.id, entry.startsAt) }, color = Color.Transparent) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                format.mediumDate(format.zoned(entry.startsAt).toLocalDate()) + ", " + format.time(entry.startsAt),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            val services = entry.services.filter { it.clientId == clientId }.joinToString(", ") { it.name }
                            if (services.isNotEmpty()) {
                                Text(
                                    services,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        StatusChip(entry.status)
                    }
                }
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
