package com.northphoenix.hairdresserclientmanager.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.Flow

/**
 * Collects what a screen reads for itself through the read cache (one Appointment, a Client's
 * history, the sources of a copy) for as long as the screen is open: also while the app is not
 * in front, behind the camera, the photo picker, the SMS app or a locked phone.
 *
 * On purpose not `collectAsStateWithLifecycle`. The cache keeps nothing of a read that nobody
 * watches, so a watch that ends when the app is stopped starts again from what the cache has by
 * then. That is nothing once the answer is older than the cache keeps it, or once a change has
 * overtaken it (a photo that finished uploading meanwhile): the screen would come back loading,
 * or failed without a connection, and what was typed and opened on it would be gone. A watch that
 * goes on keeps what it shows, gets every change, and is found by the reload after one.
 *
 * It costs no request while the app is away: a watch asks the server once, when it starts.
 */
@Composable
fun <T> Flow<T>.collectWhileOpen(initialValue: T): State<T> = collectAsState(initialValue)
