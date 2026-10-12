package com.northphoenix.hairdresserclientmanager

import androidx.activity.BackEventCompat
import com.northphoenix.hairdresserclientmanager.ui.screens.BackSwipe
import org.junit.Assert.assertEquals
import org.junit.Test

class BackSwipeTest {
    @Test
    fun `a swipe from the left edge pushes the screen off to the right`() {
        assertEquals(1080, BackSwipe.leavingOffset(1080, BackEventCompat.EDGE_LEFT))
        assertEquals(-270, BackSwipe.returningOffset(1080, BackEventCompat.EDGE_LEFT))
    }

    @Test
    fun `a swipe from the right edge pushes the screen off to the left`() {
        assertEquals(-1080, BackSwipe.leavingOffset(1080, BackEventCompat.EDGE_RIGHT))
        assertEquals(270, BackSwipe.returningOffset(1080, BackEventCompat.EDGE_RIGHT))
    }
}
