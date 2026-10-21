package com.northphoenix.hairdresserclientmanager.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.LifecycleResumeEffect
import java.time.Instant
import kotlinx.coroutines.delay

/**
 * The time, looked at again at the start of every minute, as Appointments start on the minute,
 * and each time the app comes back into view.
 */
@Composable
fun rememberNow(): State<Instant> {
    val now = remember { mutableStateOf(Instant.now()) }

    LifecycleResumeEffect(Unit) {
        now.value = Instant.now()
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        while (true) {
            // A moment after the minute has turned, so that the look does not land just before it.
            delay(60_000 - System.currentTimeMillis() % 60_000 + 50)
            now.value = Instant.now()
        }
    }

    return now
}
