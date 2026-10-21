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
import com.northphoenix.hairdresserclientmanager.data.AppointmentService
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.data.CameraCaptures
import com.northphoenix.hairdresserclientmanager.data.ClientHistoryEntry
import com.northphoenix.hairdresserclientmanager.data.ClientProfile
import com.northphoenix.hairdresserclientmanager.data.CreateAppointmentResult
import com.northphoenix.hairdresserclientmanager.data.JpegSize
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
import com.northphoenix.hairdresserclientmanager.data.TravelFeeChange
import com.northphoenix.hairdresserclientmanager.data.UpdatedAppointment
import com.northphoenix.hairdresserclientmanager.data.UploadThingClient
import com.northphoenix.hairdresserclientmanager.data.toApiException
import com.northphoenix.hairdresserclientmanager.domain.AppointmentClients
import com.northphoenix.hairdresserclientmanager.domain.AppointmentDraft
import com.northphoenix.hairdresserclientmanager.domain.CalendarWeek
import com.northphoenix.hairdresserclientmanager.domain.ChangedElsewhere
import com.northphoenix.hairdresserclientmanager.domain.HomeOutcomes
import com.northphoenix.hairdresserclientmanager.domain.ImportCandidate
import com.northphoenix.hairdresserclientmanager.domain.MenuItemValues
import com.northphoenix.hairdresserclientmanager.domain.Money
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
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A transient message for the snackbar, resolved to text by the UI in the current app language. [args] fill the text's placeholders.
 * With [quantity], [message] is a `plurals` resource, and the form is chosen by the quantity. [action] is a button on the notice, such as 'Undo'.
 */
data class Notice(
    @StringRes val message: Int,
    val isError: Boolean = false,
    val args: List<Any> = emptyList(),
    val quantity: Int? = null,
    val action: NoticeAction? = null,
)

/** A button on a notice: [label] says what [run] does. It can be tapped for as long as the notice is shown, and at most once. */
data class NoticeAction(@StringRes val label: Int, val run: () -> Unit)

/**
 * How an import of several contacts ended: the contacts that became Clients, and those that did not
 * ([reason] says why the first of them failed, in the words of the app's error messages).
 */
data class ContactImportResult(val added: Set<Long>, val failed: Set<Long>, @StringRes val reason: Int? = null)

/** A Calendar day with what is loaded of it: [appointments] and [load] are always of [day]. The list is empty only when [load] is Loaded. */
data class CalendarDay(val day: LocalDate, val appointments: List<Appointment> = emptyList(), val load: LoadState = LoadState.Loading)

sealed interface BootstrapState {
    data object Loading : BootstrapState

    data class Failed(@StringRes val message: Int) : BootstrapState

    data object Ready : BootstrapState
}

