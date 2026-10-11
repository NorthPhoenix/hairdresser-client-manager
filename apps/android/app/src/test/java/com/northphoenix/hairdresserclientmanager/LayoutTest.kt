package com.northphoenix.hairdresserclientmanager

import androidx.compose.ui.unit.dp
import com.northphoenix.hairdresserclientmanager.ui.components.oneLineScale
import com.northphoenix.hairdresserclientmanager.ui.screens.tabLabelWidth
import com.northphoenix.hairdresserclientmanager.ui.screens.weekStripExtraWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The arithmetic behind labels that stay whole at a large font size and day cells that are wide enough to tap. */
class LayoutTest {
    // --- one line of text in a place of a given width ---

    @Test
    fun `a line that fits keeps its font size`() {
        assertEquals(1f, oneLineScale(width = 199, available = 233f, least = 0.6f))
        assertEquals(1f, oneLineScale(width = 233, available = 233f, least = 0.6f))
        assertEquals(1f, oneLineScale(width = 0, available = 233f, least = 0.6f))
    }

    @Test
    fun `a line wider than its place gets the share of its font size that the place is of its width`() {
        // 'Календарь' at font scale 2.0 in a tab of a 411 dp phone.
        assertEquals(0.6f, oneLineScale(width = 400, available = 240f, least = 0.3f), 0.0001f)
    }

    @Test
    fun `a line a pixel too wide loses enough to come out narrower`() {
        assertEquals(0.98f, oneLineScale(width = 234, available = 233f, least = 0.6f))
    }

    @Test
    fun `a line does not get smaller than the smallest size, and one that is smaller already stays as it is`() {
        assertEquals(0.6f, oneLineScale(width = 1000, available = 233f, least = 0.6f))
        assertEquals(0.6f, oneLineScale(width = 1000, available = -20f, least = 0.6f))
        assertEquals(1f, oneLineScale(width = 1000, available = 233f, least = 1.4f))
    }

    // --- the labels of the bottom tabs ---

    @Test
    fun `a tab label is given its tab less 4 dp at both edges`() {
        // The QA device: 1080 px at 2.625 px per dp. Its four tabs were 254 px wide, 21 px (8 dp) apart.
        val width = tabLabelWidth((1080 / 2.625f).dp, tabs = 4).value * 2.625f

        assertTrue("$width", width <= 254 - 2 * 10.5f)
        assertTrue("$width", width > 254 - 2 * 10.5f - 4)
    }

    @Test
    fun `the tabs with their labels' places never add up to more than the bar`() {
        for (bar in listOf(280, 308, 320, 360, 393, 411, 480, 600, 840)) {
            val label = tabLabelWidth(bar.dp, tabs = 4).value

            assertTrue("$bar", 4 * (label + 8) + 3 * 8 <= bar)
        }
    }

    // --- the week strip of the Calendar ---

    /** The width of one day in pixels, on a screen [screen] px wide whose list keeps [padding] px clear on both sides. */
    private fun dayWidth(screen: Int, padding: Int, touchTarget: Int): Int {
        val row = screen - 2 * padding

        return (row + weekStripExtraWidth(row, neededWidth = 7 * touchTarget, sidePadding = padding)) / 7
    }

    @Test
    fun `a list wide enough for seven touch targets keeps the week strip inside its padding`() {
        // 411 dp at 2.625 px per dp: 20 dp of padding, 48 dp touch targets.
        assertEquals(0, weekStripExtraWidth(rowWidth = 975, neededWidth = 882, sidePadding = 52))
        assertEquals(139, dayWidth(screen = 1080, padding = 52, touchTarget = 126))
    }

    @Test
    fun `on a 360 dp phone the week strip reaches into the padding until every day is a touch target wide`() {
        // 360 dp at 3 px per dp: the list is 320 dp wide, seven days need 336 dp.
        assertEquals(48, weekStripExtraWidth(rowWidth = 960, neededWidth = 1008, sidePadding = 60))
        assertEquals(144, dayWidth(screen = 1080, padding = 60, touchTarget = 144))
    }

    @Test
    fun `on a screen too narrow for seven touch targets the week strip takes the whole width and no more`() {
        // 308 dp at 3.5 px per dp, the largest display size of the QA device.
        assertEquals(140, weekStripExtraWidth(rowWidth = 940, neededWidth = 1176, sidePadding = 70))
        assertEquals(1080 / 7, dayWidth(screen = 1080, padding = 70, touchTarget = 168))
    }
}
