package com.northphoenix.hairdresserclientmanager.state

import androidx.annotation.StringRes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Where a screen stands with data it asked for. Each state is drawn differently and none of them
 * is "empty": a list says that it has nothing in it only when it is [Loaded].
 *
 * Every read has its state from the read cache, next to its data in a [Read] (see [ReadCache]):
 * [Loaded] from the first answer on, also while it is asked for again and after that has failed;
 * [Loading] only while a request runs for something that has no answer, so a spinner cannot
 * outlast its request; [Failed] when that request failed.
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

/**
 * Loads the [settings] and the [lists] side by side, so that no list waits for the settings: a
 * list that is tried again is loading from the tap on. [listsOfSetting] are the lists whose
 * content depends on [setting]. They are loaded once more when the settings came back with
 * another value of it, and only after their first load has ended, so that the answer to that one
 * cannot arrive last. As in [together], a load that fails does not stop the others, so that each
 * list shows how its own load went, and the first failure is thrown at the end.
 */
internal suspend fun <S> withSettings(
    setting: () -> S,
    settings: suspend () -> Unit,
    lists: suspend () -> Unit,
    listsOfSetting: suspend () -> Unit,
) {
    val asked = setting()

    inTurn(
        { together(settings, lists) },
        { if (setting() != asked) listsOfSetting() },
    )
}
