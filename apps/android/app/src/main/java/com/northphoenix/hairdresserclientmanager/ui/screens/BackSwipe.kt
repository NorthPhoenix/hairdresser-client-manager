package com.northphoenix.hairdresserclientmanager.ui.screens

import androidx.activity.BackEventCompat
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/**
 * What a back swipe from the edge of the phone looks like: the screen goes off to the side the
 * finger pushes it, and the screen before it comes in from behind. The navigation library moves
 * both with the gesture, so they are linear: the screen is where the finger is.
 */
internal object BackSwipe {
    // Only for after the finger lets go: the rest of the way out, or back into place.
    private const val SETTLE_MILLIS = 250

    // The screen underneath starts a quarter of its width off, so it moves less than the one leaving.
    private const val UNDERNEATH_SHARE = 4

    /** The screen that is left. */
    fun leave(edge: Int): ExitTransition =
        slideOutHorizontally(tween(SETTLE_MILLIS, easing = LinearEasing)) { width -> leavingOffset(width, edge) }

    /** The screen that comes back. */
    fun comeBack(edge: Int): EnterTransition =
        slideInHorizontally(tween(SETTLE_MILLIS, easing = LinearEasing)) { width -> returningOffset(width, edge) }

    /** Where the screen that is left ends up: off to the right for a swipe from the left edge, to the left for one from the right. */
    fun leavingOffset(width: Int, edge: Int): Int = width * direction(edge)

    /** Where the screen that comes back starts: on the other side, and less far. */
    fun returningOffset(width: Int, edge: Int): Int = -width * direction(edge) / UNDERNEATH_SHARE

    private fun direction(edge: Int): Int = if (edge == BackEventCompat.EDGE_RIGHT) -1 else 1
}
