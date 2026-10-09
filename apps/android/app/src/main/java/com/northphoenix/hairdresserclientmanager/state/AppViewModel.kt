package com.northphoenix.hairdresserclientmanager.state

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.auth.AuthGateway
import com.northphoenix.hairdresserclientmanager.auth.AuthState
import com.northphoenix.hairdresserclientmanager.core.AppContainer
import com.northphoenix.hairdresserclientmanager.data.ApiException
import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.data.ClientHistoryEntry
import com.northphoenix.hairdresserclientmanager.data.ClientProfile
import com.northphoenix.hairdresserclientmanager.data.CreateAppointmentResult
import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.data.LocationType
import com.northphoenix.hairdresserclientmanager.data.PhotoCategory
import com.northphoenix.hairdresserclientmanager.data.PhotoPreparer
import com.northphoenix.hairdresserclientmanager.data.ServiceMenuItem
import com.northphoenix.hairdresserclientmanager.data.Stylist
import com.northphoenix.hairdresserclientmanager.data.UploadThingClient
import com.northphoenix.hairdresserclientmanager.domain.TimeFormat
import com.northphoenix.hairdresserclientmanager.domain.zoneOrSystem
import com.northphoenix.hairdresserclientmanager.i18n.deviceLanguage
import com.northphoenix.hairdresserclientmanager.i18n.AppLanguage
import com.northphoenix.hairdresserclientmanager.i18n.messageRes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A transient message for the snackbar, resolved to text by the UI in the current app language. */
data class Notice(@StringRes val message: Int, val isError: Boolean = false)

sealed interface BootstrapState {
    data object Loading : BootstrapState

    data class Failed(@StringRes val message: Int) : BootstrapState

    data object Ready : BootstrapState
}

/**
 * Holds the signed-in Stylist's server data and runs every mutation.
 * The app is online-first: after a change we refetch rather than patch local copies.
 */
class AppViewModel(private val container: AppContainer, val auth: AuthGateway) : ViewModel() {
    private val api = container.api

    val authState: StateFlow<AuthState> = auth.state

    private val _bootstrap = MutableStateFlow<BootstrapState>(BootstrapState.Loading)
    val bootstrap: StateFlow<BootstrapState> = _bootstrap.asStateFlow()

    private val _stylist = MutableStateFlow<Stylist?>(null)
    val stylist: StateFlow<Stylist?> = _stylist.asStateFlow()

    /** Chosen on the onboarding screen, where there are no saved settings to update yet. */
    private var onboardingLanguage: Language? = null

    /** The Stylist's timezone. Appointment times and day boundaries are always in it. */
    val zone: StateFlow<ZoneId> =
        _stylist.map { zoneOrSystem(it?.timezone) }.stateIn(viewModelScope, SharingStarted.Eagerly, ZoneId.systemDefault())

    // Locale-independent: only used here for day boundaries.
    private val clock: TimeFormat get() = TimeFormat(Locale.ROOT, zone.value)

    private val _clients = MutableStateFlow<List<ClientProfile>>(emptyList())
    val clients: StateFlow<List<ClientProfile>> = _clients.asStateFlow()

    private val _serviceMenu = MutableStateFlow<List<ServiceMenuItem>>(emptyList())
    val serviceMenu: StateFlow<List<ServiceMenuItem>> = _serviceMenu.asStateFlow()

    private val _home = MutableStateFlow<List<Appointment>>(emptyList())
    val home: StateFlow<List<Appointment>> = _home.asStateFlow()

    private val _calendarDate = MutableStateFlow(LocalDate.now())
    val calendarDate: StateFlow<LocalDate> = _calendarDate.asStateFlow()

    private val _calendar = MutableStateFlow<List<Appointment>>(emptyList())
    val calendar: StateFlow<List<Appointment>> = _calendar.asStateFlow()

    private val _calendarLoading = MutableStateFlow(false)
    val calendarLoading: StateFlow<Boolean> = _calendarLoading.asStateFlow()

    private val _completedSources = MutableStateFlow<List<Appointment>>(emptyList())
    val completedSources: StateFlow<List<Appointment>> = _completedSources.asStateFlow()

