package com.northphoenix.hairdresserclientmanager.state

import android.net.Uri
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.SingletonImageLoader
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.auth.AccountName
import com.northphoenix.hairdresserclientmanager.auth.AuthGateway
import com.northphoenix.hairdresserclientmanager.auth.AuthState
import com.northphoenix.hairdresserclientmanager.auth.SessionCheck
import com.northphoenix.hairdresserclientmanager.core.AppContainer
import com.northphoenix.hairdresserclientmanager.data.ApiException
import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentPhoto
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.data.CameraCaptures
import com.northphoenix.hairdresserclientmanager.data.ClientHistoryEntry
import com.northphoenix.hairdresserclientmanager.data.ClientProfile
import com.northphoenix.hairdresserclientmanager.data.CreateAppointmentResult
import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.data.LocationType
import com.northphoenix.hairdresserclientmanager.data.PendingPhoto
import com.northphoenix.hairdresserclientmanager.data.PendingPhotoStore
import com.northphoenix.hairdresserclientmanager.data.PhotoCategory
import com.northphoenix.hairdresserclientmanager.data.PhotoPreparer
import com.northphoenix.hairdresserclientmanager.data.ProfileShare
import com.northphoenix.hairdresserclientmanager.data.ServiceMenuItem
import com.northphoenix.hairdresserclientmanager.data.SharedPictures
import com.northphoenix.hairdresserclientmanager.data.ShareChoice
import com.northphoenix.hairdresserclientmanager.data.ShareImage
import com.northphoenix.hairdresserclientmanager.data.StoredFile
import com.northphoenix.hairdresserclientmanager.data.Stylist
import com.northphoenix.hairdresserclientmanager.data.UpdatedAppointment
import com.northphoenix.hairdresserclientmanager.data.UploadThingClient
import com.northphoenix.hairdresserclientmanager.data.toApiException
import com.northphoenix.hairdresserclientmanager.domain.AppointmentClients
import com.northphoenix.hairdresserclientmanager.domain.AppointmentDraft
import com.northphoenix.hairdresserclientmanager.domain.ChangedElsewhere
import com.northphoenix.hairdresserclientmanager.domain.OneLine
import com.northphoenix.hairdresserclientmanager.domain.ServiceEdit
import com.northphoenix.hairdresserclientmanager.domain.TimeFormat
import com.northphoenix.hairdresserclientmanager.domain.zoneOrNull
import com.northphoenix.hairdresserclientmanager.domain.zoneOrSystem
import com.northphoenix.hairdresserclientmanager.i18n.messageRes
import com.northphoenix.hairdresserclientmanager.i18n.refusedSignInMessage
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A transient message for the snackbar, resolved to text by the UI in the current app language. [args] fill the text's placeholders. */
data class Notice(@StringRes val message: Int, val isError: Boolean = false, val args: List<Any> = emptyList())

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

    /** The Stylist's name on the sign-in account, which Clients see on Profile Shares and Share Images. Null when the account cannot have one. */
    val accountName: StateFlow<AccountName?> = auth.accountName

    private val sessionWatch = SessionWatch()

    /** True while signed out because the session ended on its own: the sign-in screen says why it appeared. */
    val sessionEnded: StateFlow<Boolean> = sessionWatch.ended

    private val _bootstrap = MutableStateFlow<BootstrapState>(BootstrapState.Loading)
    val bootstrap: StateFlow<BootstrapState> = _bootstrap.asStateFlow()

    private val _stylist = MutableStateFlow<Stylist?>(null)
    val stylist: StateFlow<Stylist?> = _stylist.asStateFlow()

    /** Decides which language the app is in. Nothing else sets the app language. */
    private val language = StylistLanguage(container.appLanguage)

    /** Every save and every fetch of the Stylist's settings after the first goes through this, so that none overtakes another. */
    private val settingsRequests = SettingsRequests()

    /** The Stylist's timezone. Appointment times and day boundaries are always in it. */
    val zone: StateFlow<ZoneId> =
        _stylist.map { zoneOrSystem(it?.timezone) }.stateIn(viewModelScope, SharingStarted.Eagerly, ZoneId.systemDefault())

    // Locale-independent: only used here for day boundaries. Straight from the loaded Stylist:
    // [zone] follows a moment later, and a day worked out right after a save must be in the new timezone.
    private val clock: TimeFormat get() = TimeFormat(Locale.ROOT, zoneOrSystem(_stylist.value?.timezone))

    private val day = StylistDay()

    /**
     * Today in the Stylist's timezone. Today's list is of this day and the Calendar starts on it.
     * It changes only in [followDay], together with them.
     */
    val today: StateFlow<LocalDate> = day.today

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

    private val _calendarDate = MutableStateFlow(day.today.value)
    val calendarDate: StateFlow<LocalDate> = _calendarDate.asStateFlow()

    /** A day other than today that the Stylist has selected, so that it is still selected after Android has killed the app. */
    private val savedCalendarDay = SavedCalendarDay(savedState)

    private val _calendar = MutableStateFlow<List<Appointment>>(emptyList())
    val calendar: StateFlow<List<Appointment>> = _calendar.asStateFlow()

    /** Of the day in [calendarDate]. Reset whenever that day changes, so it never speaks for another day. */
    private val calendarLoading = LoadTracker()
    val calendarLoad: StateFlow<LoadState> = calendarLoading.state

    // What can be copied into the Appointment whose copy sheet is open (see [showCopySources]).
    private val copySources = CopySources { api.completedSources(it) }
    val completedSources: StateFlow<List<Appointment>> = copySources.sources
    val completedSourcesLoad: StateFlow<LoadState> = copySources.state

    // The Appointment a copy is being made into, for its copy sheet to say so. Null when no copy request is running.
    private val _copyingInto = MutableStateFlow<String?>(null)
    val copyingInto: StateFlow<String?> = _copyingInto.asStateFlow()

    private val loadedAppointments = LoadedAppointments()

    /** Every Appointment seen so far, by id, so a detail screen can render whichever list it came from. */
    val appointments: StateFlow<Map<String, Appointment>> = loadedAppointments.loaded

    /** Why an Appointment that a screen asked for is not in [appointments]: loading, failed to load or gone. */
    val absentAppointments: StateFlow<Map<String, LoadState>> = loadedAppointments.absent

    private val overlaps = OverlapWarnings()

    /**
     * By Appointment, the other Scheduled Appointments at its time, as the server named them when
     * it was created, moved or set back to Scheduled. Shown on that Appointment until dismissed.
     */
    val overlapWarnings: StateFlow<Map<String, List<Appointment>>> = overlaps.shown

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
    private val noteChanges = LatestWins<String, String>()
    private val finalTotalChanges = LatestWins<String, Long?>()
    private val photoCategoryChanges = LatestWins<String, PhotoCategory>()

    /** The photos on their way to the server: kept on the device and sent one at a time until the server has them (see [PhotoUploads]). */
    private val photos = PhotoUploads(
        viewModelScope,
        PendingPhotoStore(File(container.appContext.filesDir, "photo-uploads")),
        prepare = ::preparedPhoto,
        send = ::sendPhoto,
        record = ::recordPhoto,
        reload = ::reloadForPhoto,
        isRecorded = ::isPhotoRecorded,
        onFailure = ::report,
    )

    /** Every Appointment's, so each screen picks its own: an upload is shown on the Appointment and the Client it belongs to. */
    val photoUploads: StateFlow<List<PhotoUpload>> = photos.uploads

    /** Where the camera writes. A capture is only needed until [photos] has its own copy of it. */
    private val captures = CameraCaptures(File(container.appContext.cacheDir, "camera"))

    /** Where a Share Image is written for another app to read. */
    private val shared = SharedPictures(File(container.appContext.cacheDir, SharedPictures.DIRECTORY))

    /** Of the app's file provider, which gives the camera the address of a capture file. */
    private val filesAuthority = container.appContext.packageName + ".files"

    private val noticeChannel = Channel<Notice>(Channel.BUFFERED)
    val notices = noticeChannel.receiveAsFlow()

    private var calendarJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) { captures.deleteStale() }
        // A picture left as failed because the reply was lost is done with once its Appointment is loaded with the photo.
        viewModelScope.launch { loadedAppointments.loaded.collect { photos.dropRecorded() } }
        viewModelScope.launch {
            authState
                // In this order: a sign-out empties the upload queue while the queue still knows whose pictures it holds.
                .onEach { state ->
                    if (sessionWatch.onState(state)) {
                        forgetDevice()
                    }
                }
                .distinctUntilChangedBy { (it as? AuthState.SignedIn)?.userId }
                .collect { state ->
                    clearStylistData()
                    if (state is AuthState.SignedIn) {
                        loadStylist()
                    }
                }
        }
    }

    fun profileShareUrl(token: String): String = container.config.profileShareUrl(token)

    fun notify(@StringRes message: Int, isError: Boolean = false, vararg args: Any) {
        noticeChannel.trySend(Notice(message, isError, args.toList()))
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
        _bootstrap.value = BootstrapState.Loading
        _clients.value = emptyList()
        _serviceMenu.value = emptyList()
        _home.value = emptyList()
        _calendar.value = emptyList()
        copySources.clear()
        // Resetting also drops whatever is still on its way for the previous Stylist.
        listOf(clientsLoading, serviceMenuLoading, homeLoading, calendarLoading).forEach(LoadTracker::reset)
        loadedAppointments.clear()
        overlaps.clear()
        actions.forgetOwed()
        photos.close()
    }

    // --- loading ---

    /** Resolves (or lazily creates) the Stylist for the signed-in user, then loads their data. */
    fun loadStylist() {
        viewModelScope.launch {
            _bootstrap.value = BootstrapState.Loading

            try {
                val stylist = api.bootstrap(container.appLanguage.device(), ZoneId.systemDefault().id)
                showStylist(stylist)
                // What was still on its way when the app was last closed goes on.
                photos.open(stylist.id)
                // The day that was selected when Android killed the app in the background, today otherwise.
                day.follow(clock.zone)
                _calendarDate.value = savedCalendarDay.take(stylist.id, today.value) ?: today.value
                _bootstrap.value = BootstrapState.Ready

                if (stylist.onboardingComplete) {
                    // Every time is then in the device's timezone (see [zoneOrSystem]). Onboarding shows the same under its timezone field.
                    if (zoneOrNull(stylist.timezone) == null) {
                        notify(R.string.error_timezone_unusable, isError = true)
                    }
                    // The settings have just been loaded: only the lists.
                    refreshAll(::loadAll)
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

    /** Puts a Stylist the server has just sent in place, and the app in that Stylist's language. */
    private fun showStylist(stylist: Stylist) {
        _stylist.value = stylist
        followLanguage()
    }

    /**
     * The Stylist's settings again: they may have been saved on another device since they were
     * loaded. Whoever calls this loads the Appointment lists again when the timezone is another
     * one, because they are of days in it (see [loadEverything]). An answer that a save from this
     * device has overtaken is dropped (see [SettingsRequests]).
     */
    private suspend fun refreshSettings() {
        val loaded = settingsRequests.fetch { api.bootstrap(container.appLanguage.device(), ZoneId.systemDefault().id) } ?: return

        // Not for a Stylist who has signed out in the meantime.
        if (_stylist.value?.id == loaded.id) {
            showStylist(loaded)
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
     * The app is in front again, and what it shows may have been changed elsewhere meanwhile: the
     * lists, and the Stylist's settings on another device.
     * Nobody asked for this reload, so it fails quietly and what was on screen stays.
     */
    fun refreshOnReturn() {
        if (_stylist.value?.onboardingComplete == true) {
            // The day may have ended meanwhile. Now, so that the screen is on the new day before the reload has its turn.
            followDay()
            viewModelScope.launch { actions.reloadWithRetry(::loadEverything) }
        }
    }

    /**
     * Called while the app is in view each time the Stylist's day may have ended (see
     * [millisUntilDayCheck]). When it has, Today and a Calendar that was on today load the new day.
     * Nobody asked for this reload: when it fails, the lists say so themselves and nothing else does.
     */
    fun checkDay() {
        if (_stylist.value?.onboardingComplete == true && followDay()) {
            viewModelScope.launch { actions.reloadWithRetry(::loadAppointmentLists) }
        }
    }

    /** How long to wait before the next [checkDay]. */
    fun millisUntilDayCheck(): Long = day.millisUntilNextLook(clock.zone)

    /**
     * Looks at the clock. When it is another day for the Stylist than [today] (midnight has
     * passed, or the timezone is another one), Today starts over for the new day and a Calendar
     * that was on today moves along with it; a day the Stylist picked stays selected.
     * True when the day has changed: whoever calls this loads the lists afterwards, because
     * what was loaded or on its way for the day that ended is dropped here.
     */
    private fun followDay(): Boolean {
        val change = day.follow(clock.zone) ?: return false

        _home.value = emptyList()
        homeLoading.reset()
        showCalendarDay(change.calendarDay(_calendarDate.value))

        return true
    }

    private suspend fun refreshAll(load: suspend () -> Unit = ::loadEverything) = reporting {
        load()
        // The server answers: what could not be reloaded after an earlier change is reloaded as well.
        actions.reloadOwed()
    }

    /**
     * The settings and the lists (see [withSettings]). The Appointment lists are of days in the
     * Stylist's timezone: they are loaded again when the settings come back with another one.
     */
    private suspend fun loadEverything() = withSettings({ clock.zone }, ::refreshSettings, ::loadAll, ::loadAppointmentLists)

    private suspend fun loadAll() = together(::loadClients, ::loadServiceMenu, ::loadAppointmentLists)

    private suspend fun loadClients() = clientsLoading.load(api::listClients) { _clients.value = it }

    private suspend fun loadServiceMenu() = serviceMenuLoading.load(api::listServiceMenu) { _serviceMenu.value = it }

    /** Every load of the lists looks at the clock first, so that none of them loads a day that has ended. */
    private suspend fun loadAppointmentLists() {
        followDay()
        together(::loadHome, ::loadCalendarDay, copySources::load)
    }

    private suspend fun loadHome() {
        val (start, end) = clock.dayRange(today.value)

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

    /**
     * The copy sheet of [targetAppointmentId] is open: loads what can be copied into it. Called
     * when the sheet opens and again when the Clients of the Appointment change under it, because
     * the list depends on them. While the sheet is open the list is loaded with the Appointment
     * lists (see [loadAppointmentLists]), so a refused copy leaves it up to date.
     */
    fun showCopySources(targetAppointmentId: String) {
        copySources.open(targetAppointmentId)
        viewModelScope.launch { reporting { copySources.load() } }
    }

    /** The copy sheet of [targetAppointmentId] has closed: its list is not kept, nor loaded any longer. */
    fun closeCopySources(targetAppointmentId: String) = copySources.close(targetAppointmentId)

    /** Applies a change the server has just accepted to the loaded copies of an Appointment. */
    private fun updateLoaded(id: String, change: (Appointment) -> Appointment) {
        val changed = { appointments: List<Appointment> -> appointments.map { if (it.id == id) change(it) else it } }

        _home.update(changed)
        _calendar.update(changed)
        loadedAppointments.update(id, change)
    }

    /** Drops an Appointment the server has just deleted from everything loaded, and from the overlap warnings. */
    private fun forgetAppointment(id: String) {
        val without = { appointments: List<Appointment> -> appointments.filterNot { it.id == id } }

        _home.update(without)
        _calendar.update(without)
        copySources.forget(id)
        loadedAppointments.forget(id)
        overlaps.gone(id)
    }

    /**
     * Fetches one Appointment again, by its id: its start time can be another one by now. An
     * Appointment the server no longer has counts as gone, and no overlap warning names it any more.
     */
    private suspend fun reloadAppointment(id: String) {
        loadedAppointments.reload(id) { api.findAppointment(id) }

        if (loadedAppointments[id] == null) {
            overlaps.gone(id)
        }
    }

    private suspend fun reloadAppointmentAndLists(id: String) = together(::loadAppointmentLists, { reloadAppointment(id) })

    /**
     * Called by the Appointment screen each time it comes into view, and by its 'Try again'.
     * A loaded copy is shown at once and refreshed behind it; a refresh nobody asked for fails quietly.
     * Without a copy the screen shows how the load went (see [absentAppointments]).
     */
    fun loadAppointment(id: String) {
        viewModelScope.launch {
            val shownStart = loadedAppointments[id]?.startsAt

            try {
                reloadAppointment(id)

                val reloaded = loadedAppointments[id]

                if ((reloaded == null || (shownStart != null && reloaded.startsAt != shownStart)) && isListed(id)) {
                    // Gone, or moved to another time elsewhere: the lists that still show it as it was are loaded again, quietly.
                    actions.reloadWithRetry(::loadAppointmentLists)
                } else {
                    actions.reloadOwed()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (loadedAppointments[id] == null) {
                    report(error.toApiException())
                }
            }
        }
    }

    /** Whether a list on screen has a card for this Appointment. */
    private fun isListed(id: String): Boolean =
        listOf(_home, _calendar, copySources.sources).any { list -> list.value.any { it.id == id } }

    /** Shows [date] in the Calendar. Choosing the day that is already shown loads it again, which is also the retry after a failed load. */
    fun setCalendarDate(date: LocalDate) {
        showCalendarDay(date)

        calendarJob?.cancel()
        calendarJob = viewModelScope.launch { reporting { loadCalendarDay() } }
    }

    /** Makes [date] the Calendar's day. What is loaded or on its way for another day is dropped; the caller loads [date]. */
    private fun showCalendarDay(date: LocalDate) {
        if (date != _calendarDate.value) {
            _calendarDate.value = date
            _calendar.value = emptyList()
            calendarLoading.reset()
        }
        _stylist.value?.let { savedCalendarDay.keep(it.id, date.takeIf { selected -> selected != today.value }) }
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
     * When a later request of the taps fails, the reload still shows what an earlier one saved.
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

        var savedAny = false

        // No key: the values are ordered here, and the next change may start while this one is still reloading.
        actions.run(
            key = null,
            change = {
                changes.send(target) { next ->
                    save(next)
                    loadedAppointments.changed(appointmentId)
                    savedAny = true
                }
            },
            reload = { reloadAppointmentAndLists(appointmentId) },
            savedInPart = { savedAny },
        ) { _ -> successNotice?.let { notify(it) } }
    }

    // --- settings ---

    /**
     * The Stylist chose [chosen] as the app language, in the selector or in Android's settings.
     * The app shows it straight away. For an onboarded Stylist it is also saved, so the choice
     * follows them to other devices, and when it cannot be saved the app goes back to the saved
     * language. During onboarding it is saved with the rest. See [StylistLanguage].
     */
    fun setLanguage(chosen: Language) {
        val current = _stylist.value ?: return

        if (!language.choose(current, chosen)) {
            return
        }

        var confirmed = false
        // The other settings came from the server with the switch: they may be another timezone.
        var settingsLoaded = false

        // No key: the choices are ordered by [language], as in [saveLatest]. Nothing to reload once the server
        // has confirmed the language; after a request that got no reply, the settings show whether it was saved.
        act(key = null, reload = { if (!confirmed || settingsLoaded) loadEverything() }) {
            language.save(saved = { _stylist.value?.language }) { next ->
                settingsRequests.save {
                    // The settings as they are now, not as they were at the tap: a save that has ended since must not be undone.
                    // And when they were saved on another device since, as they are there: the switch is of the language alone.
                    _stylist.value?.let { latest ->
                        _stylist.value = onLatestSettings(
                            known = latest,
                            load = { api.bootstrap(container.appLanguage.device(), ZoneId.systemDefault().id).also { settingsLoaded = true } },
                        ) { settings -> api.saveSettings(next, settings.timezone, settings.salonAddress, settings.updatedAt) }
                    }
                }
            }
            confirmed = true
        }
    }

    /**
     * Brings the app language in line with the Stylist (see [StylistLanguage]). Called whenever
     * either may be another one: the Stylist was loaded or saved, or the app's locale changed. A
     * language chosen in Android's settings arrives as such a change and is saved like one chosen here.
     */
    fun followLanguage() {
        val current = _stylist.value ?: return

        language.follow(current)?.let(::setLanguage)
    }

    fun saveSettings(timezone: String, salonAddress: String) {
        val current = _stylist.value ?: return
        val trimmedTimezone = timezone.trim()

        if (trimmedTimezone.isEmpty()) {
            notify(R.string.timezone_required, isError = true)
            return
        }

        var saved = false

        act(
            key = "saveSettings",
            successNotice = if (current.onboardingComplete) R.string.settings_saved else null,
            // Not saved: refused because the settings were saved on another device, or never answered. Both show in the settings.
            reload = { if (saved) loadAll() else loadEverything() },
        ) {
            settingsRequests.save {
                // The settings as they are now: a language switch that was still being saved has ended by this save's turn,
                // and it may have brought settings from another device with it. What the Stylist changed in the form goes
                // on top of those, under their version, so the server refuses it when they have been saved elsewhere since.
                // The reply is put in place here, before the next save has its turn: that one is built from it.
                val latest = _stylist.value ?: current

                // That language switch brought another value for a setting the Stylist changed here too, after Save was
                // tapped. It is refused as the server refuses a form that is older than the settings: said, and saved on the next Save.
                if (ChangedElsewhere.collides(current.timezone, trimmedTimezone, latest.timezone) ||
                    ChangedElsewhere.collides(current.salonAddress, salonAddress.trim(), latest.salonAddress)
                ) {
                    throw ApiException.changedElsewhere()
                }

                showStylist(
                    api.saveSettings(
                        language.toSave(latest),
                        ChangedElsewhere.field(current.timezone, trimmedTimezone, latest.timezone),
                        ChangedElsewhere.field(current.salonAddress, salonAddress.trim(), latest.salonAddress),
                        latest.updatedAt,
                    ),
                )
            }
            saved = true
            // In another timezone it can be another day. The reload loads it.
            followDay()
        }
    }

    /**
     * Saves the Stylist's name on the sign-in account, which is where Profile Shares and Share
     * Images take it from. It is not one of the settings this server keeps.
     */
    fun saveAccountName(first: String, last: String) =
        act(key = "saveAccountName", successNotice = R.string.account_name_saved) { auth.saveAccountName(AccountName.of(first, last)) }

    /**
     * Signs the Stylist out. Once they are signed out, what the device holds of their Clients is
     * deleted (see [forgetDevice]), the photos that were not uploaded yet among them: whoever
     * offers this where such photos are shown asks first.
     */
    fun signOut() {
        sessionWatch.signOutRequested()
        viewModelScope.launch {
            reporting {
                try {
                    auth.signOut()
                } catch (error: Exception) {
                    // Still signed in, which the Stylist is told. Whatever ends this session later is not this
                    // request: nothing is deleted then, and the sign-in screen says that the session has ended.
                    sessionWatch.signOutNotDone()
                    throw error
                }
            }
        }
    }

    /**
     * The Stylist has signed out, as they asked: the pictures of their Clients that the app kept on
     * this device are deleted. Those are the photos still on their way to the server, what the
     * camera left, the Share Image that was handed to another app, and the image loader's copies of
     * the photos it has shown. Appointment Photos live in photo storage and nowhere else.
     * A session that ended on its own deletes nothing: that Stylist is expected back.
     */
    private fun forgetDevice() {
        photos.forget()
        viewModelScope.launch {
            // Also when the app is going away.
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    captures.deleteAll()
                    shared.deleteAll()
                    SingletonImageLoader.get(container.appContext).run {
                        memoryCache?.clear()
                        diskCache?.clear()
                    }
                } catch (_: Exception) {
                    // The device's storage failed. Nothing here is the Stylist's to act on.
                }
            }
        }
    }

    // --- clients ---

    /**
     * Saves the Client Profile form. For a Client who exists, [loadedUpdatedAt] is the version of
     * the Client the form was filled from: a save from a form that is older than the Client is
     * refused, and the list is loaded again for the form to take in. For a new Client,
     * [requestKey] is the form's own (see `rememberRequestKey`), so a second try creates no second Client.
     */
    fun saveClient(
        id: String?,
        name: String,
        language: Language,
        phone: String,
        email: String,
        address: String,
        note: String,
        loadedUpdatedAt: String?,
        requestKey: String,
        onSuccess: (ClientProfile) -> Unit,
    ) {
        act(
            key = "saveClient:${id ?: "new"}",
            successNotice = R.string.client_saved,
            onSuccess = onSuccess,
            reload = { together(::loadClients, { if (id != null) loadAppointmentLists() }) },
        ) {
            api.saveClient(
                id, OneLine.clean(name), language, OneLine.clean(phone), email.trim(), address.trim(), note.trim(), loadedUpdatedAt, requestKey,
            ).also { saved ->
                _clients.update { it.withSaved(saved) }
                // Names and phone numbers shown on Appointments come from the Client: every loaded
                // copy gets them now, on whatever day it is, so no reminder goes to the old number.
                _home.update { appointments -> appointments.map { it.withClient(saved) } }
                _calendar.update { appointments -> appointments.map { it.withClient(saved) } }
                loadedAppointments.updateAll { it.withClient(saved) }
            }
        }
    }

    /**
     * Deletes a Client with everything that is theirs. The server does all of it or nothing, also
     * for a Client whom an Appointment names as primary without listing them.
     */
    fun deleteClient(id: String, onSuccess: () -> Unit) {
        // The loaded Appointments this Client is in or is the primary Client of: they change or go with them, on whatever
        // day they are. Named before the request, so that they are loaded again also when its reply never comes.
        val affected = AppointmentClients.changedByDeleting(id, appointments.value.values)

        act(
            key = "deleteClient:$id",
            successNotice = R.string.client_deleted,
            onSuccess = { onSuccess() },
            reload = { together(::loadAll, { affected.forEach { reloadAppointment(it.id) } }) },
        ) {
            api.deleteClient(id)
            _clients.update { clients -> clients.filterNot { it.id == id } }
            photos.removeAll { it.clientId == id }
            overlaps.clientDeleted(id)
            // An Appointment left without Clients is deleted with its last one. The reload checks each of them again.
            affected.filter { AppointmentClients.goesWith(id, it) }.forEach { forgetAppointment(it.id) }
        }
    }

    // The changes to one Client's Profile Share share a key, so they run one at a time: a choice is
    // never sent for a Profile Share that is being revoked, and a second tap on Create does nothing.
    // What the server answers goes into the list at once, so the card is right before the reload.

    /** [included] is what the new Profile Share shows. A Client who has one already keeps it as it is, and the card shows that one. */
    fun createProfileShare(clientId: String, included: Set<ShareChoice>) =
        act(profileShareKey(clientId), reload = ::loadClients) {
            val share = api.createProfileShare(clientId, included)

            _clients.update { it.withProfileShare(clientId, share) }
        }

    /** Changes one choice of the Client's Profile Share. Its link stays the same. */
    fun setProfileShareChoice(clientId: String, choice: ShareChoice, included: Boolean) =
        act(profileShareKey(clientId), reload = ::loadClients) {
            val share = api.updateProfileShare(clientId, choice, included)

            _clients.update { it.withProfileShare(clientId, share) }
        }

    fun revokeProfileShare(clientId: String) =
        act(profileShareKey(clientId), successNotice = R.string.profile_share_revoked, reload = ::loadClients) {
            api.revokeProfileShare(clientId)
            _clients.update { it.withProfileShare(clientId, null) }
        }

    fun buildShareImage(clientId: String, language: Language, onReady: (String) -> Unit) =
        act("buildShareImage:$clientId", onSuccess = { image: ShareImage -> onReady(image.svg) }) {
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

    /** [requestKey] is the editor sheet's own (see `rememberRequestKey`): it counts for a new Service Menu Item. */
    fun saveServiceMenuItem(id: String?, name: String, defaultPriceCents: Long, requestKey: String, onSuccess: () -> Unit) =
        act(
            key = "saveServiceMenuItem:${id ?: "new"}",
            successNotice = R.string.service_menu_item_saved,
            onSuccess = { onSuccess() },
            reload = ::loadServiceMenu,
        ) {
            api.saveServiceMenuItem(id, name.trim(), defaultPriceCents, requestKey)
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

    // What creates something takes the `requestKey` of the form or sheet it is sent from (see
    // `rememberRequestKey`): sent again from the same one, after a reply that never came, it
    // creates nothing more. When the form was changed in between, the server refuses it
    // ([ApiException.isMadeWithOtherValues]) and the reload shows what the first try saved.

    fun createAppointment(
        primaryClientId: String?,
        additionalClientIds: List<String>,
        inlineClientName: String?,
        startsAt: Instant,
        endsAt: Instant?,
        locationType: LocationType,
        customLocationAddress: String?,
        requestKey: String,
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
            requestKey = requestKey,
        ).also {
            loadedAppointments.remember(listOf(it.appointment))
            // The notice only says that there is an overlap. The Appointment's own screen names it, until dismissed.
            overlaps.answered(it.appointment.id, it.conflicts)
        }
    }

    /**
     * Status taps are saved one at a time in the order they were made, so the last tap is the status that stays.
     * A status that goes back to Scheduled can land on a time that is taken: the answer says by which Appointments.
     */
    fun setAppointmentStatus(id: String, status: AppointmentStatus) =
        saveLatest(statusChanges, id, id, status, saved = loadedAppointments[id]?.status) { next ->
            val saved = api.updateAppointmentStatus(id, next)

            updateLoaded(id) { it.copy(status = next).withTimeAndPlace(saved) }
            overlaps.answered(id, saved.conflicts)
        }

    /**
     * Edit Appointment: saves the day, the times and the place of an Appointment. [times] is null
     * when the Stylist left them as they were, and they are then not sent; so is [locationType] for
     * a place that is kept ([AppointmentDraft.keepsPlace]). [ownAddress] is the at-home address the
     * Stylist typed, null for the primary Client's.
     */
    fun editAppointment(
        id: String,
        times: AppointmentDraft.Times.Valid?,
        locationType: LocationType?,
        ownAddress: String?,
        onSuccess: () -> Unit,
    ) = actOnAppointment(id, "editAppointment:$id", R.string.appointment_time_and_place_saved, onSuccess) {
        val saved = api.updateAppointmentTimeAndPlace(id, times?.startsAt, times?.endsAt, locationType, ownAddress?.trim().orEmpty())

        updateLoaded(id) { it.withTimeAndPlace(saved) }
        // An answer to times that were not sent names no overlap, and says nothing about the one there may be.
        if (times != null) {
            overlaps.answered(id, saved.conflicts)
        }
    }

    fun dismissOverlapWarning(appointmentId: String) = overlaps.dismiss(appointmentId)

    // Not put into the loaded copy: the Appointment's address can change with its primary Client, and only the reload shows to what.
    fun setAppointmentPrimary(id: String, clientId: String) =
        actOnAppointment(id, clientsChangeKey(id)) { api.updateAppointmentPrimary(id, clientId) }

    fun saveAppointmentNote(id: String, note: String) =
        saveLatest(noteChanges, id, id, note.trim(), saved = loadedAppointments[id]?.note, R.string.appointment_note_saved) { next ->
            api.updateAppointmentNote(id, next)
            updateLoaded(id) { it.copy(note = next) }
        }

    /** [overrideCents] null clears the override, and the final total is the Service total again. */
    fun setAppointmentFinalTotal(id: String, overrideCents: Long?) =
        saveLatest(finalTotalChanges, id, id, overrideCents, saved = loadedAppointments[id]?.finalTotalCentsOverride) { next ->
            api.updateAppointmentFinalTotal(id, next)
            updateLoaded(id) { it.copy(finalTotalCentsOverride = next, finalTotalCents = next ?: it.serviceTotalCents) }
        }

    fun deleteAppointment(id: String, onSuccess: () -> Unit) =
        actOnAppointment(id, "deleteAppointment:$id", onSuccess = onSuccess) {
            api.deleteAppointment(id)
            forgetAppointment(id)
            photos.removeAll { it.appointmentId == id }
        }

    fun addParticipant(appointmentId: String, clientId: String) =
        actOnAppointment(appointmentId, clientsChangeKey(appointmentId)) { api.addParticipant(appointmentId, clientId) }

    fun removeParticipant(appointmentId: String, clientId: String) {
        // As the server does for the photos it has: the Stylist deals with them first, so none is dropped without a word.
        if (photos.uploads.value.any { it.photo.appointmentId == appointmentId && it.photo.clientId == clientId }) {
            notify(R.string.error_client_has_photo_uploads, isError = true)
            return
        }

        actOnAppointment(appointmentId, clientsChangeKey(appointmentId)) { api.removeParticipant(appointmentId, clientId) }
    }

    fun addService(
        appointmentId: String,
        clientId: String,
        menuItemId: String?,
        name: String?,
        priceCents: Long?,
        note: String?,
        requestKey: String,
        onSuccess: () -> Unit,
    ) = actOnAppointment(appointmentId, "addService:$appointmentId", R.string.appointment_service_saved, onSuccess) {
        api.addService(appointmentId, clientId, menuItemId, name?.trim(), priceCents, note?.trim(), requestKey)
    }

    /**
     * Saves an edit of a Service: its name, its price and its note, all three as the form has
     * them (see [ServiceEdit]). Its Client, its Color Formulas and its place among the Services stay.
     */
    fun updateService(appointmentId: String, serviceId: String, edit: ServiceEdit.Values, onSuccess: () -> Unit) =
        actOnAppointment(appointmentId, "updateService:$serviceId", R.string.appointment_service_updated, onSuccess) {
            api.updateService(serviceId, edit.name, edit.priceCents, edit.note)
        }

    fun deleteService(appointmentId: String, serviceId: String) =
        actOnAppointment(appointmentId, "deleteService:$serviceId") { api.deleteService(serviceId) }

    /**
     * Copies Services into [targetAppointmentId] from [sourceAppointmentId], or from the last
     * Completed Appointment that has something to copy when none is named, and says how many
     * were copied. [requestKey] is the copy sheet's own for as long as it is open: a copy sent
     * again with it, after a reply that never came, is not made a second time.
     * A copy the server refuses copied nothing; the failure says why (see `messageRes`), and the
     * reload brings the sheet's list up to date.
     */
    fun copyServices(targetAppointmentId: String, sourceAppointmentId: String?, requestKey: String, onSuccess: () -> Unit) =
        act(
            key = "copyServices:$targetAppointmentId",
            onSuccess = { copied: Int? ->
                copied?.let { notify(R.string.copy_services_done, isError = false, it) }
                onSuccess()
            },
            reload = { reloadAppointmentAndLists(targetAppointmentId) },
        ) {
            _copyingInto.value = targetAppointmentId
            try {
                api.copyServices(targetAppointmentId, sourceAppointmentId, requestKey).also { loadedAppointments.changed(targetAppointmentId) }
            } finally {
                _copyingInto.value = null
            }
        }

    /** [requestKey] counts for a new Color Formula; an edit ([formulaId]) does not send it. */
    fun saveColorFormula(
        appointmentId: String,
        serviceId: String,
        formulaId: String?,
        formula: String,
        placement: String,
        requestKey: String,
        onSuccess: () -> Unit,
    ) = actOnAppointment(appointmentId, "saveColorFormula:${formulaId ?: serviceId}", onSuccess = onSuccess) {
        if (formulaId == null) {
            api.addColorFormula(serviceId, formula.trim(), placement.trim(), requestKey)
        } else {
            api.updateColorFormula(formulaId, formula.trim(), placement.trim())
        }
    }

    fun deleteColorFormula(appointmentId: String, formulaId: String, onSuccess: () -> Unit = {}) =
        actOnAppointment(appointmentId, "deleteColorFormula:$formulaId", onSuccess = onSuccess) { api.deleteColorFormula(formulaId) }

    // --- photos ---

    /**
     * Hands the selected pictures to [photos], which keeps each on the device and uploads it. A
     * picture that cannot be read is left out without stopping the others, and the Stylist is told
     * how many were.
     * [replacingPhotoId] is a failed photo on the server, removed once the picture taken for it is stored.
     */
    fun addPhotos(appointmentId: String, clientId: String, category: PhotoCategory, sources: List<Uri>, replacingPhotoId: String? = null) {
        if (sources.isEmpty()) {
            return
        }

        viewModelScope.launch {
            try {
                reporting {
                    val unread = photos.add(PhotoTarget(appointmentId, clientId, category, replacingPhotoId), sources) { source ->
                        PhotoPreparer.copy(container.appContext, source)
                    }

                    unreadPhotosNotice(sources.size, unread)?.let(noticeChannel::trySend)
                }
            } finally {
                // A camera capture is in the queue by now or could not be read: either way its file has served.
                deleteCaptures(sources)
            }
        }
    }

    /** The address of a new file for the camera to write a capture into. Hand what comes back to [addPhotos] or to [discardCameraCapture]. */
    fun newCameraCapture(): Uri = FileProvider.getUriForFile(container.appContext, filesAuthority, captures.newFile())

    /** The Stylist came back from the camera without a photo: whatever the camera wrote is deleted. */
    fun discardCameraCapture(capture: Uri) {
        viewModelScope.launch { deleteCaptures(listOf(capture)) }
    }

    /** Deletes the files of those of [sources] that are camera captures. Also done when the coroutine is being cancelled. */
    private suspend fun deleteCaptures(sources: List<Uri>) {
        val names = sources.filter { it.authority == filesAuthority }.map { it.lastPathSegment }

        if (names.isNotEmpty()) {
            withContext(NonCancellable + Dispatchers.IO) { names.forEach(captures::delete) }
        }
    }

    private suspend fun preparedPhoto(picked: File): ByteArray {
        val bytes = PhotoPreparer.prepare(container.appContext, Uri.fromFile(picked))

        if (bytes.size > UploadThingClient.MAX_PHOTO_BYTES) {
            throw ApiException("The photo is larger than 8 MB.", code = PhotoPreparer.TOO_LARGE)
        }

        return bytes
    }

    /**
     * Nothing is sent to a server that says it has no photo storage (see [Stylist.photoStorageConfigured]):
     * the failure then says so, instead of inviting another try that cannot work either.
     */
    private fun requirePhotoStorage() {
        if (_stylist.value?.photoStorageConfigured == false) {
            throw ApiException("The server has no photo storage.", code = UploadThingClient.NO_PHOTO_STORAGE)
        }
    }

    /** Uploads through UploadThing first, and only what is stored there is recorded: the database never points at a file that failed to store. */
    private suspend fun sendPhoto(photo: PendingPhoto, bytes: ByteArray): StoredFile {
        requirePhotoStorage()

        val uploaded = container.uploads.upload(
            slug = UploadThingClient.APPOINTMENT_PHOTO_SLUG,
            input = buildJsonObject {
                put("appointmentId", photo.appointmentId)
                put("clientId", photo.clientId)
                put("category", photo.category.wire)
            },
            fileName = "appointment-photo-${photo.createdAt}.jpg",
            mimeType = PhotoPreparer.MIME_TYPE,
            bytes = bytes,
        )
        val serverData = UploadThingClient.decodeUploadedPhoto(uploaded.serverData)
        val url = serverData?.url ?: uploaded.ufsUrl

        return StoredFile(fileKey = serverData?.fileKey ?: uploaded.key, url = url, thumbnailUrl = serverData?.thumbnailUrl ?: url)
    }

    private suspend fun recordPhoto(photo: PendingPhoto, stored: StoredFile) {
        // Such a server takes no stored file as a photo either.
        requirePhotoStorage()

        // The photo's id is its request key: recorded once, however often this is sent.
        val photosNow =
            api.addStoredPhoto(photo.appointmentId, photo.clientId, photo.category, stored.fileKey, stored.url, stored.thumbnailUrl, requestKey = photo.id)

        if (photosNow?.hasStored(stored) == true) {
            // The reply states the Appointment's photos with this one among them: it is shown at once, also when the reload after it fails.
            updateLoaded(photo.appointmentId) { it.copy(photos = photosNow) }
        } else {
            loadedAppointments.changed(photo.appointmentId)
        }

        photo.replacingPhotoId?.let { replaced ->
            try {
                api.deletePhoto(replaced)
            } catch (error: ApiException) {
                // The new photo is recorded whatever becomes of this, so it is not a failed upload: the Stylist
                // is told, and the failed photo stays for them to remove. Removed already is as good.
                if (!error.isNotFound) {
                    report(error)
                }
            }
        }
    }

    /**
     * The Appointment of a photo that has reached the server, or may have, is loaded again. One
     * that is not loaded is left alone: it comes with its photos when the Stylist opens it, which
     * is how it is after the app was closed with an upload still on its way.
     */
    private suspend fun reloadForPhoto(photo: PendingPhoto, saved: Boolean) =
        actions.reloadAfterChange(saved) {
            if (loadedAppointments[photo.appointmentId] != null) {
                reloadAppointmentAndLists(photo.appointmentId)
            }
        }

    /** Whether the Appointment, as it is loaded now, has the photo of this picture: the server recorded it, also when its reply never came. */
    private fun isPhotoRecorded(photo: PendingPhoto): Boolean {
        val stored = photo.stored ?: return false

        return loadedAppointments[photo.appointmentId]?.photos?.hasStored(stored) == true
    }

    /** 'Upload again' for one photo that failed to upload. */
    fun retryPhotoUpload(uploadId: String) = photos.retry(uploadId)

    /** 'Upload again' for every photo of this Client in this Appointment that failed to upload. */
    fun retryPhotoUploads(appointmentId: String, clientId: String) =
        photos.retryAll { it.appointmentId == appointmentId && it.clientId == clientId }

    /** 'Remove photo' for a photo that is not on the server yet: it is deleted from the device and never sent. */
    fun removePhotoUpload(uploadId: String) = photos.remove(uploadId)

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
 * Whether these photos, an Appointment's as the server states them, have the one recorded for this
 * picture in photo storage. Every upload makes a file of its own, so its address tells.
 */
internal fun List<AppointmentPhoto>.hasStored(file: StoredFile): Boolean = any { it.url == file.url }

/**
 * What to say about the pictures of a selection that were left out because they could not be read
 * ([unread], of [selected] pictures), or null when every one was added.
 */
internal fun unreadPhotosNotice(selected: Int, unread: List<ApiException>): Notice? =
    when {
        unread.isEmpty() -> null
        // The only picture: why it was not added.
        selected == 1 -> Notice(unread.single().messageRes(), isError = true)
        unread.size == selected -> Notice(R.string.appointment_photos_none_read, isError = true)
        // How many, so that the Stylist knows the pictures on screen are not the whole selection.
        else -> Notice(R.string.appointment_photos_some_unread, isError = true, args = listOf(unread.size, selected))
    }

internal fun profileShareKey(clientId: String) = "profileShare:$clientId"

/** The Client list with what the server has just answered about one Client's Profile Share: [share], or none after a revoke. */
internal fun List<ClientProfile>.withProfileShare(clientId: String, share: ProfileShare?): List<ClientProfile> =
    map { if (it.id == clientId) it.copy(activeProfileShare = share) else it }

/**
 * The Client list with a just-saved Client in it, first as on the server (latest change first).
 * The reply to a save leaves the Profile Share out, so the one already known is kept.
 */
internal fun List<ClientProfile>.withSaved(saved: ClientProfile): List<ClientProfile> {
    val known = firstOrNull { it.id == saved.id }

    return listOf(saved.copy(activeProfileShare = saved.activeProfileShare ?: known?.activeProfileShare)) + filterNot { it.id == saved.id }
}

/**
 * The key of every change to who the Clients of an Appointment are: adding one, removing one and
 * making one primary. They share it, so they run one at a time: each is offered by the Clients the
 * screen shows, and a second one would be decided on Clients that the first is about to change.
 * The second does nothing until the first has been answered and the Appointment loaded again.
 */
internal fun clientsChangeKey(appointmentId: String) = "clients:$appointmentId"

/** The time and the place the server has just answered with, on a loaded copy of that Appointment. */
internal fun Appointment.withTimeAndPlace(saved: UpdatedAppointment): Appointment =
    copy(
        startsAt = saved.startsAt,
        endsAt = saved.endsAt,
        locationType = saved.locationType,
        locationAddress = saved.locationAddress,
        customLocationAddress = saved.customLocationAddress,
        mapUrl = saved.mapUrl,
    )

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
