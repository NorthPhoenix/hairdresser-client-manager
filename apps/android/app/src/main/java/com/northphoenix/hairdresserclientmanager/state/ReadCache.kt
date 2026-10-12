package com.northphoenix.hairdresserclientmanager.state

import com.northphoenix.hairdresserclientmanager.data.ApiException
import com.northphoenix.hairdresserclientmanager.data.toApiException
import com.northphoenix.hairdresserclientmanager.i18n.messageRes
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.Fetcher
import org.mobilenativefoundation.store.store5.MemoryPolicy
import org.mobilenativefoundation.store.store5.Store
import org.mobilenativefoundation.store.store5.StoreBuilder
import org.mobilenativefoundation.store.store5.StoreReadRequest
import org.mobilenativefoundation.store.store5.StoreReadResponse
import org.mobilenativefoundation.store.store5.StoreReadResponseOrigin
import org.mobilenativefoundation.store.store5.Validator

/**
 * What a screen has of something it reads from the server: the answer, when there is one, and how
 * the read stands. [LoadState.Loaded] says that [data] is an answer, also when the answer is "nothing".
 *
 * [data] is what the screen shows: the answer with the changes the Stylist has made that the
 * server has not answered yet laid over it (see [ReadCache.expect]). [saved] is the answer without
 * them, what the server is known to have. The two are the same while no such change is on its way.
 */
data class Read<out V>(val state: LoadState = LoadState.Loading, val data: V? = null, val saved: V? = data)

/** What the server answered to a read, with what tells whether a change has made it out of date since (see [Changes]). */
internal data class Answer<out V>(
    val value: V,
    /** How many changes the server had confirmed when the read was sent. */
    val asked: Int,
    /** The ids of what the answer holds, such as its Appointments and their Clients. A change to one of them is a change to the answer. */
    val about: Collection<String> = emptyList(),
)

/**
 * Counts the changes the server has confirmed, so that an answer which shows them can be told
 * from one that was asked for before: that one may be wrong. It is never shown and never served
 * from the cache; the read is asked again instead.
 *
 * Not thread-safe: use it from one thread.
 */
internal class Changes {
    /** The changes so far. A read notes it when it is sent. */
    var count = 0
        private set

    /** How often the cache was started over for another Stylist. */
    var session = 0
        private set

    private var anywhere = 0
    private val byId = mutableMapOf<String, Int>()

    /**
     * The server has just accepted a change to what [ids] name (an Appointment, a Client), or may
     * have: only an answer asked for from now on shows it.
     */
    fun changed(vararg ids: String) {
        count++
        ids.forEach { byId[it] = count }
    }

    /**
     * As [changed], for a change that can show in any answer, such as the salon's address or the
     * Stylist's timezone, and for a refusal that says the screen is out of date. Not for a change
     * that a name covers: every cached answer is loaded anew after this.
     */
    fun changedAnywhere() {
        count++
        anywhere = count
    }

    /** The Stylist has signed out. No answer so far is the next one's, nor one that is still on its way. */
    fun startOver() {
        session++
        changedAnywhere()
        byId.clear()
    }

    /** False for an answer that was asked for before a change to something it holds. */
    fun isCurrent(answer: Answer<*>): Boolean =
        answer.asked >= anywhere && answer.about.all { answer.asked >= if (it == ANY_CHANGE) count else byId[it] ?: 0 }

    companion object {
        /**
         * In what an answer is about, names everything there is: such an answer is out of date
         * after any change at all. For an answer that repeats so much of other things that no list
         * of ids covers it, such as a Client's Appointment history.
         */
        const val ANY_CHANGE = "any-change"
    }
}

/** How many answers a cache keeps, and for how long after the server gave them. */
internal data class CacheLimits(val answers: Long, val age: Duration = AGE) {
    companion object {
        // Every key that is shown is asked again behind what the cache has, so the age only limits
        // what is shown at once, and for how long a refresh that keeps failing leaves it there.
        val AGE = 30.minutes
    }
}