/**
 * Holds the signed-in Stylist's server data and runs every mutation.
 * The app is online-first: after a change we refetch. A copy on screen is only touched with what
 * the server has just confirmed, so the screen is right even when that refetch fails.
 * Everything is read through the read cache (docs/adr/0016; [AppointmentReads], [ClientReads],
 * [ServiceMenuReads]): what was read before is shown at once and refreshed behind it. Only the
 * Stylist's own settings are held here, in [stylist]: they are what the session is, not a read of it.
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

    /**
     * The timezone the Appointment lists are of. Null while there is no Stylist to show them to:
     * signed out, or still in onboarding. Nothing watches the lists then.
     */
    private val listsZone: Flow<ZoneId?> =
        _stylist.map { stylist -> stylist?.takeIf { it.onboardingComplete }?.let { zoneOrSystem(it.timezone) } }.distinctUntilChanged()

    /**
     * The key of what is read of the Stylist as a whole: the Client list and the Service Menu.
     * Null while there is no Stylist to show them to, as for [listsZone].
     */
    private val listsOwner: Flow<StylistKey?> = _stylist.map { ownerKey(it) }.distinctUntilChanged()

    private val day = StylistDay()

    /**
     * Today in the Stylist's timezone. Today's list is of this day and the Calendar starts on it.
     * It changes only in [followDay], together with them.
     */
    val today: StateFlow<LocalDate> = day.today

    /** The changes the server has confirmed, by which a cached answer is told from one that is older than a change (see [Changes]). */
    private val readChanges = Changes()

    /** Every read of Clients goes through this, and so through the read cache (see [ReadCache]). */
    private val clientReads = ClientReads(
        viewModelScope,
        readChanges,
        fetchList = { api.listClients() },
        fetchHistory = { api.clientAppointmentHistory(it.id) },
    )

    private val serviceMenuReads = ServiceMenuReads(
        viewModelScope,
        readChanges,
        fetch = { api.listServiceMenu() },
        fetchFormulaNames = { api.listFormulaNames() },
    )

    // Each list comes with its load state, in one value. A screen says that a list is empty only when its state is Loaded.
    private val _clients = MutableStateFlow(Read<List<ClientProfile>>())

    /** The Client list, as the cache shows it: the Clients screen, every Client picker and the Client Profile read it. */
    val clients: StateFlow<Read<List<ClientProfile>>> = _clients.asStateFlow()

    private val _serviceMenu = MutableStateFlow(Read<List<ServiceMenuItem>>())

    /** The Service Menu, as the cache shows it. */
    val serviceMenu: StateFlow<Read<List<ServiceMenuItem>>> = _serviceMenu.asStateFlow()

    private val _formulaNames = MutableStateFlow(Read<List<String>>())

    /** The names of the Services that have a Color Formula, as the cache shows them: what a Service without a menu item learns from. */
    val formulaNames: StateFlow<Read<List<String>>> = _formulaNames.asStateFlow()

    /** Every read of Appointments goes through this, and so through the read cache (see [ReadCache]). */
    private val appointmentReads = AppointmentReads(
        viewModelScope,
        readChanges,
        fetchToday = { key -> dayRange(key).let { (start, end) -> api.homeAppointments(start, end) } },
        fetchDay = { key -> dayRange(key).let { (start, end) -> api.listAppointments(start, end) } },
        find = { api.findAppointment(it) },
        fetchSources = { api.completedSources(it.appointmentId) },
        fetchWeek = { key ->
            val format = TimeFormat(Locale.ROOT, key.zone)
            api.listAppointments(format.dayRange(key.firstDay).first, format.dayRange(key.firstDay.plusDays(CalendarWeek.LENGTH - 1)).second)
        },
    )

    private val _home = MutableStateFlow(Read<List<Appointment>>())

    /** Today's list, of the day in [today]. It says that it is empty only when its state is Loaded. */
    val home: StateFlow<Read<List<Appointment>>> = _home.asStateFlow()

    private val _tomorrow = MutableStateFlow(Read<List<Appointment>>())

    /**
     * The list of the day after [today] (the Calendar's cache of that day), for the Home View's
     * 'Tomorrow'. It is every Appointment of the day: the screen picks the Scheduled ones.
     */
    val tomorrow: StateFlow<Read<List<Appointment>>> = _tomorrow.asStateFlow()

    private val _calendarDate = MutableStateFlow(day.today.value)
    val calendarDate: StateFlow<LocalDate> = _calendarDate.asStateFlow()

    /** A day other than today that the Stylist has selected, so that it is still selected after Android has killed the app. */
    private val savedCalendarDay = SavedCalendarDay(savedState)

    private val _calendarDay = MutableStateFlow(CalendarDay(_calendarDate.value))

    /**
     * The Calendar's day with what is loaded of it. The screen takes the day from here as well, so
     * that it never shows one day's Appointments under another day. A day that was shown before
     * comes from the cache at once and is refreshed behind it.
     */
    val calendarDay: StateFlow<CalendarDay> = _calendarDay.asStateFlow()

    // The Appointment a copy is being made into, for its copy sheet to say so. Null when no copy request is running.
    private val _copyingInto = MutableStateFlow<String?>(null)
    val copyingInto: StateFlow<String?> = _copyingInto.asStateFlow()

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

    /**
     * True while a change the Stylist waits for is in flight. Screens disable with it what must
     * not be tapped meanwhile. A change that is shown ahead of the server's answer (see [Ahead])
     * does not raise it.
     */
    val busy: StateFlow<Boolean> = actions.busy

    /** True while any change is in flight, also one that is shown ahead of its answer: the thin progress line. Nothing is disabled by it. */
    val saving: StateFlow<Boolean> = actions.saving

    // Values the Stylist can change again while they are still being saved, each kept in the order
    // of the taps (see saveLatest). Each target names the value and what it is of, such as `status:<appointment id>`.
    private val statusChanges = LatestWins<String, AppointmentStatus>()
    private val noteChanges = LatestWins<String, String>()
    private val finalTotalChanges = LatestWins<String, Long?>()
    private val photoCategoryChanges = LatestWins<String, PhotoCategory>()
    private val shareChoiceChanges = LatestWins<String, Boolean>()

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
        viewModelScope.launch { appointmentReads.shown.collect { photos.dropRecorded() } }
        // Today's list and the Calendar's day each watch the key of the moment. Another day or timezone is another key.
        viewModelScope.launch {
            showLatest(
                keys = combine(listsZone, today) { zone, day -> zone?.let { DayKey(day, it) } }.distinctUntilChanged(),
                watch = { key -> key?.let(appointmentReads.today::watch) ?: flowOf(Read()) },
            ) { _, read -> _home.value = read }
        }
        viewModelScope.launch {
            showLatest(
                keys = combine(listsZone, today) { zone, day -> zone?.let { DayKey(day.plusDays(1), it) } }.distinctUntilChanged(),
                watch = { key -> key?.let(appointmentReads.days::watch) ?: flowOf(Read()) },
            ) { _, read -> _tomorrow.value = read }
        }
        viewModelScope.launch {
            showLatest(
                keys = combine(listsZone, _calendarDate) { zone, date -> date to zone }.distinctUntilChanged(),
                watch = { (date, zone) -> zone?.let { appointmentReads.days.watch(DayKey(date, it)) } ?: flowOf(Read()) },
            ) { (date, _), read -> _calendarDay.value = CalendarDay(date, read.data.orEmpty(), read.state) }
        }
        // The Client list and the Service Menu, of the Stylist of the moment.
        viewModelScope.launch {
            showLatest(keys = listsOwner, watch = { key -> key?.let(clientReads.list::watch) ?: flowOf(Read()) }) { _, read -> _clients.value = read }
        }
        viewModelScope.launch {
            showLatest(keys = listsOwner, watch = { key -> key?.let(serviceMenuReads.menu::watch) ?: flowOf(Read()) }) { _, read ->
                _serviceMenu.value = read
            }
        }
        viewModelScope.launch {
            showLatest(keys = listsOwner, watch = { key -> key?.let(serviceMenuReads.formulaNames::watch) ?: flowOf(Read()) }) { _, read ->
                _formulaNames.value = read
            }
        }
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

    private suspend fun clearStylistData() {
        calendarJob?.cancel()
        // Without a Stylist nothing here watches the reads any more (see [listsZone] and [listsOwner]): the lists are loading again.
        _stylist.value = null
        _bootstrap.value = BootstrapState.Loading
        // In this order: from here on no answer of the previous Stylist is shown or cached, also one that is still on its way.
        readChanges.startOver()
        appointmentReads.clear()
        clientReads.clear()
        serviceMenuReads.clear()
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
     * what was loaded or on its way for the day that ended is of another key, and no longer shown.
     */
    private fun followDay(): Boolean {
        val change = day.follow(clock.zone) ?: return false

        // Today's list is of another key from here on (see [todayKey]): the new day has nothing cached and starts as loading.
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

    private suspend fun loadAll() = together({ loadClients() }, ::loadServiceMenu, ::loadAppointmentLists)

    /** The key of the Client list and of the Service Menu: the Stylist. Null while there is no Stylist to show them to. */
    private fun ownerKey(): StylistKey? = ownerKey(_stylist.value)

    private fun ownerKey(stylist: Stylist?): StylistKey? = stylist?.takeIf { it.onboardingComplete }?.let { StylistKey(it.id) }

    /** Asks for the Client list again. The answer goes into the cache and onto the screen; what was on screen stays when it fails. */
    private suspend fun loadClients(): Read<List<ClientProfile>> = ownerKey()?.let { clientReads.list.refresh(it) } ?: Read()

    private suspend fun loadServiceMenu() {
        ownerKey()?.let { key ->
            together({ serviceMenuReads.menu.refresh(key) }, { serviceMenuReads.formulaNames.refresh(key) })
        }
    }

    /**
     * Every load of the lists looks at the clock first, so that none of them loads a day that has
     * ended. The list of a copy sheet that is open is loaded with them, so that a copy the server
     * refused leaves it up to date.
     */
    private suspend fun loadAppointmentLists() {
        followDay()
        together(::loadHome, ::loadTomorrow, ::loadCalendarDay, appointmentReads.weeks::refreshShown, appointmentReads.sources::refreshShown)
    }

    /** The key of Today's list: today in the Stylist's timezone. Null while there is no Stylist to show it to. */
    private fun todayKey(): DayKey? = listsDay(today.value)

    /** The key of the day in [calendarDate]. */
    private fun calendarKey(): DayKey? = listsDay(_calendarDate.value)

    private fun listsDay(day: LocalDate): DayKey? = _stylist.value?.takeIf { it.onboardingComplete }?.let { DayKey(day, zoneOrSystem(it.timezone)) }

    private fun dayRange(key: DayKey): Pair<Instant, Instant> = TimeFormat(Locale.ROOT, key.zone).dayRange(key.day)

    /** Asks for Today's list again. The answer goes into the cache and onto the screen; what was on screen stays when it fails. */
    private suspend fun loadHome() {
        todayKey()?.let { appointmentReads.today.refresh(it) }
    }

    /** Asks for the day after today again, for the Home View's 'Tomorrow'. A failure leaves what was shown. */
    private suspend fun loadTomorrow() {
        listsDay(today.value.plusDays(1))?.let { appointmentReads.days.refresh(it) }
    }

    /** Asks for the day in [calendarDate] again. What arrives after the Stylist has moved on is of another key: cached, and not shown. */
    private suspend fun loadCalendarDay() {
        calendarKey()?.let { appointmentReads.days.refresh(it) }
    }

    /**
     * What the copy sheet of [targetAppointmentId] shows, for as long as it collects this: the
     * Completed Appointments that have something to copy into it, newest first. The list is of
     * that Appointment with those Clients ([clientIds]) and of no other (see [CopyTargetKey]).
     * A list the sheet showed before is there at once and refreshed behind it, unless something
     * was changed since: then it is loaded anew. Nothing is loaded while no copy sheet is open.
     */
    fun copySources(targetAppointmentId: String, clientIds: Collection<String>): Flow<Read<List<Appointment>>> =
        appointmentReads.sources.watch(CopyTargetKey(targetAppointmentId, clientIds.toSet()))

    /** That list as it is without asking, for the first frame of the sheet: loading when it is neither on screen nor cached. */
    fun shownCopySources(targetAppointmentId: String, clientIds: Collection<String>): Read<List<Appointment>> =
        appointmentReads.sources.now(CopyTargetKey(targetAppointmentId, clientIds.toSet()))

    /** Called when the copy sheet opens, and again when the Clients of its Appointment change under it (see [loadShown]). */
    fun loadCopySources(targetAppointmentId: String, clientIds: Collection<String>) =
        loadShown(appointmentReads.sources, CopyTargetKey(targetAppointmentId, clientIds.toSet()))

    /**
     * Asks again for something a screen reads for itself, when that screen comes into view. What
     * is on screen is refreshed behind it, and a refresh nobody asked for fails quietly. Without
     * it the Stylist is waiting: the failure is reported as well as shown in its place.
     */
    private fun <K : Any, V> loadShown(cache: ReadCache<K, V>, key: K) {
        viewModelScope.launch {
            try {
                cache.refresh(key)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (cache.shown(key)?.state != LoadState.Loaded) {
                    report(error.toApiException())
                }
            }
        }
    }

    /** Applies a change the server has just accepted to the copies of an Appointment on screen (see [AppointmentReads.update]). */
    private fun updateLoaded(id: String, change: (Appointment) -> Appointment) = appointmentReads.update(id, change)

    /** Drops an Appointment the server has just deleted from everything on screen, and from the overlap warnings. */
    private fun forgetAppointment(id: String) {
        appointmentReads.forget(id)
        overlaps.gone(id)
    }

    /**
     * Fetches one Appointment again, by its id: its start time can be another one by now. An
     * Appointment the server no longer has counts as gone, and no overlap warning names it any more.
     * Answers the Appointment, null for one that is gone or when a sign-out has overtaken the read.
     */
    private suspend fun reloadAppointment(id: String): Appointment? {
        val read = appointmentReads.one.refresh(AppointmentKey(id))

        if (read.state == LoadState.Loaded && read.data == null) {
            overlaps.gone(id)
        }

        return read.data
    }

    /**
     * The reload after a change to an Appointment. A Client's history that is on screen repeats
     * the Appointment too: it is there when the Stylist has gone back to the Client Profile while
     * the change was still being saved.
     */
    private suspend fun reloadAppointmentAndLists(id: String) =
        together(::loadAppointmentLists, { reloadAppointment(id) }, clientReads.history::refreshShown)

    /**
     * What the screen of one Appointment shows, for as long as it collects this. A copy from the
     * cache or from a list on screen is there at once and refreshed behind it; without one the
     * state says how the load stands: loading, failed, or [LoadState.Missing] for an Appointment
     * the server no longer has.
     */
    fun appointment(id: String): Flow<Read<Appointment?>> = appointmentReads.watch(id)

    /** The Appointment as a screen or the cache has it now, for the first frame of its own: null when neither has it. */
    fun shownAppointment(id: String): Appointment? = appointmentReads[id] ?: appointmentReads.one.now(AppointmentKey(id)).data

    /**
     * Called by the Appointment screen each time it comes into view, and by its 'Try again'.
     * A copy on screen is refreshed behind it; a refresh nobody asked for fails quietly.
     * Without a copy the screen shows how the load went (see [appointment]).
     */
    fun loadAppointment(id: String) {
        viewModelScope.launch {
            val shownStart = appointmentReads[id]?.startsAt

            try {
                val reloaded = reloadAppointment(id)

                if ((reloaded == null || (shownStart != null && reloaded.startsAt != shownStart)) && appointmentReads.isListed(id)) {
                    // Gone, or moved to another time elsewhere: the lists that still show it as it was are loaded again, quietly.
                    actions.reloadWithRetry(::loadAppointmentLists)
                } else {
                    actions.reloadOwed()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (appointmentReads[id] == null) {
                    report(error.toApiException())
                }
            }
        }
    }

    /**
     * Shows [date] in the Calendar. A day that was shown before is there at once, from the cache,
     * and is asked again behind it: when that fails, the day stays and nothing is said. Choosing
     * the day that is already shown asks again as well, which is also the retry after a failed load.
     */
    fun setCalendarDate(date: LocalDate) {
        showCalendarDay(date)

        calendarJob?.cancel()
        calendarJob = viewModelScope.launch {
            try {
                loadCalendarDay()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // A day that could not be loaded says so twice, as any load the Stylist waits for: on the screen and as a notice.
                if (calendarKey()?.let(appointmentReads.days::shown)?.state != LoadState.Loaded) {
                    report(error.toApiException())
                }
            }
        }
    }

    /**
     * The days of the week from [firstDay] on that have an Appointment that is not canceled, for the
     * Calendar's week strip, for as long as this is collected. A week shown before is there at once
     * and is asked again behind it. None are marked while the week is loading or could not be loaded:
     * the list of the selected day says so for itself.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun calendarWeekMarks(firstDay: LocalDate): Flow<Set<LocalDate>> =
        listsZone
            .flatMapLatest { zone ->
                zone?.let { appointmentReads.weeks.watch(WeekKey(firstDay, it)).map { read -> CalendarWeek.daysWithAppointments(read.data.orEmpty(), zone) } }
                    ?: flowOf(emptySet())
            }
            .distinctUntilChanged()

    /** Makes [date] the Calendar's day. It is another key to read (see [calendarKey]): what is on its way for the day before is not shown. */
    private fun showCalendarDay(date: LocalDate) {
        _calendarDate.value = date
        _stylist.value?.let { savedCalendarDay.keep(it.id, date.takeIf { selected -> selected != today.value }) }
    }

    // --- mutation plumbing ---

    /**
     * Runs one change by the rules of [ActionRunner]. [key] names the action and what it acts on;
     * a second call with the same key while the first is running does nothing.
     * [change] is only the request that makes the change, plus what is certain once it has succeeded.
     * Whatever refreshes the screen afterwards goes in [reload], so that a refresh that fails is
     * never taken for a change that failed.
     *
     * With [ahead] the Stylist does not wait for the change: the screen shows it at once and
     * nothing is disabled while it is saved (see [Ahead]). Only for a change whose result is known
     * exactly before the server answers, and that nothing on the screen has to wait for.
     */
    private fun <T> act(
        key: String?,
        @StringRes successNotice: Int? = null,
        onSuccess: (T) -> Unit = {},
        reload: suspend () -> Unit = {},
        touched: () -> Unit = {},
        ahead: Ahead? = null,
        change: suspend () -> T,
    ) = actions.run(key, { marking(touched, change) }, reload, ahead = ahead) { result ->
        successNotice?.let { notify(it) }
        onSuccess(result)
    }

    /**
     * Runs [change] and tells the read cache what it has made of the cached answers. [touched]
     * marks in [readChanges] what the change touches: once it is saved, and also when its outcome
     * is unknown, because it may have been saved. No answer from before the mark is shown or
     * served again (see [Changes]); [reload] of the action then asks for what is on screen.
     * A change of something that reads show needs a [touched]; one that is refused because the
     * server no longer has what the screen shows, or has it otherwise, says that the cached
     * answers are out of date too, all of them.
     */
    private suspend fun <T> marking(touched: () -> Unit, change: suspend () -> T): T =
        try {
            change().also { touched() }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val failure = error.toApiException()

            when {
                failure.outcomeUnknown -> touched()
                failure.isNotFound || failure.screenOutOfDate -> readChanges.changedAnywhere()
            }
            throw error
        }

    /**
     * A change to one Appointment. [touched] is that Appointment unless the change can show elsewhere.
     *
     * With [ahead] the Stylist does not wait for it (see [act]): [ahead] is what the change makes
     * of the Appointment, such as the Appointment without the Service that is being deleted. It is
     * on every screen at once, and is what the loaded copies have once the server has accepted it.
     */
    private fun actOnAppointment(
        id: String,
        key: String,
        @StringRes successNotice: Int? = null,
        onSuccess: () -> Unit = {},
        touched: () -> Unit = { readChanges.changed(id) },
        ahead: ((Appointment) -> Appointment)? = null,
        reload: suspend () -> Unit = { reloadAppointmentAndLists(id) },
        change: suspend () -> Unit,
    ) = act(
        key,
        successNotice,
        onSuccess = { onSuccess() },
        reload = reload,
        touched = touched,
        ahead = ahead?.let { appointmentReads.ahead(key, id, it) },
        change = change,
    )

    /**
     * Saves a value of an Appointment that the Stylist can change again while it is still being
     * saved, such as a status chip, without making them wait (see [ActionRunner.saveLatest]):
     * every tap is on screen at once, requests for one [target] go out one at a time, and the
     * value tapped last is the one saved last. [saved] is the value on screen.
     *
     * [edit] is what a value makes of the Appointment. It is shown ahead of the answer on every
     * screen that has the Appointment, and put into the loaded copies when [save], the request,
     * has worked: so [saved] is right for the next tap before the reload. [elsewhere] is for what
     * the value changes in another kind of read.
     * When a request fails the screen is back on the value the server has, and the reload still
     * shows what an earlier tap saved.
     */
    private fun <V> saveLatest(
        changes: LatestWins<String, V>,
        appointmentId: String,
        target: String,
        value: V,
        saved: V?,
        @StringRes successNotice: Int? = null,
        touched: () -> Unit = { readChanges.changed(appointmentId) },
        elsewhere: (V) -> Ahead? = { null },
        edit: (Appointment, V) -> Appointment,
        save: suspend (V) -> Unit,
    ) = actions.saveLatest(
        changes,
        target,
        value,
        saved,
        ahead = { next ->
            val shown = appointmentReads.ahead(target, appointmentId) { edit(it, next) }

            elsewhere(next)?.let { shown + it } ?: shown
        },
        reload = { reloadAppointmentAndLists(appointmentId) },
        onSaved = { successNotice?.let { notify(it) } },
    ) { next ->
        // Marked after each request: a read that was sent before one of them was saved may not show it.
        marking(touched) { save(next) }
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
            // The salon's address is the address of every Appointment at the salon.
            touched = readChanges::changedAnywhere,
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
        referredById: String? = null,
        onSuccess: (ClientProfile) -> Unit,
    ) {
        act(
            key = "saveClient:${id ?: "new"}",
            successNotice = R.string.client_saved,
            onSuccess = onSuccess,
            // The Client's history names them too: the one on screen is under the form that was just saved.
            reload = { together({ loadClients() }, { if (id != null) together(::loadAppointmentLists, clientReads.history::refreshShown) }) },
            touched = { clientReads.changed(id) },
        ) {
            api.saveClient(
                id, OneLine.clean(name), language, OneLine.clean(phone), email.trim(), address.trim(), note.trim(), loadedUpdatedAt, requestKey, referredById,
            ).also { saved ->
                // In the list and in every picker now, before the reload.
                clientReads.saved(saved)
                // Names and phone numbers shown on Appointments come from the Client: every copy on screen gets
                // them now, and no cached day is served as it was before the save, so no reminder goes to the old number.
                appointmentReads.clientSaved(saved)
            }
        }
    }

    private val _contactImportProgress = MutableStateFlow<Pair<Int, Int>?>(null)

    /** While contacts are being imported: how many are done, and how many there are. Null when no import is running. */
    val contactImportProgress: StateFlow<Pair<Int, Int>?> = _contactImportProgress.asStateFlow()

    // One key per contact as it is imported, kept until it was added: the contacts that failed are sent again with the
    // key they had, so a retry whose first try was saved but never answered makes no second Client (ADR 0015).
    private val contactImportKeys = mutableMapOf<ImportCandidate, String>()

    /**
     * Makes a Client of each of [contacts], one after the other, each with the contact's name, first number,
     * address and note, in the Stylist's language, by the same save as the Client form. It goes on after a Client
     * the server refuses, and stops at the first failure to reach the server: the contacts not reached are failed too.
     * It runs on until it is done also when the Stylist leaves the screen; [onFinished] is told then and
     * shows one notice, of what was added. A retry of the contacts an earlier try left undone counts
     * the [addedBefore] Clients that try added into that notice: it is of the whole import.
     */
    fun importContacts(contacts: List<ImportCandidate>, addedBefore: Int = 0, onFinished: (ContactImportResult) -> Unit) {
        if (contacts.isEmpty() || _contactImportProgress.value != null) {
            return
        }

        val language = _stylist.value?.language ?: Language.RU

        _contactImportProgress.value = 0 to contacts.size
        act(
            key = "importContacts",
            onSuccess = onFinished,
            reload = { loadClients() },
            touched = { clientReads.changed(null) },
        ) {
            val added = mutableSetOf<Long>()
            val failed = mutableSetOf<Long>()
            var reason: Int? = null
            var unreachable = false

            try {
                for (contact in contacts) {
                    if (unreachable) {
                        failed += contact.id
                        continue
                    }

                    try {
                        val key = contactImportKeys.getOrPut(contact) { UUID.randomUUID().toString() }
                        // The Client Note is the contact's, and the form's other fields stay empty.
                        clientReads.saved(api.saveClient(null, contact.name, language, contact.phone, "", contact.address, contact.note, null, key))
                        contactImportKeys.remove(contact)
                        added += contact.id
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        val failure = error.toApiException()

                        failed += contact.id
                        reason = reason ?: failure.messageRes()
                        if (failure.isUnauthorized) {
                            report(failure)
                        }
                        unreachable = failure.isNetwork || failure.outcomeUnknown || failure.isUnauthorized
                    } finally {
                        _contactImportProgress.value = (added.size + failed.size) to contacts.size
                    }
                }
            } finally {
                _contactImportProgress.value = null
            }

            val result = ContactImportResult(added, failed, reason)
            val addedInAll = addedBefore + added.size

            when {
                failed.isEmpty() -> noticeChannel.trySend(Notice(R.plurals.contact_import_added, quantity = addedInAll, args = listOf(addedInAll)))
                added.isEmpty() -> notify(reason ?: R.string.contact_import_failed, isError = true)
                else -> notify(R.string.contact_import_partial, isError = true, addedInAll, addedBefore + contacts.size)
            }
            result
        }
    }

    /**
     * Deletes a Client with everything that is theirs. The server does all of it or nothing, also
     * for a Client whom an Appointment names as primary without listing them.
     */
    fun deleteClient(id: String, onSuccess: () -> Unit) {
        // The loaded Appointments this Client is in or is the primary Client of: they change or go with them, on whatever
        // day they are. Named before the request, so that they are loaded again also when its reply never comes.
        // Those that are not on screen are of cached answers that name the Client, none of which is served again.
        val affected = AppointmentClients.changedByDeleting(id, appointmentReads.all())

        act(
            key = "deleteClient:$id",
            successNotice = R.string.client_deleted,
            onSuccess = { onSuccess() },
            reload = { together(::loadAll, { affected.forEach { reloadAppointment(it.id) } }) },
            touched = { clientReads.changed(id) },
        ) {
            api.deleteClient(id)
            // Gone from the list and from every picker now. No cached answer that names them is served again.
            clientReads.deleted(id)
            photos.removeAll { it.clientId == id }
            overlaps.clientDeleted(id)
            // An Appointment left without Clients is deleted with its last one. The reload checks each of them again.
            affected.filter { AppointmentClients.goesWith(id, it) }.forEach { forgetAppointment(it.id) }
        }
    }

    /**
     * Makes two Client Profiles of one person one: [removeId]'s Appointments, Services and photos go to
     * [keepId], and [removeId] is deleted. The server does all of it or nothing, and the screens change only
     * once it has answered: a merge that could not be sent changes nothing, and one whose answer never came is
     * reloaded. [onSuccess] gets the kept Client.
     */
    fun mergeClients(keepId: String, removeId: String, onSuccess: (ClientProfile) -> Unit) {
        // A photo on its way to the server would arrive for a Client who is gone.
        if (photos.uploads.value.any { it.photo.clientId == removeId }) {
            notify(R.string.client_merge_photo_uploads, isError = true)
            return
        }

        // The loaded Appointments of the removed Client, named before the request as for a delete.
        val affected = AppointmentClients.changedByDeleting(removeId, appointmentReads.all())

        act(
            key = "mergeClients",
            successNotice = R.string.client_merged,
            onSuccess = onSuccess,
            reload = { together(::loadAll, { affected.forEach { reloadAppointment(it.id) } }, clientReads.history::refreshShown) },
            touched = {
                clientReads.changed(keepId)
                clientReads.changed(removeId)
            },
        ) {
            val merged = api.mergeClients(keepId, removeId)

            clientReads.referralsMoved(removeId, merged.client)
            clientReads.deleted(removeId)
            clientReads.saved(merged.client)
            appointmentReads.clientSaved(merged.client)
            overlaps.clientDeleted(removeId)
            merged.client
        }
    }

    /** The two Clients are different people: neither is offered as the other's duplicate any more. */
    fun markClientsDistinct(clientId: String, otherClientId: String) =
        act("markDistinct:$clientId:$otherClientId", reload = { loadClients() }, touched = { clientReads.changed(clientId) }) {
            api.markClientsDistinct(clientId, otherClientId)
            clientReads.distinct(clientId, otherClientId)
        }

    // Creating and revoking one Client's Profile Share share a key, so they run one at a time and a
    // second tap on Create does nothing. A choice of the Profile Share that exists is not waited
    // for (see setProfileShareChoice). The runner sends a Revoke, or the request for a Share Image,
    // only after the choices made before it have been answered: none is sent for a Profile Share
    // that is being revoked, and the picture shows what the link shows.
    // What the server answers goes into the list at once, so the card is right before the reload.

    /** [included] is what the new Profile Share shows. A Client who has one already keeps it as it is, and the card shows that one. */
    fun createProfileShare(clientId: String, included: Set<ShareChoice>) =
        act(profileShareKey(clientId), reload = { loadClients() }, touched = clientReads::shareChanged) {
            clientReads.profileShare(clientId, api.createProfileShare(clientId, included))
        }

    /**
     * Changes one choice of the Client's Profile Share. Its link stays the same. The switch moves
     * at once and can be tapped again while it is saved: the last tap is what stays (see [saveLatest]).
     */
    fun setProfileShareChoice(clientId: String, choice: ShareChoice, included: Boolean) {
        val share = _clients.value.data?.firstOrNull { it.id == clientId }?.activeProfileShare ?: return
        val target = "shareChoice:$clientId:${choice.name}"

        actions.saveLatest(
            shareChoiceChanges,
            target,
            included,
            saved = choice in share.included,
            ahead = { next -> clientReads.shareChoiceAhead(target, clientId, choice, next) },
            reload = { loadClients() },
        ) { next ->
            // The reply states the whole Profile Share. The requests go out one at a time, so it never undoes a choice saved after it.
            marking(clientReads::shareChanged) { clientReads.profileShare(clientId, api.updateProfileShare(clientId, choice, next)) }
        }
    }

    fun revokeProfileShare(clientId: String) =
        act(profileShareKey(clientId), successNotice = R.string.profile_share_revoked, reload = { loadClients() }, touched = clientReads::shareChanged) {
            api.revokeProfileShare(clientId)
            clientReads.profileShare(clientId, null)
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
            val list = loadClients()

            when {
                // A sign-out has overtaken the read: there is no list to look in, and this profile is on its way out.
                list.state != LoadState.Loaded -> LoadState.Loading
                list.data.orEmpty().any { it.id == id } -> LoadState.Loaded
                else -> LoadState.Missing
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val failure = error.toApiException()

            report(failure)
            LoadState.Failed(failure.messageRes())
        }

    /**
     * A Client's Appointment history, for as long as the Client Profile collects this. A history
     * that was shown before is there at once and refreshed behind it, unless something was changed
     * since: then it is loaded anew. Without one the state says how the load stands, and a failure
     * is shown in the history's place.
     */
    fun clientHistory(clientId: String): Flow<Read<List<ClientHistoryEntry>>> = clientReads.history.watch(ClientKey(clientId))

    /** That history as it is without asking, for the first frame of the profile: loading when it is neither on screen nor cached. */
    fun shownClientHistory(clientId: String): Read<List<ClientHistoryEntry>> = clientReads.history.now(ClientKey(clientId))

    /** Called by the Client Profile each time it comes into view, and by 'Try again' under its history (see [loadShown]). */
    fun loadClientHistory(clientId: String) = loadShown(clientReads.history, ClientKey(clientId))

    // --- service menu ---

    /** [requestKey] is the editor sheet's own (see `rememberRequestKey`): it counts for a new Service Menu Item. */
    fun saveServiceMenuItem(id: String?, values: MenuItemValues, requestKey: String, onSuccess: () -> Unit) =
        act(
            key = "saveServiceMenuItem:${id ?: "new"}",
            successNotice = R.string.service_menu_item_saved,
            onSuccess = { onSuccess() },
            reload = ::loadServiceMenu,
            touched = serviceMenuReads::changed,
        ) {
            serviceMenuReads.saved(api.saveServiceMenuItem(id, values, requestKey))
        }

    /** Not waited for: the item leaves the menu at once, and comes back in its place when the delete fails. */
    fun deleteServiceMenuItem(id: String) {
        val key = "deleteServiceMenuItem:$id"

        act(
            key = key,
            successNotice = R.string.service_menu_item_deleted,
            reload = ::loadServiceMenu,
            touched = serviceMenuReads::changed,
            ahead = serviceMenuReads.deletedAhead(key, id),
        ) {
            api.deleteServiceMenuItem(id)
        }
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
            // Its screen opens next, before any read of it has answered: it shows the reply until then.
            appointmentReads.created(result.appointment)
            // A Travel Fee the server added is said instead, with a way to remove it: the overlap is named on the Appointment's screen.
            if (!notifyTravelFee(result.appointment.id, result.travelFee)) {
                notify(if (result.conflicts.isEmpty()) R.string.appointment_saved else R.string.appointment_conflict_warning)
            }
            onSuccess(result)
        },
        // An inline Client is created on the server as part of this call.
        reload = { together(::loadAppointmentLists, { if (primaryClientId == null) loadClients() }) },
        // A new Appointment is in the list of the day it starts on and may be in Today's. No other cached day has anything of it.
        touched = {
            appointmentReads.startChanged(null, startsAt)
            // An inline Client is new in the Client list.
            if (primaryClientId == null) {
                clientReads.changed(null)
            }
        },
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
            // The notice only says that there is an overlap. The Appointment's own screen names it, until dismissed.
            overlaps.answered(it.appointment.id, it.conflicts)
        }
    }

    /**
     * The chip that was tapped is the status at once, on the Appointment's screen, on its cards and
     * in a Client's history. Status taps are saved one at a time in the order they were made, so
     * the last tap is the status that stays.
     * A status that goes back to Scheduled can land on a time that is taken: the answer says by which Appointments.
     */
    fun setAppointmentStatus(id: String, status: AppointmentStatus) {
        val target = "status:$id"

        saveLatest(
            statusChanges,
            id,
            target,
            status,
            saved = appointmentReads[id]?.status,
            touched = { appointmentReads.statusChanged(id) },
            elsewhere = { next -> clientReads.statusAhead(target, id, next) + backOnHome(target, id, next) },
            edit = { appointment, next -> appointment.copy(status = next) },
        ) { next ->
            val saved = api.updateAppointmentStatus(id, next)

            updateLoaded(id) { it.copy(status = next).withTimeAndPlace(saved) }
            overlaps.answered(id, saved.conflicts)
            // The Home View names no overlap of its own: the notice points to the Appointment's screen, which does.
            if (next == AppointmentStatus.SCHEDULED && id in homeUndos && saved.conflicts.isNotEmpty()) {
                notify(R.string.home_undo_overlap)
            }
        }
    }

    // --- quick outcomes on the Home View ---

    /**
     * The cards that 'Undo' is setting back to Scheduled, by Appointment, as the Home View showed
     * them. Kept here and not in the call, because a status tap joins whatever request for the
     * Appointment is already on its way, and that request's code is the one that runs (see [saveLatest]).
     */
    private val homeUndos = mutableMapOf<String, Appointment>()

    /**
     * An Appointment that left Today's list with an outcome is back on it the moment 'Undo' sets it
     * to Scheduled, not only once the reload has it (see [AppointmentReads.backOnToday]). Taken away
     * when the status requests for it have ended, whichever way.
     */
    private fun backOnHome(target: String, id: String, next: AppointmentStatus): Ahead {
        val change = "$target:backOnHome"
        val back = homeUndos[id]?.takeIf { next == AppointmentStatus.SCHEDULED }?.let { appointmentReads.backOnToday(change, it) }

        return Ahead(
            show = { back?.show() },
            saved = { back?.saved() },
            settle = {
                appointmentReads.today.settled(change)
                homeUndos -= id
            },
        )
    }

    /**
     * An outcome chip on a Home card: [outcome] is the Appointment's status at once (see
     * [setAppointmentStatus]), and a notice says so with 'Undo', which sets it back to Scheduled.
     * [card] is the Appointment as the card shows it.
     */
    fun setOutcomeFromHome(card: Appointment, outcome: AppointmentStatus) {
        setAppointmentStatus(card.id, outcome)
        noticeChannel.trySend(
            Notice(
                outcome.homeNotice(),
                args = listOf(AppointmentClients.leadName(card)),
                action = NoticeAction(R.string.notice_undo) { undoHomeOutcomes(listOf(card), outcome) },
            ),
        )
    }

    /**
     * 'All completed' for one day of the Home View: each of [cards], the day's unresolved
     * Appointments, is Completed at once, each by its own status change. One notice says how many,
     * and its 'Undo' sets all of them back to Scheduled.
     */
    fun completeAllFromHome(cards: List<Appointment>) {
        if (cards.isEmpty()) {
            return
        }
        cards.forEach { setAppointmentStatus(it.id, AppointmentStatus.COMPLETED) }
        noticeChannel.trySend(
            Notice(
                R.plurals.home_completed_count,
                args = listOf(cards.size),
                quantity = cards.size,
                action = NoticeAction(R.string.notice_undo) { undoHomeOutcomes(cards, AppointmentStatus.COMPLETED) },
            ),
        )
    }

    /**
     * 'Undo' of an outcome set from the Home View: each of [cards] that still has [outcome] goes
     * back to Scheduled, by the rules of any status change (an overlap is named on its screen).
     * One that has another outcome by now, set elsewhere, keeps it, and the Stylist is told.
     */
    private fun undoHomeOutcomes(cards: List<Appointment>, outcome: AppointmentStatus) {
        var changedElsewhere = false

        cards.forEach { card ->
            when (HomeOutcomes.undo(shownAppointment(card.id)?.status, outcome)) {
                HomeOutcomes.Undo.REVERT -> {
                    homeUndos[card.id] = card
                    setAppointmentStatus(card.id, AppointmentStatus.SCHEDULED)
                }
                HomeOutcomes.Undo.NOTHING -> Unit
                HomeOutcomes.Undo.CHANGED_ELSEWHERE -> changedElsewhere = true
            }
        }
        if (changedElsewhere) {
            notify(R.string.home_undo_changed_elsewhere)
        }
    }

    /**
     * Edit Appointment: saves the day, the times and the place of an Appointment. [times] is null
     * when the Stylist left them as they were, and they are then not sent; so is [locationType] for
     * a place that is kept ([AppointmentDraft.keepsPlace]). [ownAddress] is the at-home address the
     * Stylist typed, null for the primary Client's.
     * Another place can add or remove the Travel Fee: the notice then says that, with a way to undo it.
     */
    fun editAppointment(
        id: String,
        times: AppointmentDraft.Times.Valid?,
        locationType: LocationType?,
        ownAddress: String?,
        onSuccess: () -> Unit,
    ) {
        var travelFee = TravelFeeChange()

        actOnAppointment(
            id,
            "editAppointment:$id",
            onSuccess = {
                if (!notifyTravelFee(id, travelFee)) {
                    notify(R.string.appointment_time_and_place_saved)
                }
                onSuccess()
            },
            // At another time the Appointment is in the list of the day it starts on now, which does not hold it yet.
            touched = { if (times != null) appointmentReads.startChanged(id, times.startsAt) else readChanges.changed(id) },
        ) {
            val saved = api.updateAppointmentTimeAndPlace(id, times?.startsAt, times?.endsAt, locationType, ownAddress?.trim().orEmpty())

            travelFee = saved.travelFee
            updateLoaded(id) { it.withTimeAndPlace(saved).withTravelFee(saved.travelFee) }
            // An answer to times that were not sent names no overlap, and says nothing about the one there may be.
            if (times != null) {
                overlaps.answered(id, saved.conflicts)
            }
        }
    }

    /**
     * Says what the server did with the Travel Fee of an Appointment that was created or changed
     * place, with a button that undoes it: 'Remove' deletes the Services it added, and the
     * Appointment stays where it is; 'Put back' adds the ones it removed again, as they were.
     * Neither moves the Appointment, so the server does not add or remove the fee again until it
     * changes place. False when the server did nothing with it, and nothing was said.
     */
    private fun notifyTravelFee(appointmentId: String, change: TravelFeeChange): Boolean {
        val added = change.added.firstOrNull()
        val removed = change.removed.firstOrNull()

        when {
            added != null -> noticeChannel.trySend(
                Notice(
                    R.string.travel_fee_added,
                    args = listOf(added.name, Money.formatShort(change.added.sumOf { it.priceCents })),
                    action = NoticeAction(R.string.travel_fee_remove) { change.added.forEach { deleteService(appointmentId, it.id) } },
                ),
            )
            removed != null -> noticeChannel.trySend(
                Notice(
                    R.string.travel_fee_removed,
                    args = listOf(removed.name),
                    action = NoticeAction(R.string.travel_fee_put_back) { putBackServices(appointmentId, change.removed) },
                ),
            )
            else -> return false
        }
        return true
    }

    /** Adds [services] to the Appointment again, for the same Clients, with the same names, prices and notes. */
    private fun putBackServices(appointmentId: String, services: List<AppointmentService>) =
        actOnAppointment(appointmentId, "addService:$appointmentId") {
            services.forEach { service ->
                // A new creation each: this tap is the only one that sends it.
                api.addService(appointmentId, service.clientId, service.menuItemId, service.name, service.priceCents, service.note, UUID.randomUUID().toString())
            }
        }

    fun dismissOverlapWarning(appointmentId: String) = overlaps.dismiss(appointmentId)

    // Not put into the loaded copy: the Appointment's address can change with its primary Client, and only the reload shows to what.
    fun setAppointmentPrimary(id: String, clientId: String) =
        actOnAppointment(id, clientsChangeKey(id)) { api.updateAppointmentPrimary(id, clientId) }

    /** The note is the Appointment's at once. When it cannot be saved, the Appointment has the note before it again, and its screen still has the text. */
    fun saveAppointmentNote(id: String, note: String) =
        saveLatest(
            noteChanges,
            id,
            "note:$id",
            note.trim(),
            saved = appointmentReads[id]?.note,
            R.string.appointment_note_saved,
            edit = { appointment, next -> appointment.copy(note = next) },
        ) { next -> api.updateAppointmentNote(id, next) }

    /** [overrideCents] null clears the override, and the final total is the Service total again. Either shows at once. */
    fun setAppointmentFinalTotal(id: String, overrideCents: Long?) =
        saveLatest(
            finalTotalChanges,
            id,
            "finalTotal:$id",
            overrideCents,
            saved = appointmentReads[id]?.finalTotalCentsOverride,
            edit = { appointment, next -> appointment.copy(finalTotalCentsOverride = next, finalTotalCents = next ?: appointment.serviceTotalCents) },
        ) { next -> api.updateAppointmentFinalTotal(id, next) }

    fun deleteAppointment(id: String, onSuccess: () -> Unit) =
        actOnAppointment(id, "deleteAppointment:$id", onSuccess = onSuccess) {
            api.deleteAppointment(id)
            forgetAppointment(id)
            photos.removeAll { it.appointmentId == id }
        }

    fun addParticipant(appointmentId: String, clientId: String, onSuccess: () -> Unit = {}) =
        actOnAppointment(appointmentId, clientsChangeKey(appointmentId), onSuccess = onSuccess) { api.addParticipant(appointmentId, clientId) }

    /**
     * Adds a new Client to an Appointment, a minimal one made from just [name], as Appointment
     * Creation makes one inline: in the Stylist's language and with nothing else filled in.
     * [onSuccess] gets the new Client's id.
     *
     * Two requests, each safe to send again: the Client is created under [requestKey], which the
     * caller keeps for as long as it retries this one, and adding a Client who is in the Appointment
     * already adds nobody. So when the Client was created and the add failed, the next try finds
     * that Client and adds them, and no second Client of the same name is made.
     */
    fun addNewParticipant(appointmentId: String, name: String, requestKey: String, onSuccess: (String) -> Unit) {
        var created = false

        actions.run(
            key = clientsChangeKey(appointmentId),
            change = {
                marking({
                    readChanges.changed(appointmentId)
                    clientReads.changed(null)
                }) {
                    val client = api.saveClient(null, OneLine.clean(name), stylist.value?.language ?: Language.RU, "", "", "", "", null, requestKey)

                    // In the Client list and in every picker now, before the reload.
                    clientReads.saved(client)
                    created = true
                    api.addParticipant(appointmentId, client.id)
                    client.id
                }
            },
            reload = { together({ reloadAppointmentAndLists(appointmentId) }, { loadClients() }) },
            // A Client who was created and not added is in the Clients list all the same.
            savedInPart = { created },
            onSaved = onSuccess,
        )
    }

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
     * Adds a Service from each picked menu item for each Client of [picks], at their default prices,
     * Client after Client and each one's in the order picked, in one request. [requestKey] is the
     * sheet's own for its picks from the menu. The menu is read again too: how often its items were
     * used has changed.
     */
    fun addMenuServices(appointmentId: String, picks: List<Pair<String, List<String>>>, requestKey: String, onSuccess: () -> Unit) =
        act(
            key = "addService:$appointmentId",
            onSuccess = { _: Unit ->
                notify(R.string.appointment_services_added, isError = false, picks.sumOf { it.second.size })
                onSuccess()
            },
            reload = { together({ reloadAppointmentAndLists(appointmentId) }, ::loadServiceMenu) },
            touched = {
                readChanges.changed(appointmentId)
                serviceMenuReads.changed()
            },
        ) {
            api.addMenuServices(appointmentId, picks, requestKey)
        }

    /**
     * Saves an edit of a Service: its name, its price and its note, all three as the form has
     * them (see [ServiceEdit]). Its Client, its Color Formulas and its place among the Services stay.
     */
    fun updateService(appointmentId: String, serviceId: String, edit: ServiceEdit.Values, onSuccess: () -> Unit) =
        actOnAppointment(appointmentId, "updateService:$serviceId", R.string.appointment_service_updated, onSuccess) {
            api.updateService(serviceId, edit.name, edit.priceCents, edit.note)
        }

    // A delete the Stylist has confirmed is not waited for: the item is gone at once, with what
    // follows from it (the totals), and is back in its place when the delete fails.

    fun deleteService(appointmentId: String, serviceId: String) =
        actOnAppointment(appointmentId, "deleteService:$serviceId", ahead = { it.withoutService(serviceId) }) { api.deleteService(serviceId) }

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
            touched = { readChanges.changed(targetAppointmentId) },
        ) {
            _copyingInto.value = targetAppointmentId
            try {
                api.copyServices(targetAppointmentId, sourceAppointmentId, requestKey)
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
    ) = actOnAppointment(
        appointmentId,
        "saveColorFormula:${formulaId ?: serviceId}",
        onSuccess = onSuccess,
        touched = { formulaChanged(appointmentId) },
        reload = { formulaReloaded(appointmentId) },
    ) {
        if (formulaId == null) {
            api.addColorFormula(serviceId, formula.trim(), placement.trim(), requestKey)
        } else {
            api.updateColorFormula(formulaId, formula.trim(), placement.trim())
        }
    }

    fun deleteColorFormula(appointmentId: String, formulaId: String) =
        actOnAppointment(
            appointmentId,
            "deleteColorFormula:$formulaId",
            touched = { formulaChanged(appointmentId) },
            ahead = { it.withoutColorFormula(formulaId) },
            reload = { formulaReloaded(appointmentId) },
        ) {
            api.deleteColorFormula(formulaId)
        }

    /**
     * A Color Formula was added, edited or deleted: besides the Appointment, the menu's count of
     * Services with a formula and the names of such Services are out of date, and the next
     * Service the Stylist adds learns from them (see [ColorFormulaOffer]).
     */
    private fun formulaChanged(appointmentId: String) {
        readChanges.changed(appointmentId)
        serviceMenuReads.changed()
    }

    private suspend fun formulaReloaded(appointmentId: String) = together({ reloadAppointmentAndLists(appointmentId) }, ::loadServiceMenu)

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

        // The size of what was uploaded, so that the photo is recorded with it.
        val size = JpegSize.of(bytes)

        return StoredFile(
            fileKey = serverData?.fileKey ?: uploaded.key,
            url = url,
            thumbnailUrl = serverData?.thumbnailUrl ?: url,
            width = size?.width,
            height = size?.height,
        )
    }

    private suspend fun recordPhoto(photo: PendingPhoto, stored: StoredFile) {
        // Such a server takes no stored file as a photo either.
        requirePhotoStorage()

        // The photo's id is its request key: recorded once, however often this is sent.
        val touched = { readChanges.changed(photo.appointmentId) }
        val photosNow = marking(touched) {
            api.addStoredPhoto(photo.appointmentId, photo.clientId, photo.category, stored, requestKey = photo.id)
        }

        if (photosNow?.hasStored(stored) == true) {
            // The reply states the Appointment's photos with this one among them: it is shown at once, also when the reload after it fails.
            updateLoaded(photo.appointmentId) { it.copy(photos = photosNow) }
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
            } finally {
                // Removed or not, known or not: no answer from before is taken for the Appointment as it is now.
                touched()
            }
        }
    }

    /**
     * The Appointment of a photo that has reached the server, or may have, is loaded again. One
     * that is on no screen is left alone: no cached answer from before the photo is served again
     * (see [recordPhoto]), so it comes with its photos when the Stylist opens it. An Appointment
     * whose screen is open behind the camera or the photo picker is on screen: that screen goes
     * on reading (`collectWhileOpen`), so the photo is put into it and it is loaded again here.
     */
    private suspend fun reloadForPhoto(photo: PendingPhoto, saved: Boolean) =
        actions.reloadAfterChange(saved) {
            if (appointmentReads[photo.appointmentId] != null) {
                reloadAppointmentAndLists(photo.appointmentId)
            }
        }

    /** Whether the Appointment, as it is loaded now, has the photo of this picture: the server recorded it, also when its reply never came. */
    private fun isPhotoRecorded(photo: PendingPhoto): Boolean {
        val stored = photo.stored ?: return false

        return appointmentReads[photo.appointmentId]?.photos?.hasStored(stored) == true
    }

    /** 'Upload again' for one photo that failed to upload. */
    fun retryPhotoUpload(uploadId: String) = photos.retry(uploadId)

    /** 'Upload again' for every photo of this Client in this Appointment that failed to upload. */
    fun retryPhotoUploads(appointmentId: String, clientId: String) =
        photos.retryAll { it.appointmentId == appointmentId && it.clientId == clientId }

    /** 'Remove photo' for a photo that is not on the server yet: it is deleted from the device and never sent. */
    fun removePhotoUpload(uploadId: String) = photos.remove(uploadId)

    /** The photo is in the category that was tapped at once. Taps are saved in their order, so the last one stays. */
    fun setPhotoCategory(appointmentId: String, photoId: String, category: PhotoCategory) =
        saveLatest(
            photoCategoryChanges,
            appointmentId,
            "photoCategory:$photoId",
            category,
            saved = appointmentReads[appointmentId]?.photos?.firstOrNull { it.id == photoId }?.category,
            edit = { appointment, next -> appointment.copy(photos = appointment.photos.map { if (it.id == photoId) it.copy(category = next) else it }) },
        ) { next -> api.updatePhotoCategory(photoId, next) }

    /** Not waited for, as the other confirmed deletes (see [deleteService]). */
    fun deletePhoto(appointmentId: String, photoId: String) =
        actOnAppointment(appointmentId, "deletePhoto:$photoId", ahead = { it.withoutPhoto(photoId) }) { api.deletePhoto(photoId) }

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

