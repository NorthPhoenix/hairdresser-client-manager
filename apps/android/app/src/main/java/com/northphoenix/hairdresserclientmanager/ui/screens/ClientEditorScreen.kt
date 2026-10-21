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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.ImportContacts
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
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
import coil3.request.maxBitmapSize
import coil3.size.Size
import coil3.toBitmap
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.auth.AccountName
import com.northphoenix.hairdresserclientmanager.data.AppointmentPhoto
import com.northphoenix.hairdresserclientmanager.data.ClientHistoryEntry
import com.northphoenix.hairdresserclientmanager.data.ClientProfile
import com.northphoenix.hairdresserclientmanager.data.DeviceContacts
import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.data.PhotoCategory
import com.northphoenix.hairdresserclientmanager.data.ShareChoice
import com.northphoenix.hairdresserclientmanager.data.SharedPictures
import com.northphoenix.hairdresserclientmanager.domain.PhoneFormat
import com.northphoenix.hairdresserclientmanager.domain.ClientRules
import com.northphoenix.hairdresserclientmanager.domain.ContactFields
import com.northphoenix.hairdresserclientmanager.domain.ContactImport
import com.northphoenix.hairdresserclientmanager.domain.CopiedContact
import com.northphoenix.hairdresserclientmanager.domain.ImportedContact
import com.northphoenix.hairdresserclientmanager.domain.ChangedElsewhere
import com.northphoenix.hairdresserclientmanager.domain.Money
import com.northphoenix.hairdresserclientmanager.domain.OneLine
import com.northphoenix.hairdresserclientmanager.domain.ShareImageSize
import com.northphoenix.hairdresserclientmanager.domain.TextLimits
import com.northphoenix.hairdresserclientmanager.domain.UnsavedInput
import com.northphoenix.hairdresserclientmanager.i18n.label
import com.northphoenix.hairdresserclientmanager.state.AppViewModel
import com.northphoenix.hairdresserclientmanager.state.LoadState
import com.northphoenix.hairdresserclientmanager.state.Read
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
import com.northphoenix.hairdresserclientmanager.ui.components.rememberRequestKey
import com.northphoenix.hairdresserclientmanager.ui.components.SecondaryButton
import com.northphoenix.hairdresserclientmanager.ui.components.SegmentedControl
import com.northphoenix.hairdresserclientmanager.ui.components.StatusChip
import com.northphoenix.hairdresserclientmanager.ui.components.SubScreen
import com.northphoenix.hairdresserclientmanager.ui.components.SwitchRow
import com.northphoenix.hairdresserclientmanager.ui.components.Tag
import com.northphoenix.hairdresserclientmanager.ui.components.TagPadding
import com.northphoenix.hairdresserclientmanager.ui.components.TextAction
import com.northphoenix.hairdresserclientmanager.ui.components.collectWhileOpen
import com.northphoenix.hairdresserclientmanager.ui.components.fittedTextStyle
import com.northphoenix.hairdresserclientmanager.ui.components.tagTextStyle
import com.northphoenix.hairdresserclientmanager.ui.openUri
import com.northphoenix.hairdresserclientmanager.ui.theme.HcmColors
import java.io.File
import kotlinx.coroutines.CancellationException
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

/**
 * What a Profile Share that is not created yet will show, saved as the names of the choices that
 * are on. One text and not a list: a list saver keeps an empty list as nothing, and "nothing is
 * on" would come back as the defaults.
 */
internal val ShareChoicesSaver = Saver<Set<ShareChoice>, String>(
    save = { included -> included.joinToString(",") { it.name } },
    restore = { names -> names.split(",").let { on -> ShareChoice.entries.filter { it.name in on }.toSet() } },
)

/**
 * Client Profile editor: details, Contact Import, Profile Share and the Client's Appointment history.
 * A new Client is created here; an existing one is edited here from their Client Profile
 * ([ClientProfileScreen]). [onDeleted] closes it once the Client is deleted.
 */