/**
 * One kind of read, such as "the Appointments of a day", cached in memory by a Store
 * (docs/adr/0016): the fetch, the cached answers with their size and age limits and the sharing of
 * one request between everyone who asks for the same key are the Store's. Nothing is kept on the device.
 *
 * - [watch] is how a screen reads. A key that was read before is there at once, from the cache,
 *   and is asked again behind it: no loading state. Only a key with nothing cached is loading.
 * - [refresh] asks again now: pull to refresh, 'Try again', the reload after a change.
 * - [update] and [answer] put what the server has just confirmed in front of whoever is watching.
 *   The cached answers are not rewritten: the change is marked in [changes], which keeps every
 *   answer asked for before it from being shown or served again.
 * - [expect] shows a change before the server has answered it, laid over whatever the cache holds
 *   or gets meanwhile, until it is [settled]. Nothing is kept twice: it is an edit, applied to
 *   what is shown ([Read.data]) and never to what the server has ([Read.saved]).
 *
 * Not thread-safe: use it from the thread of [scope].
 */
internal class ReadCache<K : Any, V>(
    private val scope: CoroutineScope,
    private val changes: Changes,
    limits: CacheLimits,
    /** What an answer is about (see [Answer.about]). */
    private val about: (K, V) -> Collection<String> = { _, _ -> emptyList() },
    private val fetch: suspend (K) -> V,
) {
    private val store: Store<K, Answer<V>> =
        StoreBuilder
            .from(
                Fetcher.of { key: K ->
                    val asked = changes.count
                    val value = fetch(key)

                    Answer(value, asked, about(key, value))
                },
            )
            .scope(scope)
            .cachePolicy(MemoryPolicy.builder<K, Answer<V>>().setMaxSize(limits.answers).setExpireAfterWrite(limits.age).build())
            // A cached answer that a change has overtaken counts as not cached. (Whoever follows a read checks again: see [follow].)
            .validator(Validator.by { changes.isCurrent(it) })
            .build()

    private var watchers = 0
    private val _shown = MutableStateFlow<Map<Int, Shown<K, V>>>(emptyMap())

    // The changes the Stylist has made that the server has not answered yet, by what names each, in the order they were made.
    private var expected = mapOf<Any, (K, V) -> V>()

    /** What is on screen now: per watcher, its key and what it sees. Nothing here outlives its watcher. */
    val shown: StateFlow<Map<Int, Shown<K, V>>> = _shown.asStateFlow()

    /** What the watchers of [key] see now, null when nobody watches it. */
    fun shown(key: K): Read<V>? = _shown.value.values.firstOrNull { it.key == key }?.read

    /**
     * What there is of [key] before anyone has asked: what its watchers see, or else the cached
     * answer, and [LoadState.Loading] when there is neither. Nothing is asked of the server. For
     * the first frame of a screen that is about to [watch] the key, so that what is cached is
     * there from the start: the first value of [watch] is the same, a moment later.
     */
    fun now(key: K): Read<V> {
        shown(key)?.takeIf { it.state == LoadState.Loaded }?.let { return it }

        var cached = Read<V>()

        // The Store answers a read of its memory alone without suspending, so this has run when `launch` returns.
        // Should it ever wait instead, there is no answer for now and the watcher shows it.
        scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            store.stream(StoreReadRequest.localOnly(key)).catch { }.collect { response ->
                if (response is StoreReadResponse.Data && changes.isCurrent(response.value)) {
                    cached = Read(LoadState.Loaded, response.value.value).asShown(key)
                }
            }
        }.cancel()

        return cached
    }

    /**
     * What a screen shows of [key], for as long as it is collected. The first value is there at
     * once: the cached answer as [LoadState.Loaded], or [LoadState.Loading] when nothing is cached.
     * Either way the server is asked, and its answer follows. When that fails, a cached answer
     * stays as it is, and where there is none the failure is the state. Later values are what
     * [refresh], [update] and [answer] bring.
     */
    fun watch(key: K): Flow<Read<V>> = flow {
        val watcher = ++watchers

        // Whoever watches the key already has what is shown of it, with every change since: a new watcher starts from the same.
        _shown.update { it + (watcher to Shown(key, shown(key)?.takeIf { read -> read.state == LoadState.Loaded } ?: Read())) }
        try {
            coroutineScope {
                // Undispatched: what the cache has is in place before the first value is emitted below.
                launch(start = CoroutineStart.UNDISPATCHED) {
                    val response = follow(StoreReadRequest.cached(key, refresh = true), watcher)

                    if (response.isOvertaken()) {
                        quietly { refresh(key) }
                    }
                }
                emitAll(_shown.mapNotNull { it[watcher]?.read }.distinctUntilChanged())
            }
        } finally {
            _shown.update { it - watcher }
        }
    }

    /**
     * Asks the server for [key] again. The answer goes into the cache and in front of whoever
     * watches the key, and is returned. A failure is thrown on for the caller, who decides whether
     * it is worth a notice; an answer on screen stays there, and a watcher without one shows the
     * failure. While this runs, such a watcher is loading again.
     *
     * A request for [key] that is already on its way is shared. When its answer turns out to be
     * older than a change, the server is asked once more. What a sign-out has overtaken goes
     * nowhere: [LoadState.Loading] is returned, whatever came of it.
     *
     * What is returned is the server's answer as it is. A change that is still on its way
     * ([expect]) is laid over it for whoever watches the key, not here.
     */
    suspend fun refresh(key: K): Read<V> {
        val session = changes.session

        while (true) {
            val response = follow(StoreReadRequest.fresh(key), watcher = null)

            when {
                session != changes.session -> return Read()
                response is StoreReadResponse.Error -> throw response.failure()
                response is StoreReadResponse.Data && changes.isCurrent(response.value) -> return Read(LoadState.Loaded, response.value.value)
                response !is StoreReadResponse.Data -> throw ApiException("The read ended without an answer.", code = "UNEXPECTED")
            }
        }
    }

    /**
     * Asks again for every key that is on screen now (see [refresh]): the reload of a read that a
     * screen watches for itself, such as an open sheet's. Nothing is asked while nobody watches.
     * One key that fails does not stop the others; the first failure is thrown when all have ended.
     */
    suspend fun refreshShown() {
        val keys = _shown.value.values.map { it.key }.distinct()

        together(*keys.map<K, suspend () -> Unit> { key -> { refresh(key) } }.toTypedArray())
    }

    /**
     * Applies what the server has just confirmed to every answer on screen. Mark the change in
     * [changes] as well, so that no answer from before it is shown or served again.
     */
    fun update(change: (K, V) -> V) = _shown.update { all ->
        all.mapValues { (_, shown) ->
            @Suppress("UNCHECKED_CAST")
            if (shown.read.state == LoadState.Loaded) shown.copy(read = Read(LoadState.Loaded, change(shown.key, shown.read.saved as V)).asShown(shown.key)) else shown
        }
    }

    /** The answer to [key] is known without asking, such as "gone" after a delete: whoever watches the key sees it. Mark the change as for [update]. */
    fun answer(key: K, value: V) = _shown.update { all ->
        all.mapValues { (_, shown) -> if (shown.key == key) shown.copy(read = Read(LoadState.Loaded, value).asShown(key)) else shown }
    }

    /**
     * Shows a change the Stylist has just made, before the server has answered it: [edit] is laid
     * over every answer on screen, and over whatever arrives or is shown from the cache while the
     * change is on its way, so that a refresh in the meantime does not bring the old value back.
     * [change] names it: expecting it again replaces its edit, as when the value is tapped once more.
     *
     * [edit] gets every answer and leaves alone what it is not about. It never reaches
     * [Read.saved]. Call [settled] when the change has been answered, whichever way: once it is
     * saved, put it there with [update] first.
     */
    fun expect(change: Any, edit: (K, V) -> V) {
        expected = expected + (change to edit)
        showExpected()
    }

    /** The change that [expect] showed has been answered: its edit is taken away, and the screen shows what the server has. */
    fun settled(change: Any) {
        if (change in expected) {
            expected = expected - change
            showExpected()
        }
    }

    /**
     * Empties the cache, for a Stylist who has signed out. Call [Changes.startOver] first: it is what keeps an answer still on its way out.
     * The changes that were on their way are no longer shown ahead: they were another Stylist's.
     */
    @OptIn(ExperimentalStoreApi::class)
    suspend fun clear() {
        expected = emptyMap()
        showExpected()
        store.clear()
    }

    /**
     * Follows one read of the Store up to what its fetch ends in, which is returned, and shows
     * each step: the cached answer to [watcher] alone, everything of the fetch to all who watch
     * the key. An answer that a change has overtaken is shown to no one.
     */
    private suspend fun follow(request: StoreReadRequest<K>, watcher: Int?): StoreReadResponse<Answer<V>> {
        var last: StoreReadResponse<Answer<V>> = StoreReadResponse.Initial

        store.stream(request)
            // A fetch answers with its failure. Should the Store itself ever throw, that is a failed read as well.
            .catch { emit(StoreReadResponse.Error.Exception(it, StoreReadResponseOrigin.Fetcher())) }
            .transformWhile { response ->
                emit(response)
                response is StoreReadResponse.Loading || response.origin !is StoreReadResponseOrigin.Fetcher
            }
            .collect { response ->
                last = response
                if (!response.isOvertaken()) {
                    val fromCache = response.origin is StoreReadResponseOrigin.Cache

                    _shown.update { all ->
                        all.mapValues { (id, shown) ->
                            if (shown.key == request.key && (!fromCache || id == watcher)) {
                                shown.copy(read = shown.read.after(response).asShown(shown.key))
                            } else {
                                shown
                            }
                        }
                    }
                }
            }

        return last
    }

    private fun StoreReadResponse<Answer<V>>.isOvertaken(): Boolean = this is StoreReadResponse.Data && !changes.isCurrent(value)

    /** This read as [key] shows it: what the server has ([Read.saved]), with every change that is on its way laid over it, in their order. */
    private fun Read<V>.asShown(key: K): Read<V> {
        if (state != LoadState.Loaded) {
            return this
        }

        @Suppress("UNCHECKED_CAST")
        val saved = saved as V

        return Read(LoadState.Loaded, expected.values.fold(saved) { shown, edit -> edit(key, shown) }, saved)
    }

    private fun showExpected() = _shown.update { all -> all.mapValues { (_, shown) -> shown.copy(read = shown.read.asShown(shown.key)) } }

    /** A read behind what is on screen: nobody waits for it, and how it went shows in [shown]. */
    private suspend fun quietly(read: suspend () -> Unit) {
        try {
            read()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        }
    }

    /** One watcher's key and what it sees. */
    data class Shown<K, out V>(val key: K, val read: Read<V>)
}

