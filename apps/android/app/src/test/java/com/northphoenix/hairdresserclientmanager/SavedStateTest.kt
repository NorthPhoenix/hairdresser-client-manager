package com.northphoenix.hairdresserclientmanager

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.lifecycle.SavedStateHandle
import com.northphoenix.hairdresserclientmanager.state.SavedCalendarDay
import com.northphoenix.hairdresserclientmanager.ui.screens.DetailSheet
import com.northphoenix.hairdresserclientmanager.ui.screens.MenuEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** What has to survive Android recreating the screen or killing the app in the background. */
class SavedStateTest {
    // --- the Calendar's selected day ---

    private val day = LocalDate.of(2026, 10, 24)

    /** What Android hands back after it has killed the app: a new handle with what the old one held. */
    private fun SavedStateHandle.afterAppWasKilled() = SavedStateHandle(keys().associateWith { get<Any?>(it) })

    @Test
    fun `the selected day comes back for the Stylist who selected it after the app was killed`() {
        val state = SavedStateHandle()
        SavedCalendarDay(state).keep("stylist-1", day)

        val restored = state.afterAppWasKilled()
        assertEquals(day, SavedCalendarDay(restored).take("stylist-1"))

        // It stays the selected day without being selected again, so it survives being killed once more.
        assertEquals(day, SavedCalendarDay(restored.afterAppWasKilled()).take("stylist-1"))
    }

    @Test
    fun `a Calendar left on today comes back on today`() {
        val state = SavedStateHandle()
        val calendar = SavedCalendarDay(state)

        // A fresh start has nothing to restore.
        assertNull(calendar.take("stylist-1"))

        // Browsed to another day and back to today: null is how today is kept.
        calendar.keep("stylist-1", day)
        calendar.keep("stylist-1", null)

        assertNull(SavedCalendarDay(state.afterAppWasKilled()).take("stylist-1"))
    }

    @Test
    fun `another Stylist starts on today and the day is not kept for anyone`() {
        val state = SavedStateHandle()
        SavedCalendarDay(state).keep("stylist-1", day)

        val restored = state.afterAppWasKilled()
        assertNull(SavedCalendarDay(restored).take("stylist-2"))
        assertNull(SavedCalendarDay(restored.afterAppWasKilled()).take("stylist-1"))
    }

    @Test
    fun `the day is handed out once, so signing in again starts on today`() {
        val state = SavedStateHandle()
        SavedCalendarDay(state).keep("stylist-1", day)

        val restored = state.afterAppWasKilled()
        val calendar = SavedCalendarDay(restored)
        assertEquals(day, calendar.take("stylist-1"))

        // Signed out and in again while the app keeps running: today, and today is what is kept from then on.
        assertNull(calendar.take("stylist-1"))
        assertNull(SavedCalendarDay(restored.afterAppWasKilled()).take("stylist-1"))
    }

    @Test
    fun `a day selected after a start replaces the one that was restored`() {
        val state = SavedStateHandle()
        SavedCalendarDay(state).keep("stylist-1", day)

        val restored = state.afterAppWasKilled()
        val calendar = SavedCalendarDay(restored)
        calendar.take("stylist-1")
        calendar.keep("stylist-1", day.plusDays(3))

        assertEquals(day.plusDays(3), SavedCalendarDay(restored.afterAppWasKilled()).take("stylist-1"))
    }

    @Test
    fun `a saved day that cannot be read counts as today`() {
        val state = SavedStateHandle(mapOf("calendarDay" to "not a date", "calendarDayStylist" to "stylist-1"))

        assertNull(SavedCalendarDay(state).take("stylist-1"))
    }

    // --- open sheets ---

    private fun <T> Saver<T, Any>.saved(value: T): Any? = with(this) { SaverScope { true }.save(value) }

    private fun <T> Saver<T, Any>.roundTrip(value: T): T? = saved(value)?.let(::restore)

    @Test
    fun `every sheet of the Appointment screen comes back as the sheet it was`() {
        val sheets = listOf(
            DetailSheet.AddClient,
            DetailSheet.AddService,
            DetailSheet.CopyServices,
            DetailSheet.Formula(serviceId = "service-1"),
            DetailSheet.Formula(serviceId = "service-1", formulaId = "formula-1"),
            DetailSheet.AddPhoto(clientId = "client-1"),
            DetailSheet.AddPhoto(clientId = "client-1", replacingPhotoId = "photo-1"),
            DetailSheet.Photo(photoId = "photo-1"),
        )

        for (sheet in sheets) {
            assertEquals(sheet, DetailSheet.Saver.roundTrip(sheet))
        }
    }

    @Test
    fun `no open sheet is saved as nothing, and what cannot be read is no sheet`() {
        assertNull(DetailSheet.Saver.saved(null))
        assertNull(DetailSheet.Saver.restore(listOf("formula")))
        assertNull(DetailSheet.Saver.restore(listOf("somethingElse", "id")))

        assertNull(MenuEditor.Saver.saved(null))
        assertNull(MenuEditor.Saver.restore(listOf("edit")))
    }

    @Test
    fun `the Service Menu editor comes back for the same item`() {
        assertEquals(MenuEditor.New, MenuEditor.Saver.roundTrip(MenuEditor.New))
        assertEquals(MenuEditor.Edit(itemId = "item-1"), MenuEditor.Saver.roundTrip(MenuEditor.Edit(itemId = "item-1")))
    }
}