    /** Every Appointment seen so far, by id, so a detail screen can render whichever list it came from. */
    private val _appointments = MutableStateFlow<Map<String, Appointment>>(emptyMap())
    val appointments: StateFlow<Map<String, Appointment>> = _appointments.asStateFlow()

    private val _loaded = MutableStateFlow(false)

    /** False until the first full load after sign-in finishes, so lists can show a spinner instead of "empty". */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val pendingCount = MutableStateFlow(0)

    /** True while any mutation is in flight. */
    val busy: StateFlow<Boolean> =
        pendingCount.map { it > 0 }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _uploadingPhoto = MutableStateFlow(false)
    val uploadingPhoto: StateFlow<Boolean> = _uploadingPhoto.asStateFlow()

    private val noticeChannel = Channel<Notice>(Channel.BUFFERED)
    val notices = noticeChannel.receiveAsFlow()

    private var calendarJob: Job? = null

    init {
        viewModelScope.launch {
            authState.distinctUntilChangedBy { (it as? AuthState.SignedIn)?.userId }.collect { state ->
                clearStylistData()
                if (state is AuthState.SignedIn) {
                    loadStylist()
                }
            }
        }
    }

    fun profileShareUrl(token: String): String = container.config.profileShareUrl(token)

    fun notify(@StringRes message: Int, isError: Boolean = false) {
        noticeChannel.trySend(Notice(message, isError))
    }

    private fun notifyFailure(error: ApiException) = notify(error.messageRes(), isError = true)

    private fun clearStylistData() {
        calendarJob?.cancel()
        _stylist.value = null
        onboardingLanguage = null
        _bootstrap.value = BootstrapState.Loading
        _clients.value = emptyList()
        _serviceMenu.value = emptyList()
        _home.value = emptyList()
        _calendar.value = emptyList()
        _completedSources.value = emptyList()
        _appointments.value = emptyMap()
        _loaded.value = false
    }

    // --- loading ---

