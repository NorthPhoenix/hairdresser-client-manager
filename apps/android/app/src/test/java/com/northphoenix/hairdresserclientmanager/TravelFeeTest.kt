package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentParticipant
import com.northphoenix.hairdresserclientmanager.data.AppointmentService
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.data.LocationType
import com.northphoenix.hairdresserclientmanager.data.TravelFeeChange
import com.northphoenix.hairdresserclientmanager.state.withTravelFee
import org.junit.Assert.assertEquals
import org.junit.Test

class TravelFeeTest {
    private val cut = AppointmentService(id = "sv1", clientId = "c1", menuItemId = "m1", name = "Стрижка", priceCents = 4_000)
    private val fee = AppointmentService(id = "sv2", clientId = "c1", menuItemId = "fee", name = "Выезд на дом к клиенту", priceCents = 2_000)
    private val appointment = Appointment(
        id = "a1",
        primaryClientId = "c1",
        primaryClientName = "Анна",
        participants = listOf(
            AppointmentParticipant(clientId = "c1", name = "Анна", isPrimary = true, subtotalCents = 4_000),
            AppointmentParticipant(clientId = "c2", name = "Мила"),
        ),
        services = listOf(cut),
        serviceTotalCents = 4_000,
        finalTotalCents = 4_000,
        startsAt = "2026-10-23T15:00:00.000Z",
        status = AppointmentStatus.SCHEDULED,
        locationType = LocationType.AT_HOME,
    )

    @Test
    fun `a Travel Fee the server added is the last Service, in the totals and the primary Client's subtotal`() {
        val withFee = appointment.withTravelFee(TravelFeeChange(added = listOf(fee)))

        assertEquals(listOf(cut, fee), withFee.services)
        assertEquals(6_000L, withFee.serviceTotalCents)
        assertEquals(6_000L, withFee.finalTotalCents)
        assertEquals(listOf(6_000L, 0L), withFee.participants.map { it.subtotalCents })
        // Told twice, as by a reload that already had it: still one.
        assertEquals(withFee, withFee.withTravelFee(TravelFeeChange(added = listOf(fee))))
    }

    @Test
    fun `a Travel Fee the server removed is gone with its price, and an overridden final total stays`() {
        val overridden = appointment.withTravelFee(TravelFeeChange(added = listOf(fee))).copy(finalTotalCentsOverride = 5_000, finalTotalCents = 5_000)
        val inSalon = overridden.withTravelFee(TravelFeeChange(removed = listOf(fee)))

        assertEquals(listOf(cut), inSalon.services)
        assertEquals(4_000L, inSalon.serviceTotalCents)
        assertEquals(5_000L, inSalon.finalTotalCents)
        assertEquals(listOf(4_000L, 0L), inSalon.participants.map { it.subtotalCents })
    }

    @Test
    fun `no change of the Travel Fee changes nothing`() {
        assertEquals(appointment, appointment.withTravelFee(TravelFeeChange()))
    }
}
