package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentService
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.data.LocationType
import com.northphoenix.hairdresserclientmanager.domain.ServiceNames
import com.northphoenix.hairdresserclientmanager.ui.components.cardAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** What an Appointment card says on its place line and its Services line. */
class AppointmentCardTest {
    private fun appointment(locationType: LocationType, address: String) = Appointment(
        id = "a1",
        primaryClientId = "c1",
        primaryClientName = "Наташа",
        startsAt = "2026-11-16T18:30:00Z",
        status = AppointmentStatus.SCHEDULED,
        locationType = locationType,
        locationAddress = address,
    )

    private fun service(name: String, clientId: String = "c1") =
        AppointmentService(id = name + clientId, clientId = clientId, name = name, priceCents = 2500)

    @Test
    fun `an in-salon card leaves out the salon address, which every in-salon card has`() {
        assertEquals("", cardAddress(appointment(LocationType.IN_SALON, "500 Salon Ave, Austin, TX")))
        assertEquals("", cardAddress(appointment(LocationType.IN_SALON, "")))
    }

    @Test
    fun `an at-home card names the address, on one line`() {
        assertEquals("742 Evergreen Terrace, Apt 5", cardAddress(appointment(LocationType.AT_HOME, "742 Evergreen Terrace\nApt 5")))
        assertEquals("", cardAddress(appointment(LocationType.AT_HOME, " \n ")))
    }

    @Test
    fun `Services are named in the order they were added`() {
        assertEquals("Балаяж, Укладка", ServiceNames.line(listOf(service("Балаяж"), service("Укладка"))))
        assertEquals("", ServiceNames.line(emptyList()))
    }

    @Test
    fun `a Service added twice is named once with how many there are`() {
        // Two children's haircuts under one Client, added in two passes of the sheet.
        assertEquals("Стрижка детская\u00A0×2", ServiceNames.line(listOf(service("Стрижка детская"), service("Стрижка детская"))))
        // Where the name first comes, also when another Service is between, and for another Client of a group Appointment.
        assertEquals(
            "Колорирование\u00A0×2, Стрижка мужская\u00A0×2, Укладка",
            ServiceNames.line(
                listOf(
                    service("Колорирование"),
                    service("Стрижка мужская"),
                    service("Стрижка мужская", clientId = "c2"),
                    service("Колорирование", clientId = "c2"),
                    service("Укладка"),
                ),
            ),
        )
    }

    @Test
    fun `the count stays with its name when the line wraps`() {
        val line = ServiceNames.line(listOf(service("Покраска в один цвет средний волос"), service("Покраска в один цвет средний волос")))

        assertEquals("Покраска в один цвет средний волос\u00A0×2", line)
        assertFalse(line.contains(" ×"))
    }

    @Test
    fun `names are compared without the spaces at their ends, and a blank name is left out`() {
        assertEquals("Стрижка\u00A0×2", ServiceNames.line(listOf(service("Стрижка "), service(" "), service(" Стрижка"))))
    }
}
