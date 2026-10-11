package com.northphoenix.hairdresserclientmanager.state

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.auth.AuthGateway
import com.northphoenix.hairdresserclientmanager.auth.AuthState
import com.northphoenix.hairdresserclientmanager.auth.SessionCheck
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
import com.northphoenix.hairdresserclientmanager.data.ShareImage
import com.northphoenix.hairdresserclientmanager.data.Stylist
import com.northphoenix.hairdresserclientmanager.data.UploadThingClient
import com.northphoenix.hairdresserclientmanager.data.toApiException
import com.northphoenix.hairdresserclientmanager.domain.TimeFormat
import com.northphoenix.hairdresserclientmanager.domain.zoneOrSystem
import com.northphoenix.hairdresserclientmanager.i18n.deviceLanguage
import com.northphoenix.hairdresserclientmanager.i18n.AppLanguage
import com.northphoenix.hairdresserclientmanager.i18n.messageRes
import com.northphoenix.hairdresserclientmanager.i18n.refusedSignInMessage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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
 * The app is online-first: after a change we refetch. A local copy is only touched with what the
 * server has just confirmed, so the screen is right even when that refetch fails.
 */
class AppViewModel(private val container: AppContainer, val auth: AuthGateway, savedState: SavedStateHandle) : ViewModel() {
    private val api = container.api

    val authState: StateFlow<AuthState> = auth.state

    private val sessionWatch = SessionWatch()

    /** True while signed out because the session ended on its own: the sign-in screen says why it appeared. */
    val sessionEnded: StateFlow<Boolean> = sessionWatch.ended

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

    // Each list comes with a load state. A screen says that a list is empty only when its state is Loaded.
    private val _clients = MutableStateFlow<List<ClientProfile>>(emptyList())
    val clients: StateFlow<List<ClientProfile>> = _clients.asStateFlow()
    private val clientsLoading = LoadTracker()
    val clientsLoad: StateFlow<LoadState> = clientsLoading.state

    private val _serviceMenu = MutableStateFlow<List<ServiceMenuItem>>(emptyList())
    val serviceMenu: StateFlow<List<ServiceMenuItem>> = _serviceMenu.asStateFlow()
    private val serviceMenuLoading = LoadTracker()
    val serviceMenuLoad: StateFlow<LoadState> = serviceMenuLoading.state

    private val _home = MutableStateFlow<List<Appointment>>(emptyList())
    val home: StateFlow<List<Appointment>> = _home.asStateFlow()
    private val homeLoading = LoadTracker()
    val homeLoad: StateFlow<LoadState> = homeLoading.state

    private val _calendarDate = MutableStateFlow(LocalDate.now())
    val calendarDate: StateFlow<LocalDate> = _calendarDate.asStateFlow()

    /** A day other than today that the Stylist has selected, so that it is still selected after Android has killed the app. */
    private val savedCalendarDay = SavedCalendarDay(savedState)

    private val _calendar = MutableStateFlow<List<Appointment>>(emptyList())
    val calendar: StateFlow<List<Appointment>> = _calendar.asStateFlow()

    /** Of the day in [calendarDate]. Reset whenever that day changes, so it never speaks for another day. */
    private val calendarLoading = LoadTracker()
    val calendarLoad: StateFlow<LoadState> = calendarLoading.state

    private val _completedSources = MutableStateFlow<List<Appointment>>(emptyList())
    val completedSources: StateFlow<List<Appointment>> = _completedSources.asStateFlow()
    private val completedSourcesLoading = LoadTracker()
    val completedSourcesLoad: StateFlow<LoadState> = completedSourcesLoading.state

    private val loadedAppointments = LoadedAppointments()

    /** Every Appointment seen so far, by id, so a detail screen can render whichever list it came from. */
    val appointments: StateFlow<Map<String, Appointment>> = loadedAppointments.loaded

    /** Why an Appointment that a screen asked for is not in [appointments]: loading, failed to load or gone. */
    val absentAppointments: StateFlow<Map<String, LoadState>> = loadedAppointments.absent

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val actions = ActionRunner(
        viewModelScope,
        ::report,
        reloadAll = ::loadAll,
        onStale = { notify(R.string.error_screen_not_refreshed, isError = true) },
    )

    /** True while any mutation is in flight. */
    val busy: StateFlow<Boolean> = actions.busy

