package com.northphoenix.hairdresserclientmanager.state

import androidx.annotation.StringRes
import com.northphoenix.hairdresserclientmanager.data.toApiException
import com.northphoenix.hairdresserclientmanager.i18n.messageRes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where a screen stands with data it asked for. Each state is drawn differently and none of them
 * is "empty": a list says that it has nothing in it only when it is [Loaded].
 */
sealed interface LoadState {
    /** On its way, or not asked for yet. */
    data object Loading : LoadState

    data object Loaded : LoadState

    /** Could not be loaded and there is nothing to show. The screen says why and offers a retry. */
    data class Failed(@StringRes val message: Int) : LoadState

    /** A single item the server does not have (any more). The screen says so and offers a way back. */
    data object Missing : LoadState
}

/**
 * Follows the loads of one piece of data, so that its screen can always tell the states apart:
 *
 * - [LoadState.Loaded] from the first load that succeeds. Data on screen stays there while it is
 *   loaded again and after a reload that failed.
 * - [LoadState.Loading] only while a load is running, or before the first one: a spinner cannot
 *   outlast its request.
 * - [LoadState.Failed] when the last load failed and nothing has been loaded.
 *
 * [reset] starts over for other data (another Calendar day, another Stylist). A load that was
 * still running for the old data then changes nothing, however it ends.
 *
 * Not thread-safe: use it from one thread.
 */
internal class LoadTracker {
    private var generation = 0
    private var running = 0
    private var loaded = false
    private var failure: Int? = null
    private val _state = MutableStateFlow<LoadState>(LoadState.Loading)

    val state: StateFlow<LoadState> = _state.asStateFlow()

    /**
     * Fetches the data and hands it to [apply]. A failure is kept for the screen and thrown on
     * for the caller, who decides whether it is also worth a notice.
     */
    suspend fun <T> load(fetch: suspend () -> T, apply: (T) -> Unit) {
        val asked = generation

        running++
        publish()
        try {
            val result = fetch()

            if (asked == generation) {
                apply(result)
                loaded = true
                failure = null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (asked == generation) {
                failure = error.toApiException().messageRes()
                throw error
            }
        } finally {
            if (asked == generation) {
                running--
                publish()
            }
        }
    }

    fun reset() {
        generation++
        running = 0
        loaded = false
        failure = null
        publish()
    }

    private fun publish() {
        _state.value = when {
            loaded -> LoadState.Loaded
            running > 0 -> LoadState.Loading
            else -> failure?.let(LoadState::Failed) ?: LoadState.Loading
        }
    }
}

/**
 * Runs loads side by side. One that fails does not stop the others, so each of them ends in a
 * state of its own. The first failure is thrown once all of them have ended.
 */
internal suspend fun together(vararg loads: suspend () -> Unit) {
    val failures = coroutineScope {
        loads.map { load ->
            async {
                try {
                    load()
                    null
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    error
                }
            }
        }.awaitAll()
    }

    failures.firstNotNullOfOrNull { it }?.let { throw it }
}

/**
 * Runs loads one after another, for a load that needs what the one before it loaded. As in
 * [together], one that fails does not stop the next, and the first failure is thrown at the end.
 */
internal suspend fun inTurn(vararg loads: suspend () -> Unit) {
    var failure: Exception? = null

    for (load in loads) {
        try {
            load()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failure = failure ?: error
        }
    }

    failure?.let { throw it }
}