    /** Resolves (or lazily creates) the Stylist for the signed-in user, then loads their data. */
    fun loadStylist() {
        viewModelScope.launch {
            _bootstrap.value = BootstrapState.Loading

            try {
                val stylist = api.bootstrap(deviceLanguage(), ZoneId.systemDefault().id)
                _stylist.value = stylist
                // An onboarded Stylist's saved language wins over whatever this device last showed.
                if (stylist.onboardingComplete) {
                    AppLanguage.apply(stylist.language)
                }
                _calendarDate.value = clock.today()
                _bootstrap.value = BootstrapState.Ready

                if (stylist.onboardingComplete) {
                    refreshAll()
                }
            } catch (error: ApiException) {
                _bootstrap.value = BootstrapState.Failed(error.messageRes())
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            try {
                refreshAll()
            } finally {
                _refreshing.value = false
            }
        }
    }

    private suspend fun refreshAll() {
        try {
            coroutineScope {
                listOf(
                    async { _clients.value = api.listClients() },
                    async { _serviceMenu.value = api.listServiceMenu() },
                    async { loadAppointmentLists() },
                ).awaitAll()
            }
            _loaded.value = true
        } catch (error: ApiException) {
            notifyFailure(error)
        }
    }

    private suspend fun loadAppointmentLists() =
        coroutineScope {
            val format = clock
            val (todayStart, todayEnd) = format.dayRange(format.today())
            val (dayStart, dayEnd) = format.dayRange(_calendarDate.value)
            val home = async { api.homeAppointments(todayStart, todayEnd) }
            val calendar = async { api.listAppointments(dayStart, dayEnd) }
            val completed = async { api.completedSources() }

            _home.value = home.await()
            _calendar.value = calendar.await()
            _completedSources.value = completed.await()
            // The completed-sources projection omits photos and phone numbers, so it is not cached for detail screens.
            remember(_home.value + _calendar.value)
        }

    private fun remember(appointments: List<Appointment>) {
        _appointments.update { cache -> cache + appointments.associateBy { it.id } }
    }

    /**
     * Refetches one Appointment. The API has no get-by-id, so this asks for the
     * one-millisecond range that starts at the Appointment's own start time.
     */
    private suspend fun reloadAppointment(id: String) {
        val startsAt = _appointments.value[id]?.startsAt ?: return
        val start = Instant.parse(startsAt)
        val match = api.listAppointments(start, start.plusMillis(1)).firstOrNull { it.id == id }

        _appointments.update { cache -> if (match != null) cache + (id to match) else cache - id }
    }

    /** Opens an Appointment that may not be in any loaded list, such as one from a Client's history. */
    fun ensureAppointment(id: String, startsAt: String) {
        if (_appointments.value.containsKey(id)) {
            return
        }

        viewModelScope.launch {
            try {
                val start = Instant.parse(startsAt)
                api.listAppointments(start, start.plusMillis(1)).firstOrNull { it.id == id }?.let { remember(listOf(it)) }
            } catch (error: ApiException) {
                notifyFailure(error)
            }
        }
    }

    fun setCalendarDate(date: LocalDate) {
        if (date == _calendarDate.value) {
            return
        }

        _calendarDate.value = date
        _calendar.value = emptyList()
        calendarJob?.cancel()
        calendarJob = viewModelScope.launch {
            _calendarLoading.value = true
            try {
                val (start, end) = clock.dayRange(date)
                val appointments = api.listAppointments(start, end)
                _calendar.value = appointments
                remember(appointments)
            } catch (error: ApiException) {
                notifyFailure(error)
            } finally {
                _calendarLoading.value = false
            }
        }
    }

    // --- mutation plumbing ---

    private fun act(@StringRes successNotice: Int? = null, onSuccess: () -> Unit = {}, block: suspend () -> Unit): Job =
        viewModelScope.launch {
            pendingCount.update { it + 1 }
            try {
                block()
                successNotice?.let { notify(it) }
                onSuccess()
            } catch (error: CancellationException) {
                throw error
            } catch (error: ApiException) {
                notifyFailure(error)
            } finally {
                pendingCount.update { it - 1 }
            }
        }

    private fun actOnAppointment(id: String, @StringRes successNotice: Int? = null, onSuccess: () -> Unit = {}, change: suspend () -> Unit) =
        act(successNotice, onSuccess) {
            change()
            coroutineScope {
                listOf(async { loadAppointmentLists() }, async { reloadAppointment(id) }).awaitAll()
            }
        }

    // --- settings ---

    /**
     * Switches the app language straight away. For an onboarded Stylist it is also saved,
     * so the choice follows them to other devices; during onboarding it is saved with the rest.
     */
    fun setLanguage(language: Language) {
        AppLanguage.apply(language)

        val current = _stylist.value

        if (current == null || !current.onboardingComplete) {
            onboardingLanguage = language
        } else if (current.language != language) {
            act { _stylist.value = api.saveSettings(language, current.timezone, current.salonAddress) }
        }
    }

    fun saveSettings(timezone: String, salonAddress: String) {
        val current = _stylist.value ?: return
        val trimmedTimezone = timezone.trim()

        if (trimmedTimezone.isEmpty()) {
            notify(R.string.timezone_required, isError = true)
            return
        }

        val wasOnboarded = current.onboardingComplete

        act {
            val language = if (wasOnboarded) current.language else onboardingLanguage ?: current.language
            val saved = api.saveSettings(language, trimmedTimezone, salonAddress.trim())
            _stylist.value = saved
            AppLanguage.apply(saved.language)
            if (!wasOnboarded) {
                _calendarDate.value = clock.today()
            }
            refreshAll()
            if (wasOnboarded) {
                notify(R.string.settings_saved)
            }
        }
    }

    fun signOut() {
        viewModelScope.launch { auth.signOut() }
    }

    // --- clients ---

    fun saveClient(
        id: String?,
        name: String,
        language: Language,
        phone: String,
        email: String,
        address: String,
        note: String,
        onSuccess: (ClientProfile) -> Unit,
    ) {
        act {
            val saved = api.saveClient(id, name.trim(), language, phone.trim(), email.trim(), address.trim(), note.trim())
            _clients.value = api.listClients()
            notify(R.string.client_saved)
            onSuccess(saved)
            // Names and phone numbers shown on Appointments come from the Client.
            if (id != null) {
                loadAppointmentLists()
            }
        }
    }

    fun deleteClient(id: String, onSuccess: () -> Unit) =
        act(R.string.client_deleted, onSuccess) {
            api.deleteClient(id)
            _appointments.value = emptyMap()
            refreshAll()
        }

    fun createProfileShare(clientId: String) =
        act {
            api.createProfileShare(clientId)
            _clients.value = api.listClients()
        }

    fun revokeProfileShare(clientId: String) =
        act {
            api.revokeProfileShare(clientId)
            _clients.value = api.listClients()
        }

    fun buildShareImage(clientId: String, language: Language, onReady: (String) -> Unit) =
        act { onReady(api.buildProfileShareImage(clientId, language).svg) }

    suspend fun clientHistory(clientId: String): List<ClientHistoryEntry>? =
        try {
            api.clientAppointmentHistory(clientId)
        } catch (error: ApiException) {
            notifyFailure(error)
            null
        }

    // --- service menu ---

    fun saveServiceMenuItem(id: String?, name: String, defaultPriceCents: Int, onSuccess: () -> Unit) =
        act(R.string.service_menu_item_saved, onSuccess) {
            api.saveServiceMenuItem(id, name.trim(), defaultPriceCents)
            _serviceMenu.value = api.listServiceMenu()
        }

    fun deleteServiceMenuItem(id: String, onSuccess: () -> Unit) =
        act(R.string.service_menu_item_deleted, onSuccess) {
            api.deleteServiceMenuItem(id)
            _serviceMenu.value = api.listServiceMenu()
        }

    // --- appointments ---

    fun createAppointment(
        primaryClientId: String?,
        additionalClientIds: List<String>,
        inlineClientName: String?,
        startsAt: Instant,
        endsAt: Instant?,
        locationType: LocationType,
        customLocationAddress: String?,
        onSuccess: (CreateAppointmentResult) -> Unit,
    ) = act {
        val result = api.createAppointment(
            primaryClientId = primaryClientId,
            additionalClientIds = additionalClientIds,
            inlineClientName = inlineClientName?.trim(),
            startsAt = startsAt,
            endsAt = endsAt,
            locationType = locationType,
            customLocationAddress = customLocationAddress?.trim(),
        )
        remember(listOf(result.appointment))
        notify(if (result.conflicts.isEmpty()) R.string.appointment_saved else R.string.appointment_conflict_warning)
        onSuccess(result)
        coroutineScope {
            listOf(
                async { loadAppointmentLists() },
                // An inline Client is created on the server as part of this call.
                async { if (primaryClientId == null) _clients.value = api.listClients() },
            ).awaitAll()
        }
    }

    fun setAppointmentStatus(id: String, status: AppointmentStatus) =
        actOnAppointment(id) { api.updateAppointmentStatus(id, status) }

    fun setAppointmentPrimary(id: String, clientId: String) =
        actOnAppointment(id) { api.updateAppointmentPrimary(id, clientId) }

    fun saveAppointmentNote(id: String, note: String) =
        actOnAppointment(id, R.string.appointment_note_saved) { api.updateAppointmentNote(id, note.trim()) }

    fun setAppointmentFinalTotal(id: String, overrideCents: Int?) =
        actOnAppointment(id) { api.updateAppointmentFinalTotal(id, overrideCents) }

    fun deleteAppointment(id: String, onSuccess: () -> Unit) =
        act(onSuccess = onSuccess) {
            api.deleteAppointment(id)
            _appointments.update { it - id }
            loadAppointmentLists()
        }

    fun addParticipant(appointmentId: String, clientId: String) =
        actOnAppointment(appointmentId) { api.addParticipant(appointmentId, clientId) }

    fun removeParticipant(appointmentId: String, clientId: String) =
        actOnAppointment(appointmentId) { api.removeParticipant(appointmentId, clientId) }

    fun addService(
        appointmentId: String,
        clientId: String,
        menuItemId: String?,
        name: String?,
        priceCents: Int?,
        note: String?,
        onSuccess: () -> Unit,
    ) = actOnAppointment(appointmentId, R.string.appointment_service_saved, onSuccess) {
        api.addService(appointmentId, clientId, menuItemId, name?.trim(), priceCents, note?.trim())
    }

    fun deleteService(appointmentId: String, serviceId: String) =
        actOnAppointment(appointmentId) { api.deleteService(serviceId) }

    fun copyServices(targetAppointmentId: String, sourceAppointmentId: String?, onSuccess: () -> Unit) =
        actOnAppointment(targetAppointmentId, onSuccess = onSuccess) { api.copyServices(targetAppointmentId, sourceAppointmentId) }

    fun saveColorFormula(
        appointmentId: String,
        serviceId: String,
        formulaId: String?,
        formula: String,
        placement: String,
        onSuccess: () -> Unit,
    ) = actOnAppointment(appointmentId, onSuccess = onSuccess) {
        if (formulaId == null) {
            api.addColorFormula(serviceId, formula.trim(), placement.trim())
        } else {
            api.updateColorFormula(formulaId, formula.trim(), placement.trim())
        }
    }

    fun deleteColorFormula(appointmentId: String, formulaId: String, onSuccess: () -> Unit = {}) =
        actOnAppointment(appointmentId, onSuccess = onSuccess) { api.deleteColorFormula(formulaId) }

    // --- photos ---

    /**
     * Uploads through UploadThing first and only then records the photo, so the
     * database never points at a file that failed to store.
     * Several [sources] upload one after another and stop at the first failure.
     * [replacingPhotoId] removes a failed attempt once its retry succeeds.
     */
    fun addPhotos(appointmentId: String, clientId: String, category: PhotoCategory, sources: List<Uri>, replacingPhotoId: String? = null) {
        if (_uploadingPhoto.value || sources.isEmpty()) {
            return
        }

        viewModelScope.launch {
            _uploadingPhoto.value = true
            var stored = 0
            try {
                for (source in sources) {
                    val photo = PhotoPreparer.prepare(container.appContext, source)

                    if (photo.bytes.size > UploadThingClient.MAX_PHOTO_BYTES) {
                        notify(R.string.appointment_photo_too_large, isError = true)
                        break
                    }

                    val uploaded = container.uploads.upload(
                        slug = UploadThingClient.APPOINTMENT_PHOTO_SLUG,
                        input = buildJsonObject {
                            put("appointmentId", appointmentId)
                            put("clientId", clientId)
                            put("category", category.wire)
                        },
                        fileName = photo.fileName,
                        mimeType = photo.mimeType,
                        bytes = photo.bytes,
                    )
                    val serverData = UploadThingClient.decodeUploadedPhoto(uploaded.serverData)
                    val url = serverData?.url ?: uploaded.ufsUrl

                    api.addStoredPhoto(
                        appointmentId = serverData?.appointmentId ?: appointmentId,
                        clientId = serverData?.clientId ?: clientId,
                        category = serverData?.category ?: category,
                        fileKey = serverData?.fileKey ?: uploaded.key,
                        url = url,
                        thumbnailUrl = serverData?.thumbnailUrl ?: url,
                    )
                    if (stored == 0) {
                        replacingPhotoId?.let { api.deletePhoto(it) }
                    }
                    stored++
                }
            } catch (error: ApiException) {
                notifyFailure(error)
            }

            try {
                if (stored > 0) {
                    coroutineScope {
                        listOf(async { loadAppointmentLists() }, async { reloadAppointment(appointmentId) }).awaitAll()
                    }
                }
            } catch (error: ApiException) {
                notifyFailure(error)
            } finally {
                _uploadingPhoto.value = false
            }
        }
    }

    fun setPhotoCategory(appointmentId: String, photoId: String, category: PhotoCategory) =
        actOnAppointment(appointmentId) { api.updatePhotoCategory(photoId, category) }

    fun deletePhoto(appointmentId: String, photoId: String) =
        actOnAppointment(appointmentId) { api.deletePhoto(photoId) }

    companion object {
        fun factory(container: AppContainer, auth: AuthGateway): ViewModelProvider.Factory =
            viewModelFactory { initializer { AppViewModel(container, auth) } }
    }
}