/** What the notice says after an outcome chip on a Home card set [this]. */
@StringRes
internal fun AppointmentStatus.homeNotice(): Int =
    when (this) {
        AppointmentStatus.COMPLETED -> R.string.home_outcome_completed
        AppointmentStatus.CANCELED -> R.string.home_outcome_canceled
        AppointmentStatus.NO_SHOW -> R.string.home_outcome_no_show
        // No chip sets it; named for completeness.
        AppointmentStatus.SCHEDULED -> R.string.appointment_status_scheduled
    }

internal fun profileShareKey(clientId: String) = "profileShare:$clientId"

/** The Client list with what the server has just answered about one Client's Profile Share: [share], or none after a revoke. */
internal fun List<ClientProfile>.withProfileShare(clientId: String, share: ProfileShare?): List<ClientProfile> =
    map { if (it.id == clientId) it.copy(activeProfileShare = share) else it }

/**
 * The Client list with a just-saved Client in it, first as on the server (latest change first).
 * The reply to a save leaves the Profile Share and whom the Client is not out, so what is already known is kept.
 */
internal fun List<ClientProfile>.withSaved(saved: ClientProfile): List<ClientProfile> {
    val known = firstOrNull { it.id == saved.id }

    return listOf(
        saved.copy(
            activeProfileShare = saved.activeProfileShare ?: known?.activeProfileShare,
            distinctFromIds = saved.distinctFromIds ?: known?.distinctFromIds,
        ),
    ) + filterNot { it.id == saved.id }
}

/**
 * The key of every change to who the Clients of an Appointment are: adding one, removing one and
 * making one primary. They share it, so they run one at a time: each is offered by the Clients the
 * screen shows, and a second one would be decided on Clients that the first is about to change.
 * The second does nothing until the first has been answered and the Appointment loaded again.
 */
internal fun clientsChangeKey(appointmentId: String) = "clients:$appointmentId"

/**
 * The Services the Travel Fee has just added to or removed from an Appointment, on a loaded copy
 * of it, with the totals that follow. The server adds it after the Services that were there.
 */
internal fun Appointment.withTravelFee(change: TravelFeeChange): Appointment {
    val kept = change.removed.fold(this) { appointment, service -> appointment.withoutService(service.id) }

    return change.added.filterNot { added -> kept.services.any { it.id == added.id } }.fold(kept) { appointment, service ->
        val total = appointment.serviceTotalCents + service.priceCents

        appointment.copy(
            services = appointment.services + service,
            participants = appointment.participants.map {
                if (it.clientId == service.clientId) it.copy(subtotalCents = it.subtotalCents + service.priceCents) else it
            },
            serviceTotalCents = total,
            finalTotalCents = appointment.finalTotalCentsOverride ?: total,
        )
    }
}

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
