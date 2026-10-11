package com.northphoenix.hairdresserclientmanager.state

import com.northphoenix.hairdresserclientmanager.data.ApiException
import com.northphoenix.hairdresserclientmanager.data.toApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs the Stylist's changes so that all of them follow the same rules. A change has two parts:
 * the request that makes it, and the reload that shows its result.
 *
 * - While a change is running, a second call with the same key does nothing.
 * - Once the request has succeeded the change has succeeded: `onSaved` runs before the reload, and
 *   a reload that fails is never reported as a failed change.
 * - When the request was sent but never answered ([ApiException.outcomeUnknown]) the failure is
 *   reported and the reload still runs, so the screen shows whether the change was saved.
 * - When the server says that something the change refers to no longer exists
 *   ([ApiException.isNotFound]) the failure is reported and everything is reloaded, so what is
 *   gone leaves the screen.
 * - When a change that makes several requests fails after one of them was saved (`savedInPart`)
 *   the failure is reported and the reload still runs, so the screen shows what was saved.
 * - A reload after a change that fails stays owed until it works (see [reloadAfterChange]), and
 *   the Stylist is told when the screen may not show a change that was saved.
 * - Whatever is thrown is reported through [onFailure]. Nothing escapes to kill the app, and
 *   [busy] always clears.
 *
 * Not thread-safe: call it from the scope's own (main) thread.
 */
internal class ActionRunner(
    private val scope: CoroutineScope,
    private val onFailure: (ApiException) -> Unit,
    private val reloadRetryDelaysMillis: List<Long> = listOf(3_000, 10_000, 30_000),
    /** Reloads everything a screen can show. A change's own reload covers only what that change touches. */
    private val reloadAll: suspend () -> Unit = {},
    /** A change was saved, but the screen could not be reloaded after it and may still show old data. */
    private val onStale: () -> Unit = {},
) {
    private val running = mutableSetOf<String>()
    private var pending = 0
    private val _busy = MutableStateFlow(false)

    // One reload at a time, so the one started last is also the one applied last.
    private val reloadLock = Mutex()

    // Reloads that failed and have not worked since: the screen may still show what they would have replaced.
    private val owed = mutableListOf<Owed>()
    private var forgotten = 0

    /** True while any change is in flight. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * [key] names the action and what it acts on, such as `deleteService:<id>`. Pass null only
     * when repeated calls are meant and the caller orders them itself (see [LatestWins]).
     * [savedInPart] is asked when [change] has failed: true when a request it made before the one
     * that failed was saved.
     */
    fun <T> run(
        key: String?,
        change: suspend () -> T,
        reload: suspend () -> Unit = {},
        savedInPart: () -> Boolean = { false },
        onSaved: (T) -> Unit = {},
    ) {
        if (key != null && !running.add(key)) {
            return
        }

        // Undispatched, so a second tap in the same frame already finds this one running.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            pending++
            _busy.value = true
            try {
                var saved = false
                val failure = failureOrNull {
                    val result = change()
                    saved = true
                    onSaved(result)
                }

                failure?.let(onFailure)
                when {
                    saved || failure?.outcomeUnknown == true -> reloadAfterChange(saved, reload)
                    // The screen shows something the server no longer has, and the reply does not say what.
                    failure?.isNotFound == true -> reloadAfterChange(saved = false) {
                        reloadAll()
                        reload()
                    }
                    // The Stylist has been told what failed. What was saved before it still has to show.
                    savedInPart() -> reloadAfterChange(saved = false, reload)
                }
            } finally {
                key?.let(running::remove)
                pending--
                _busy.value = pending > 0
            }
        }
    }

    /**
     * Reloads what a change touched. A reload that fails is never reported as a failure, and it is
     * not dropped either: it is tried again after each of the retry delays and then stays owed
     * until [reloadOwed] gets it to work.
     *
     * [saved] says that the change is known to be saved. If the first retry fails as well,
     * [onStale] is told, once, that the screen may not show the change. After a change that failed
     * the Stylist has been told already.
     */
    suspend fun reloadAfterChange(saved: Boolean, reload: suspend () -> Unit) = reloadAndRetry(reload, keepOwed = true, tellWhenStale = saved)

    /**
     * Reloads now and, if that fails, a few more times in the background, without reporting it.
     * For a reload that follows no change, so nobody is waiting for it: it is dropped when the
     * retries fail too.
     */
    suspend fun reloadWithRetry(reload: suspend () -> Unit) = reloadAndRetry(reload, keepOwed = false, tellWhenStale = false)

    /**
     * Tries the reloads that are owed again, up to the first that fails again. The runner does
     * this itself whenever a reload works; call it when something else shows that the server answers.
     */
    suspend fun reloadOwed() {
        for (debt in owed.toList()) {
            if (!settled(debt)) {
                // To the back, so that a reload that cannot work costs one try and does not hold up the others.
                if (owed.remove(debt)) {
                    owed += debt
                }
                return
            }
        }
    }

    /** Drops what is owed, being retried or still running: it was for a Stylist who has signed out. */
    fun forgetOwed() {
        forgotten++
        owed.clear()
    }

    private suspend fun reloadAndRetry(reload: suspend () -> Unit, keepOwed: Boolean, tellWhenStale: Boolean) {
        val asked = forgotten

        if (reloaded(reload)) {
            // What worked for this one may work for the owed ones now. Nobody waits for them.
            if (owed.isNotEmpty()) {
                scope.launch { reloadOwed() }
            }
            return
        }
        if (asked != forgotten) {
            return
        }

        val debt = Owed(reload).also(owed::add)

        scope.launch {
            var tell = tellWhenStale

            for (wait in reloadRetryDelaysMillis) {
                delay(wait)
                if (settled(debt)) {
                    reloadOwed()
                    return@launch
                }
                if (tell) {
                    tell = false
                    onStale()
                }
            }
            if (!keepOwed) {
                owed -= debt
            }
        }
    }

    private suspend fun reloaded(reload: suspend () -> Unit): Boolean = failureOrNull { reloadLock.withLock { reload() } } == null

    /**
     * Tries an owed reload again. True when it is no longer owed: it has worked now, something
     * else had got it to work by its turn, or it was forgotten.
     */
    private suspend fun settled(debt: Owed): Boolean =
        reloadLock.withLock {
            if (debt in owed && failureOrNull(debt.reload) == null) {
                owed -= debt
            }
            debt !in owed
        }

    /** One failed reload. Each failure is an entry of its own, also when two of them reload the same thing. */
    private class Owed(val reload: suspend () -> Unit)

    /** Runs [block] and returns what went wrong, if anything. Cancellation is never a failure. */
    private suspend fun failureOrNull(block: suspend () -> Unit): ApiException? =
        try {
            block()
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            error.toApiException()
        }
}
