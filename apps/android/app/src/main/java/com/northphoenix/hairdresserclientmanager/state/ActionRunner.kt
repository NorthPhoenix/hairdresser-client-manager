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
 *   a reload that fails is retried quietly instead of being reported as a failed change.
 * - When the request was sent but never answered ([ApiException.outcomeUnknown]) the failure is
 *   reported and the reload still runs, so the screen shows whether the change was saved.
 * - Whatever is thrown is reported through [onFailure]. Nothing escapes to kill the app, and
 *   [busy] always clears.
 *
 * Not thread-safe: call it from the scope's own (main) thread.
 */
internal class ActionRunner(
    private val scope: CoroutineScope,
    private val onFailure: (ApiException) -> Unit,
    private val reloadRetryDelaysMillis: List<Long> = listOf(3_000, 10_000, 30_000),
) {
    private val running = mutableSetOf<String>()
    private var pending = 0
    private val _busy = MutableStateFlow(false)

    // One reload at a time, so the one started last is also the one applied last.
    private val reloadLock = Mutex()

    /** True while any change is in flight. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * [key] names the action and what it acts on, such as `deleteService:<id>`. Pass null only
     * when repeated calls are meant and the caller orders them itself (see [LatestWins]).
     */
    fun <T> run(key: String?, change: suspend () -> T, reload: suspend () -> Unit = {}, onSaved: (T) -> Unit = {}) {
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
                if (saved || failure?.outcomeUnknown == true) {
                    reloadWithRetry(reload)
                }
            } finally {
                key?.let(running::remove)
                pending--
                _busy.value = pending > 0
            }
        }
    }

    /** Reloads now and, if that fails, a few more times in the background. A failed reload is never reported. */
    suspend fun reloadWithRetry(reload: suspend () -> Unit) {
        if (reloaded(reload)) {
            return
        }

        scope.launch {
            for (wait in reloadRetryDelaysMillis) {
                delay(wait)
                if (reloaded(reload)) {
                    return@launch
                }
            }
        }
    }

    private suspend fun reloaded(reload: suspend () -> Unit): Boolean = failureOrNull { reloadLock.withLock { reload() } } == null

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