    // Values the Stylist can change again while they are still being saved, each kept in the order
    // of the taps (see saveLatest). Keyed by the Appointment, the photo's category by the photo.
    private val statusChanges = LatestWins<String, AppointmentStatus>()
    private val primaryChanges = LatestWins<String, String>()
    private val noteChanges = LatestWins<String, String>()
    private val finalTotalChanges = LatestWins<String, Int?>()
    private val photoCategoryChanges = LatestWins<String, PhotoCategory>()

    private val _uploadingPhoto = MutableStateFlow(false)
    val uploadingPhoto: StateFlow<Boolean> = _uploadingPhoto.asStateFlow()

    private val noticeChannel = Channel<Notice>(Channel.BUFFERED)
    val notices = noticeChannel.receiveAsFlow()

    private var calendarJob: Job? = null

    init {
        viewModelScope.launch { authState.collect { sessionWatch.onState(it) } }
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

    /**
     * Shows a failure. When the API refused the sign-in, the sign-in provider is asked first: an
     * ended session takes the Stylist to sign-in (see [sessionEnded]) instead of showing a notice,
     * and a provider that cannot be reached is named as the cause.
     */
    private fun report(error: ApiException) {
        if (!error.isUnauthorized) {
            notify(error.messageRes(), isError = true)
            return
        }

        viewModelScope.launch { checkSession().refusedSignInMessage()?.let { notify(it, isError = true) } }
    }

    private suspend fun checkSession(): SessionCheck =
        try {
            auth.checkSession()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            SessionCheck.ALIVE
        }

    /** Runs [block] and reports whatever goes wrong in it, so no failure can end a view-model coroutine and the app with it. */
    private suspend fun reporting(block: suspend () -> Unit) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            report(error.toApiException())
        }
    }

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
        // Resetting also drops whatever is still on its way for the previous Stylist.
        listOf(clientsLoading, serviceMenuLoading, homeLoading, calendarLoading, completedSourcesLoading).forEach(LoadTracker::reset)
        loadedAppointments.clear()
        actions.forgetOwed()
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
                // The day that was selected when Android killed the app in the background, today otherwise.
                _calendarDate.value = savedCalendarDay.take(stylist.id) ?: clock.today()
                _bootstrap.value = BootstrapState.Ready

                if (stylist.onboardingComplete) {
                    refreshAll()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val failure = error.toApiException()
                // No message for an ended session: it goes to sign-in, and the auth state change replaces this screen.
                val message = if (failure.isUnauthorized) checkSession().refusedSignInMessage() else failure.messageRes()

                message?.let { _bootstrap.value = BootstrapState.Failed(it) }
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

    /** 'Try again' under a list that failed to load. The list has a spinner of its own, so the pull-to-refresh one stays out of it. */
    fun retryLoad() {
        viewModelScope.launch { refreshAll() }
    }

    /**
     * The app is in front again, and what it shows may have been changed elsewhere meanwhile.
     * Nobody asked for this reload, so it fails quietly and what was on screen stays.
     */
    fun refreshOnReturn() {
        if (_stylist.value?.onboardingComplete == true) {
            viewModelScope.launch { actions.reloadWithRetry(::loadAll) }
        }
    }

    private suspend fun refreshAll() = reporting {
        loadAll()
        // The server answers: what could not be reloaded after an earlier change is reloaded as well.
        actions.reloadOwed()
    }

    private suspend fun loadAll() = together(::loadClients, ::loadServiceMenu, ::loadAppointmentLists)

    private suspend fun loadClients() = clientsLoading.load(api::listClients) { _clients.value = it }

    private suspend fun loadServiceMenu() = serviceMenuLoading.load(api::listServiceMenu) { _serviceMenu.value = it }

    private suspend fun loadAppointmentLists() = together(::loadHome, ::loadCalendarDay, ::loadCompletedSources)

    private suspend fun loadHome() {
        val (start, end) = clock.run { dayRange(today()) }

        homeLoading.load({ api.homeAppointments(start, end) }) { appointments ->
            _home.value = appointments
            loadedAppointments.remember(appointments)
        }
    }

    /** Loads the day in [calendarDate]. What arrives after the Stylist has moved on to another day is dropped. */
    private suspend fun loadCalendarDay() {
        val (start, end) = clock.dayRange(_calendarDate.value)

        calendarLoading.load({ api.listAppointments(start, end) }) { appointments ->
            _calendar.value = appointments
            loadedAppointments.remember(appointments)
        }
    }

    // The completed-sources projection omits photos and phone numbers, so it is not kept for detail screens.
    private suspend fun loadCompletedSources() = completedSourcesLoading.load(api::completedSources) { _completedSources.value = it }

    /** Applies a change the server has just accepted to the loaded copies of an Appointment. */
    private fun updateLoaded(id: String, change: (Appointment) -> Appointment) {
        val changed = { appointments: List<Appointment> -> appointments.map { if (it.id == id) change(it) else it } }

        _home.update(changed)
        _calendar.update(changed)
        loadedAppointments.update(id, change)
    }

    /** Drops an Appointment the server has just deleted from everything loaded. */
    private fun forgetAppointment(id: String) {
        val without = { appointments: List<Appointment> -> appointments.filterNot { it.id == id } }

        _home.update(without)
        _calendar.update(without)
        _completedSources.update(without)
        loadedAppointments.forget(id)
    }

    /**
     * Fetches one Appointment again. The API has no get-by-id, so this asks for the one-millisecond
     * range that starts at the Appointment's own start time: the one of the loaded copy, or
     * [startsAt] for an Appointment that is not loaded. An Appointment that is not found there
     * counts as gone, so a change of the start time has to reach the loaded copy before a reload.
     */
    private suspend fun reloadAppointment(id: String, startsAt: String = "") =
        loadedAppointments.reload(id) {
            val start = (loadedAppointments[id]?.startsAt ?: startsAt).takeIf { it.isNotEmpty() }?.let(Instant::parse)

            start?.let { api.listAppointments(it, it.plusMillis(1)).firstOrNull { appointment -> appointment.id == id } }
        }

    private suspend fun reloadAppointmentAndLists(id: String) = together(::loadAppointmentLists, { reloadAppointment(id) })

    /**
     * Called by the Appointment screen each time it comes into view, and by its 'Try again'.
     * A loaded copy is shown at once and refreshed behind it; a refresh nobody asked for fails quietly.
     * Without a copy the screen shows how the load went (see [absentAppointments]).
     */
    fun loadAppointment(id: String, startsAt: String) {
        viewModelScope.launch {
            try {
                reloadAppointment(id, startsAt)
                actions.reloadOwed()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (loadedAppointments[id] == null) {
                    report(error.toApiException())
                }
            }
        }
    }

    /** Shows [date] in the Calendar. Choosing the day that is already shown loads it again, which is also the retry after a failed load. */
    fun setCalendarDate(date: LocalDate) {
        if (date != _calendarDate.value) {
            _calendarDate.value = date
            _calendar.value = emptyList()
            calendarLoading.reset()
        }
        _stylist.value?.let { savedCalendarDay.keep(it.id, date.takeIf { day -> day != clock.today() }) }

        calendarJob?.cancel()
        calendarJob = viewModelScope.launch { reporting { loadCalendarDay() } }
    }

    // --- mutation plumbing ---

    /**
     * Runs one change by the rules of [ActionRunner]. [key] names the action and what it acts on;
     * a second call with the same key while the first is running does nothing.
     * [change] is only the request that makes the change, plus what is certain once it has succeeded.
     * Whatever refreshes the screen afterwards goes in [reload], so that a refresh that fails is
     * never taken for a change that failed.
     */
    private fun <T> act(
        key: String?,
        @StringRes successNotice: Int? = null,
        onSuccess: (T) -> Unit = {},
        reload: suspend () -> Unit = {},
        change: suspend () -> T,
    ) = actions.run(key, change, reload) { result ->
        successNotice?.let { notify(it) }
        onSuccess(result)
    }

    private fun actOnAppointment(
        id: String,
        key: String?,
        @StringRes successNotice: Int? = null,
        onSuccess: () -> Unit = {},
        change: suspend () -> Unit,
    ) = act(key, successNotice, onSuccess = { onSuccess() }, reload = { reloadAppointmentAndLists(id) }) {
        change()
        loadedAppointments.changed(id)
    }

    /**
     * Saves a value of an Appointment that the Stylist can change again while it is still being
     * saved, such as a status chip. Requests for one [target] go out one at a time, and the value
     * offered last is the one saved last (see [LatestWins]); a key would drop a tap that goes back
     * to a value whose request is still running. [saved] is the value the loaded copy has.
     * [save] makes the request and, when the result is known exactly, puts it into the loaded
     * copy, so that [saved] is right for the next tap even before the reload.
     */
    private fun <V> saveLatest(
        changes: LatestWins<String, V>,
        appointmentId: String,
        target: String,
        value: V,
        saved: V?,
        @StringRes successNotice: Int? = null,
        save: suspend (V) -> Unit,
    ) {
        if (!changes.offer(target, value, saved)) {
            return
        }

        // No key: the values are ordered here, and the next change may start while this one is still reloading.
        actOnAppointment(appointmentId, key = null, successNotice) { changes.send(target, save) }
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
            act("setLanguage:$language") { _stylist.value = api.saveSettings(language, current.timezone, current.salonAddress) }
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

        act(
            key = "saveSettings",
            successNotice = if (wasOnboarded) R.string.settings_saved else null,
            reload = { loadAll() },
        ) {
            val language = if (wasOnboarded) current.language else onboardingLanguage ?: current.language
            val saved = api.saveSettings(language, trimmedTimezone, salonAddress.trim())
            _stylist.value = saved
            AppLanguage.apply(saved.language)
            if (!wasOnboarded) {
                _calendarDate.value = clock.today()
            }
        }
    }

    fun signOut() {
        sessionWatch.signOutRequested()
        viewModelScope.launch { reporting { auth.signOut() } }
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
        act(
            key = "saveClient:${id ?: "new"}",
            successNotice = R.string.client_saved,
            onSuccess = onSuccess,
            reload = { together(::loadClients, { if (id != null) loadAppointmentLists() }) },
        ) {
            api.saveClient(id, name.trim(), language, phone.trim(), email.trim(), address.trim(), note.trim()).also { saved ->
                _clients.update { it.withSaved(saved) }
                // Names and phone numbers shown on Appointments come from the Client: every loaded
                // copy gets them now, on whatever day it is, so no reminder goes to the old number.
                _home.update { appointments -> appointments.map { it.withClient(saved) } }
                _calendar.update { appointments -> appointments.map { it.withClient(saved) } }
                loadedAppointments.updateAll { it.withClient(saved) }
            }
        }
    }

    fun deleteClient(id: String, onSuccess: () -> Unit) {
        // The loaded Appointments this Client was in: they have changed or gone with them, on whatever day they are.
        var affected = emptyList<Appointment>()

        act(
            key = "deleteClient:$id",
            successNotice = R.string.client_deleted,
            onSuccess = { onSuccess() },
            reload = { together(::loadAll, { affected.forEach { reloadAppointment(it.id, it.startsAt) } }) },
        ) {
            api.deleteClient(id)
            _clients.update { clients -> clients.filterNot { it.id == id } }
            affected = appointments.value.values.filter { appointment -> appointment.participants.any { it.clientId == id } }
            // An Appointment left without Clients is deleted with its last one. The reload checks each of them again.
            affected.filter { appointment -> appointment.participants.all { it.clientId == id } }.forEach { forgetAppointment(it.id) }
        }
    }

    fun createProfileShare(clientId: String) =
        act("createProfileShare:$clientId", reload = ::loadClients) { api.createProfileShare(clientId) }

    fun revokeProfileShare(clientId: String) =
        act("revokeProfileShare:$clientId", reload = ::loadClients) { api.revokeProfileShare(clientId) }

    fun buildShareImage(clientId: String, language: Language, onReady: (String) -> Unit) =
        act("buildShareImage:$clientId:$language", onSuccess = { image: ShareImage -> onReady(image.svg) }) {
            api.buildProfileShareImage(clientId, language)
        }

    /**
     * For a Client Profile whose Client is not in the list. The list may be older than the Client,
     * or the Client may have been deleted: the list is loaded again before the profile says which.
     * [LoadState.Loaded] means the Client is in [clients] now.
     */
    suspend fun findClient(id: String): LoadState =
        try {
            loadClients()
            if (_clients.value.any { it.id == id }) LoadState.Loaded else LoadState.Missing
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val failure = error.toApiException()

            report(failure)
            LoadState.Failed(failure.messageRes())
        }

    /**
     * A Client's Appointment history. A failure is thrown for the profile to show in the history's
     * place. It is also reported as a notice, unless history that is already on screen was only
     * being [refreshing]: that fails quietly.
     */
    suspend fun clientHistory(clientId: String, refreshing: Boolean): List<ClientHistoryEntry> =
        try {
            api.clientAppointmentHistory(clientId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val failure = error.toApiException()

            if (!refreshing) {
                report(failure)
            }
            throw failure
        }

    // --- service menu ---

    fun saveServiceMenuItem(id: String?, name: String, defaultPriceCents: Int, onSuccess: () -> Unit) =
        act(
            key = "saveServiceMenuItem:${id ?: "new"}",
            successNotice = R.string.service_menu_item_saved,
            onSuccess = { onSuccess() },
            reload = ::loadServiceMenu,
        ) {
            api.saveServiceMenuItem(id, name.trim(), defaultPriceCents)
        }

    fun deleteServiceMenuItem(id: String, onSuccess: () -> Unit) =
        act(
            key = "deleteServiceMenuItem:$id",
            successNotice = R.string.service_menu_item_deleted,
            onSuccess = { onSuccess() },
            reload = ::loadServiceMenu,
        ) {
            api.deleteServiceMenuItem(id)
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
    ) = act(
        key = "createAppointment",
        onSuccess = { result: CreateAppointmentResult ->
            notify(if (result.conflicts.isEmpty()) R.string.appointment_saved else R.string.appointment_conflict_warning)
            onSuccess(result)
        },
        // An inline Client is created on the server as part of this call.
        reload = { together(::loadAppointmentLists, { if (primaryClientId == null) loadClients() }) },
    ) {
        api.createAppointment(
            primaryClientId = primaryClientId,
            additionalClientIds = additionalClientIds,
            inlineClientName = inlineClientName?.trim(),
            startsAt = startsAt,
            endsAt = endsAt,
            locationType = locationType,
            customLocationAddress = customLocationAddress?.trim(),
        ).also { loadedAppointments.remember(listOf(it.appointment)) }
    }

    /** Status taps are saved one at a time in the order they were made, so the last tap is the status that stays. */
    fun setAppointmentStatus(id: String, status: AppointmentStatus) =
        saveLatest(statusChanges, id, id, status, saved = loadedAppointments[id]?.status) { next ->
            api.updateAppointmentStatus(id, next)
            updateLoaded(id) { it.copy(status = next) }
        }

    // Not put into the loaded copy: the Appointment's address can change with its primary Client, and only the reload shows to what.
    fun setAppointmentPrimary(id: String, clientId: String) =
        saveLatest(primaryChanges, id, id, clientId, saved = loadedAppointments[id]?.primaryClientId) { next ->
            api.updateAppointmentPrimary(id, next)
        }

    fun saveAppointmentNote(id: String, note: String) =
        saveLatest(noteChanges, id, id, note.trim(), saved = loadedAppointments[id]?.note, R.string.appointment_note_saved) { next ->
            api.updateAppointmentNote(id, next)
            updateLoaded(id) { it.copy(note = next) }
        }

    /** [overrideCents] null clears the override, and the final total is the Service total again. */
    fun setAppointmentFinalTotal(id: String, overrideCents: Int?) =
        saveLatest(finalTotalChanges, id, id, overrideCents, saved = loadedAppointments[id]?.finalTotalCentsOverride) { next ->
            api.updateAppointmentFinalTotal(id, next)
            updateLoaded(id) { it.copy(finalTotalCentsOverride = next, finalTotalCents = next ?: it.serviceTotalCents) }
        }

    fun deleteAppointment(id: String, onSuccess: () -> Unit) =
        actOnAppointment(id, "deleteAppointment:$id", onSuccess = onSuccess) {
            api.deleteAppointment(id)
            forgetAppointment(id)
        }

    fun addParticipant(appointmentId: String, clientId: String) =
        actOnAppointment(appointmentId, "addParticipant:$appointmentId:$clientId") { api.addParticipant(appointmentId, clientId) }

    fun removeParticipant(appointmentId: String, clientId: String) =
        actOnAppointment(appointmentId, "removeParticipant:$appointmentId:$clientId") { api.removeParticipant(appointmentId, clientId) }

    fun addService(
        appointmentId: String,
        clientId: String,
        menuItemId: String?,
        name: String?,
        priceCents: Int?,
        note: String?,
        onSuccess: () -> Unit,
    ) = actOnAppointment(appointmentId, "addService:$appointmentId", R.string.appointment_service_saved, onSuccess) {
        api.addService(appointmentId, clientId, menuItemId, name?.trim(), priceCents, note?.trim())
    }

    fun deleteService(appointmentId: String, serviceId: String) =
        actOnAppointment(appointmentId, "deleteService:$serviceId") { api.deleteService(serviceId) }

    fun copyServices(targetAppointmentId: String, sourceAppointmentId: String?, onSuccess: () -> Unit) =
        actOnAppointment(targetAppointmentId, "copyServices:$targetAppointmentId", onSuccess = onSuccess) {
            api.copyServices(targetAppointmentId, sourceAppointmentId)
        }

    fun saveColorFormula(
        appointmentId: String,
        serviceId: String,
        formulaId: String?,
        formula: String,
        placement: String,
        onSuccess: () -> Unit,
    ) = actOnAppointment(appointmentId, "saveColorFormula:${formulaId ?: serviceId}", onSuccess = onSuccess) {
        if (formulaId == null) {
            api.addColorFormula(serviceId, formula.trim(), placement.trim())
        } else {
            api.updateColorFormula(formulaId, formula.trim(), placement.trim())
        }
    }

    fun deleteColorFormula(appointmentId: String, formulaId: String, onSuccess: () -> Unit = {}) =
        actOnAppointment(appointmentId, "deleteColorFormula:$formulaId", onSuccess = onSuccess) { api.deleteColorFormula(formulaId) }

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
            var outcomeUnknown = false
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
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val failure = error.toApiException()

                // The photo may have been recorded even though the reply to that step never came.
                outcomeUnknown = failure.outcomeUnknown
                report(failure)
            }

            try {
                if (stored > 0 || outcomeUnknown) {
                    // Saved in full only when every photo was stored; otherwise the Stylist has been told what went wrong.
                    actions.reloadAfterChange(saved = stored == sources.size) { reloadAppointmentAndLists(appointmentId) }
                }
            } finally {
                _uploadingPhoto.value = false
            }
        }
    }

    fun setPhotoCategory(appointmentId: String, photoId: String, category: PhotoCategory) =
        saveLatest(
            photoCategoryChanges,
            appointmentId,
            photoId,
            category,
            saved = loadedAppointments[appointmentId]?.photos?.firstOrNull { it.id == photoId }?.category,
        ) { next ->
            api.updatePhotoCategory(photoId, next)
            updateLoaded(appointmentId) { appointment ->
                appointment.copy(photos = appointment.photos.map { if (it.id == photoId) it.copy(category = next) else it })
            }
        }

    fun deletePhoto(appointmentId: String, photoId: String) =
        actOnAppointment(appointmentId, "deletePhoto:$photoId") { api.deletePhoto(photoId) }

    companion object {
        fun factory(container: AppContainer, auth: AuthGateway): ViewModelProvider.Factory =
            viewModelFactory { initializer { AppViewModel(container, auth, createSavedStateHandle()) } }
    }
}

/**
 * The Client list with a just-saved Client in it, first as on the server (latest change first).
 * The reply to a save leaves the Profile Share out, so the one already known is kept.
 */
internal fun List<ClientProfile>.withSaved(saved: ClientProfile): List<ClientProfile> {
    val known = firstOrNull { it.id == saved.id }

    return listOf(saved.copy(activeProfileShare = saved.activeProfileShare ?: known?.activeProfileShare)) + filterNot { it.id == saved.id }
}

/**
 * The Appointment with what it repeats of a Client replaced by that Client as just saved: the name,
 * phone, language and address of their row, and the Appointment's primary Client name.
 */
internal fun Appointment.withClient(saved: ClientProfile): Appointment {
    if (participants.none { it.clientId == saved.id } && primaryClientId != saved.id) {
        return this
    }

    return copy(
        primaryClientName = if (primaryClientId == saved.id) saved.name else primaryClientName,
        participants = participants.map { participant ->
            if (participant.clientId == saved.id) {
                participant.copy(name = saved.name, phone = saved.phone, language = saved.language, address = saved.address)
            } else {
                participant
            }
        },
    )
}