/**
 * Keeps a place on screen that shows one key at a time, such as the Calendar's day, on the newest
 * of [keys]: [show] gets what [watch] has for it, value by value. The first value of a new key is
 * shown in the same step in which the key arrives, so a cached answer replaces the one before it
 * with nothing in between. The read of the key before is given up without being waited for:
 * `collectLatest` would wait until its request has wound up, on another thread.
 */
internal suspend fun <K, T> showLatest(keys: Flow<K>, watch: (K) -> Flow<T>, show: (K, T) -> Unit): Unit = coroutineScope {
    var shown: Job? = null

    keys.collect { key ->
        shown?.cancel()
        shown = launch(start = CoroutineStart.UNDISPATCHED) {
            watch(key).collect {
                // Nothing of a key that has been left, should one of its values still be on its way.
                ensureActive()
                show(key, it)
            }
        }
    }
}

/**
 * What a read shows after the Store's next [response] to it:
 *
 * - an answer, from the cache or from the server, is [LoadState.Loaded];
 * - while the server is asked, an answer on screen stays as it is, and only a read without one is [LoadState.Loading];
 * - a failure leaves an answer on screen alone, and is [LoadState.Failed] only for a read without one.
 */
internal fun <V> Read<V>.after(response: StoreReadResponse<Answer<V>>): Read<V> =
    when (response) {
        is StoreReadResponse.Data -> Read(LoadState.Loaded, response.value.value)
        is StoreReadResponse.Loading -> if (state == LoadState.Loaded) this else Read(LoadState.Loading)
        is StoreReadResponse.Error -> if (state == LoadState.Loaded) this else Read(LoadState.Failed(response.failure().messageRes()))
        is StoreReadResponse.NoNewData, StoreReadResponse.Initial -> this
    }

/** What went wrong in a read, as the failure the rest of the app reports. */
internal fun StoreReadResponse.Error.failure(): ApiException =
    ((this as? StoreReadResponse.Error.Exception)?.error as? Exception)?.toApiException()
        ?: ApiException(errorMessageOrNull() ?: "The read failed.", code = "UNEXPECTED")