@Composable
fun ClientEditorScreen(
    viewModel: AppViewModel,
    clientId: String?,
    onBack: () -> Unit,
    onOpenAppointment: (String) -> Unit,
    onDeleted: () -> Unit = onBack,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clients = viewModel.clients.collectAsStateWithLifecycle().value.data.orEmpty()
    val stylist by viewModel.stylist.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val accountName by viewModel.accountName.collectAsStateWithLifecycle()

    // A new Client becomes an existing one in place after the first save.
    var currentId by rememberSaveable { mutableStateOf(clientId) }
    val existing = clients.firstOrNull { it.id == currentId }
    val defaultLanguage = stylist?.language ?: Language.RU

    var loadedFor by rememberSaveable { mutableStateOf<String?>(null) }
    // The version of the Client the form was filled from or last saved as: a save names it, so the server can refuse a form that is older than the Client.
    var loadedVersion by rememberSaveable { mutableStateOf<String?>(null) }
    // Names the creation of this form's new Client, so that a second try of it creates no second Client.
    val requestKey = rememberRequestKey()
    var name by rememberSaveable { mutableStateOf("") }
    var language by rememberSaveable { mutableStateOf(defaultLanguage) }
    var phone by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    // Who recommended the Client ("Посоветовала"), by id; null for nobody.
    var referredId by rememberSaveable { mutableStateOf<String?>(null) }
    var pickingReferrer by rememberSaveable { mutableStateOf(false) }
    // The form as it was when it was opened, filled or last saved. Back asks before it discards what was changed since.
    var savedContent by rememberSaveable { mutableStateOf(UnsavedInput.clientForm(name, language, phone, email, address, note, referredId)) }
    // What the last save sent, until its reply has come or the Client was taken in again: a Client who comes back as this was saved from here.
    var sentContent by rememberSaveable { mutableStateOf<List<String>?>(null) }
    // The email a save was refused for: the reason shows under the field until the text is another one.
    var refusedEmail by rememberSaveable { mutableStateOf<String?>(null) }
    // How many saves it was refused for: each one brings the field back into view, however far the Stylist scrolled.
    var emailRefusals by remember { mutableIntStateOf(0) }
    // The Contact Import made since the last save: the next one takes its place (see ContactImport.copy).
    var lastImport by rememberSaveable(stateSaver = CopiedContactSaver) { mutableStateOf<CopiedContact?>(null) }
    var dialog by remember { mutableStateOf<ClientDialog?>(null) }
    var pendingImport by remember { mutableStateOf<ImportedContact?>(null) }
    var shareImageSvg by remember { mutableStateOf<String?>(null) }
    // The Profile Share the Stylist is asked about revoking. A question about one that is gone, or about another one, is not shown.
    var revokingShareId by rememberSaveable { mutableStateOf<String?>(null) }
    // How looking for a Client who is not in the list went (see below).
    var lookup by remember { mutableStateOf<LoadState>(LoadState.Loading) }
    var lookupAttempt by remember { mutableIntStateOf(0) }

    // Fill the form once per Client; later list refreshes must not overwrite what is being typed.
    // A Client who was saved on another device since is taken in field by field: what the Stylist
    // has not changed in the form is the Client's again, so that the form neither shows nor saves
    // what is no longer saved, and what they changed stays as it is (see ChangedElsewhere).
    LaunchedEffect(existing?.id, existing?.updatedAt) {
        if (existing != null && (loadedFor != existing.id || loadedVersion != existing.updatedAt)) {
            // What the form was filled with for this Client, in the order of UnsavedInput.clientForm. None: it is filled now.
            val loaded = savedContent.takeIf { loadedFor == existing.id }
            // A field the Stylist changed was saved as something else since: the form keeps theirs, and the next save replaces the other.
            var collided = false

            fun taken(field: Int, typed: String, now: String): String {
                val filledWith = loaded?.getOrNull(field) ?: return now

                collided = collided || ChangedElsewhere.collides(filledWith, typed, now, sentContent?.getOrNull(field))
                return ChangedElsewhere.field(filledWith, typed, now)
            }

            // One line each, as a save stores them, also when they were saved before names were cleaned.
            val savedName = OneLine.clean(existing.name)
            val savedPhone = OneLine.clean(existing.phone)

            name = taken(0, name, savedName)
            language = Language.valueOf(taken(1, language.name, existing.language.name))
            phone = taken(2, phone, savedPhone)
            email = taken(3, email, existing.email)
            address = taken(4, address, existing.address)
            note = taken(5, note, existing.note)
            referredId = taken(6, referredId.orEmpty(), existing.referredById.orEmpty()).ifEmpty { null }
            loadedFor = existing.id
            loadedVersion = existing.updatedAt
            savedContent = UnsavedInput.clientForm(savedName, existing.language, savedPhone, existing.email, existing.address, existing.note, existing.referredById)
            sentContent = null

            // The save names the version taken in here and is not refused, so this is the one moment to say it.
            if (collided) {
                viewModel.notify(R.string.error_changed_elsewhere_too, isError = true)
            }
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
        // A US number is saved as it is shown, "(512) 917-4528", however it was typed; any other as typed.
        phone = PhoneFormat.display(OneLine.clean(phone))

        // A Client who has been deleted since is no referrer: the link is gone on the server too.
        val referrerId = referredId?.takeIf { id -> clients.any { it.id == id } }
        val submitted = UnsavedInput.clientForm(name, language, phone, email, address, note, referrerId)

        // One line each, as the save sends them and as the form takes them in.
        sentContent = UnsavedInput.clientForm(OneLine.clean(name), language, OneLine.clean(phone), email, address, note, referrerId)
        viewModel.saveClient(currentId, name, language, phone, email, address, note, loadedVersion, requestKey, referrerId) { saved ->
            currentId = saved.id
            loadedFor = saved.id
            loadedVersion = saved.updatedAt
            savedContent = submitted
            sentContent = null
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

        SubScreen(title = stringResource(R.string.client_edit_title), onBack = onBack, saving = saving) { padding ->
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
        title = stringResource(if (existing != null) R.string.client_edit_title else R.string.new_client_title),
        onBack = onBack,
        saving = saving,
        unsaved = UnsavedInput.clientForm(name, language, phone, email, address, note, referredId) != savedContent,
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
                    HcmTextField(name, { name = it }, label = stringResource(R.string.client_name_label), placeholder = stringResource(R.string.placeholder_client_name), capitalization = KeyboardCapitalization.Words, maxLength = TextLimits.NAME)
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(stringResource(R.string.client_language_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SegmentedControl(Language.entries, language, { language = it }, label = { stringResource(it.label) })
                    }
                    HcmTextField(phone, { phone = it }, label = stringResource(R.string.client_phone_label), placeholder = stringResource(R.string.placeholder_client_phone), keyboardType = KeyboardType.Phone, maxLength = TextLimits.PHONE)
                    HcmTextField(
                        address,
                        { address = it },
                        label = stringResource(R.string.client_address_label),
                        placeholder = stringResource(R.string.placeholder_address),
                        singleLine = false,
                        minLines = 2,
                        capitalization = KeyboardCapitalization.Words,
                        maxLength = TextLimits.ADDRESS,
                    )
                    HcmTextField(note, { note = it }, label = stringResource(R.string.client_note_label), placeholder = stringResource(R.string.placeholder_client_note), singleLine = false, minLines = 3, maxLength = TextLimits.NOTE)
                    // Hardly any Client has an email address, so it comes last.
                    HcmTextField(
                        email,
                        { email = it },
                        label = stringResource(R.string.client_email_label),
                        placeholder = stringResource(R.string.optional),
                        keyboardType = KeyboardType.Email,
                        capitalization = KeyboardCapitalization.None,
                        error = stringResource(R.string.client_email_invalid).takeIf { email == refusedEmail },
                        errorAttempt = emailRefusals,
                        maxLength = TextLimits.EMAIL,
                    )
                }
            }

            ReferrerField(
                referrer = clients.firstOrNull { it.id == referredId },
                onPick = { pickingReferrer = true },
                onClear = { referredId = null },
            )

            if (existing != null) {
                ProfileShareCard(
                    client = existing,
                    shareUrl = existing.activeProfileShare?.let { viewModel.profileShareUrl(it.token) },
                    busy = busy,
                    // An account that could have a name and has none: the share then names nobody.
                    showsNoName = accountName == AccountName(),
                    onCreate = { included -> viewModel.createProfileShare(existing.id, included) },
                    onChoice = { choice, included -> viewModel.setProfileShareChoice(existing.id, choice, included) },
                    onRevoke = { revokingShareId = existing.activeProfileShare?.id },
                    onGenerateImage = { imageLanguage -> viewModel.buildShareImage(existing.id, imageLanguage) { shareImageSvg = it } },
                    onCopied = { viewModel.notify(R.string.profile_share_link_copied) },
                    onOpenFailed = { viewModel.notify(R.string.link_open_failed, isError = true) },
                )
                val history by rememberClientHistory(viewModel, existing.id)

                ClientHistoryCard(history, onRetry = { viewModel.loadClientHistory(existing.id) }, onOpenAppointment)
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

    if (pickingReferrer) {
        val clientList by viewModel.clients.collectAsStateWithLifecycle()

        // Anyone but the Client themselves.
        ClientPickerSheet(
            title = stringResource(R.string.client_referred_by_label),
            clients = clients,
            load = clientList.state,
            onRetry = viewModel::retryLoad,
            selectedIds = setOfNotNull(referredId),
            takenIds = setOfNotNull(currentId),
            onDismiss = { pickingReferrer = false },
            onPick = { picked ->
                referredId = picked.id
                pickingReferrer = false
            },
        )
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
                currentId?.let { viewModel.deleteClient(it, onSuccess = onDeleted) }
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

    // The link the Client holds stops working for good, so the Stylist is asked first, as before a delete.
    if (existing != null && revokingShareId != null && existing.activeProfileShare?.id == revokingShareId) {
        ConfirmDialog(
            title = stringResource(R.string.revoke_profile_share) + "?",
            body = stringResource(R.string.revoke_profile_share_confirm_body),
            confirmText = stringResource(R.string.revoke_profile_share_confirm),
            danger = true,
            onConfirm = {
                revokingShareId = null
                viewModel.revokeProfileShare(existing.id)
            },
            onDismiss = { revokingShareId = null },
        )
    }

    shareImageSvg?.let { svg ->
        ShareImageDialog(svg = svg, onDismiss = { shareImageSvg = null })
    }
}

/** Who recommended the Client: the field shows their name, opens the Client list to choose, and clears. */
@Composable
private fun ReferrerField(referrer: ClientProfile?, onPick: () -> Unit, onClear: () -> Unit) {
    HcmCard(onClick = onPick, contentPadding = PaddingValues(start = 18.dp, end = 6.dp, top = 12.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.client_referred_by_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (referrer != null) {
                    Text(referrer.name, style = MaterialTheme.typography.bodyLarge)
                } else {
                    Text(stringResource(R.string.client_referred_by_none), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (referrer != null) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.client_referred_by_clear))
                }
            }
        }
    }
}

/**
 * The Client's Profile Share: what it shows, its link, the Share Image and Revoke. The three
 * choices are made before a Profile Share is created and can be changed on the one that exists.
 * While a change the Stylist waits for is running ([busy]) nothing here starts another one. A
 * choice of the Profile Share that exists is not such a change: its switch moves with the tap.
 */
@Composable
private fun ProfileShareCard(
    client: ClientProfile,
    shareUrl: String?,
    busy: Boolean,
    showsNoName: Boolean,
    onCreate: (Set<ShareChoice>) -> Unit,
    onChoice: (ShareChoice, Boolean) -> Unit,
    onRevoke: () -> Unit,
    onGenerateImage: (Language) -> Unit,
    onCopied: () -> Unit,
    onOpenFailed: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val share = client.activeProfileShare
    // Share Image language is chosen per generation. It starts from the Client's language, and again when another one is saved for the Client.
    var imageLanguage by rememberSaveable(client.id, client.language) { mutableStateOf(client.language) }

    HcmCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.profile_share_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.profile_share_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (showsNoName) {
                    Text(stringResource(R.string.profile_share_no_name), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (share == null || shareUrl == null) {
                // What the new Profile Share will show. It starts from the defaults whenever there is no Profile Share.
                var included by rememberSaveable(client.id, stateSaver = ShareChoicesSaver) { mutableStateOf(ShareChoice.defaults) }

                ShareChoices(included, enabled = !busy, onChange = { choice, on -> included = if (on) included + choice else included - choice })
                SecondaryButton(
                    stringResource(R.string.create_profile_share),
                    onClick = { onCreate(included) },
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Rounded.Link,
                    loading = busy,
                )
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
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Each switch moves with the tap and is saved behind it. It goes back when its change could not be saved.
                    ShareChoices(share.included, enabled = !busy, onChange = onChoice)
                    Text(stringResource(R.string.profile_share_choices_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Hairline()
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(stringResource(R.string.share_image_language_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SegmentedControl(Language.entries, imageLanguage, { imageLanguage = it }, label = { stringResource(it.label) })
                }
                // The server puts the photos into the picture, which can take a few seconds. It shows what the link shows:
                // a choice that is still being saved is answered first (the view model sends the request after it).
                SecondaryButton(
                    stringResource(R.string.generate_share_image),
                    onClick = { onGenerateImage(imageLanguage) },
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Rounded.Image,
                    loading = busy,
                )
                TextAction(
                    stringResource(R.string.revoke_profile_share),
                    onClick = onRevoke,
                    icon = Icons.Rounded.LinkOff,
                    danger = true,
                    enabled = !busy,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}

/** What a Profile Share shows of the last completed Appointment beyond its Services: one switch for each choice. */
@Composable
private fun ShareChoices(included: Set<ShareChoice>, enabled: Boolean, onChange: (ShareChoice, Boolean) -> Unit) {
    Column {
        Text(stringResource(R.string.profile_share_choices_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ShareChoice.entries.forEach { choice ->
            SwitchRow(stringResource(choice.label), checked = choice in included, onCheckedChange = { onChange(choice, it) }, enabled = enabled)
        }
    }
}

/**
 * A Client's Appointment history, read through the cache for as long as the screen is open, also
 * while the app is not in front (see `collectWhileOpen`). A history that was shown before is there
 * from the first frame and refreshed behind it; one that is on screen stays there when loading it
 * again fails.
 */
@Composable
internal fun rememberClientHistory(viewModel: AppViewModel, clientId: String): State<Read<List<ClientHistoryEntry>>> {
    val history = remember(viewModel, clientId) { viewModel.clientHistory(clientId) }.collectWhileOpen(
        initialValue = remember(viewModel, clientId) { viewModel.shownClientHistory(clientId) },
    )

    // Asked for each time the screen comes into view, from an Appointment opened there or from the background:
    // a history that cannot be loaded says so as a notice too.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.loadClientHistory(clientId) }

    return history
}

/**
 * The Client's Appointment history ([rememberClientHistory]), newest first: each Appointment with
 * this Client's part of it, the Services with their prices, notes and Color Formulas, and the
 * Client's photos.
 */
@Composable
internal fun ClientHistoryCard(history: Read<List<ClientHistoryEntry>>, onRetry: () -> Unit, onOpenAppointment: (String) -> Unit) {
    HcmCard(contentPadding = PaddingValues(vertical = 18.dp)) {
        Text(stringResource(R.string.appointment_history_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 18.dp))

        val entries = history.data.orEmpty()

        when (val state = history.state) {
            is LoadState.Failed -> InlineLoadFailed(
                state.message,
                onRetry = onRetry,
                Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp),
            )
            LoadState.Loaded -> if (entries.isEmpty()) {
                InlineEmpty(stringResource(R.string.appointment_history_empty), Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp))
            } else {
                entries.forEachIndexed { index, entry ->
                    if (index > 0) {
                        Hairline(Modifier.padding(horizontal = 18.dp))
                    }
                    ClientHistoryRow(entry, onOpen = { onOpenAppointment(entry.id) })
                }
            }
            else -> LoadingRow(Modifier.heightIn(max = 96.dp))
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
            // The Tip is for the whole visit: of a group Appointment, it says so.
            entry.tipCents?.let { tip ->
                Row(Modifier.padding(horizontal = HistoryInset), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(if (entry.participantClientIds.size > 1) R.string.history_tip_whole_visit else R.string.history_tip),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(Money.format(tip), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                // As on the Appointment's own screen: only a web address is loaded, and a picture that does not load is marked.
                if (inView && photo.hasPicture) {
                    StoredPicture(photo.tilePicture, contentDescription = null, ContentScale.Crop, Modifier.fillMaxSize())
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

/**
 * Preview of a generated Share Image with a button to send it through the system share sheet.
 * The picture is handed to the image loader as the server made it: its texts and photos are
 * drawn from the markup, and nothing here reads or rewrites it.
 */
@Composable
private fun ShareImageDialog(svg: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val svgBytes = remember(svg) { svg.toByteArray() }
    var sharing by remember { mutableStateOf(false) }
    // The picture could not be drawn, here or for the file to send. Said in the dialog: a notice would lie under it.
    var failed by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.share_image_title), style = MaterialTheme.typography.headlineSmall)
                // As wide as the dialog and as tall as the picture then is, which can be many screens: it scrolls in its place.
                AsyncImage(
                    model = ImageRequest.Builder(context).data(svgBytes).build(),
                    contentDescription = stringResource(R.string.share_image_title),
                    contentScale = ContentScale.FillWidth,
                    onError = { failed = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .verticalScroll(rememberScrollState()),
                )
                if (failed) {
                    Text(stringResource(R.string.share_image_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                PrimaryButton(
                    stringResource(R.string.share_action),
                    onClick = {
                        scope.launch {
                            sharing = true
                            failed = !shareSvgAsPng(context, svg, svgBytes)
                            sharing = false
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

/**
 * Draws the picture at the size it states for itself (see [ShareImageSize]) and hands the PNG to
 * other apps through the FileProvider. False when the picture could not be drawn or handed over.
 */
private suspend fun shareSvgAsPng(context: Context, svg: String, svgBytes: ByteArray): Boolean =
    try {
        val drawn = ShareImageSize.of(svg)?.let { ShareImageSize.drawn(it) }
        val request = ImageRequest.Builder(context)
            .data(svgBytes)
            .allowHardware(false)
            .apply {
                if (drawn != null) {
                    size(drawn.width, drawn.height)
                    // The image loader's own limit is 4096 px a side, and a picture with photos or long texts is taller.
                    maxBitmapSize(Size(drawn.width, drawn.height))
                } else {
                    size(Size.ORIGINAL)
                }
            }
            .build()
        val bitmap = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
            ?: return false
        val file = withContext(Dispatchers.IO) {
            SharedPictures(File(context.cacheDir, SharedPictures.DIRECTORY)).shareImage().also { target ->
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
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }
