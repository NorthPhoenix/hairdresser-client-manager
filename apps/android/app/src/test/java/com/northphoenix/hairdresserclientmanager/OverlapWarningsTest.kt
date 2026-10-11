package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentParticipant
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.data.LocationType
import com.northphoenix.hairdresserclientmanager.state.OverlapWarnings
import org.junit.Assert.assertEquals
import org.junit.Test

/** The overlap warning of an Appointment: what the server named, kept until it is dismissed or out of date. */
class OverlapWarningsTest {
    private val warnings = OverlapWarnings()

    private fun appointment(id: String, client: String) = Appointment(
        id = id,
        primaryClientId = "client-$id",
        primaryClientName = client,
        startsAt = "2026-10-21T20:00:00.000Z",
        status = AppointmentStatus.SCHEDULED,
        locationType = LocationType.IN_SALON,
    )

    private val alice = appointment("alice", "Alice Home")
    private val bob = appointment("bob", "Bob Noaddress")
    private val wanda = appointment("wanda", "Walkin Wanda")

    private fun shown() = warnings.shown.value

    @Test
    fun `a warning names the overlapping Appointments and stays until it is dismissed`() {
        // L9: created over two others.
        warnings.answered("wanda", listOf(alice, bob))

        assertEquals(mapOf("wanda" to listOf(alice, bob)), shown())

        // Nothing but the Stylist ends it: not time, and not the warning of another Appointment.
        warnings.answered("carol", emptyList())
        assertEquals(listOf(alice, bob), shown()["wanda"])

        warnings.dismiss("wanda")
        assertEquals(emptyMap<String, List<Appointment>>(), shown())
    }

    @Test
    fun `an Appointment set back to Scheduled on a taken time has a warning, and none once it has an outcome again`() {
        // L5: Alice was Canceled, Bob took her time, Alice is Scheduled again.
        warnings.answered("alice", listOf(bob))
        assertEquals(mapOf("alice" to listOf(bob)), shown())

        // Canceled again: the server names no overlap for it.
        warnings.answered("alice", emptyList())
        assertEquals(emptyMap<String, List<Appointment>>(), shown())
    }

    @Test
    fun `a later answer about the same Appointment takes the place of the earlier one`() {
        warnings.answered("wanda", listOf(alice, bob))
        // Moved: now it overlaps Alice only.
        warnings.answered("wanda", listOf(alice))
        assertEquals(mapOf("wanda" to listOf(alice)), shown())

        // Moved to a free time.
        warnings.answered("wanda", emptyList())
        assertEquals(emptyMap<String, List<Appointment>>(), shown())
    }

    @Test
    fun `an Appointment that was moved, got an outcome or was deleted leaves the warnings that name it`() {
        warnings.answered("wanda", listOf(alice, bob))
        warnings.answered("carol", listOf(bob))

        // Bob is Completed: the server answers that he overlaps nothing.
        warnings.answered("bob", emptyList())
        // Wanda still overlaps Alice. Carol's warning named Bob alone, so it is over.
        assertEquals(mapOf("wanda" to listOf(alice)), shown())

        warnings.gone("alice")
        assertEquals(emptyMap<String, List<Appointment>>(), shown())
    }

    @Test
    fun `a deleted Appointment has no warning left`() {
        warnings.answered("wanda", listOf(alice))
        warnings.answered("alice", listOf(wanda))

        warnings.gone("wanda")

        assertEquals(emptyMap<String, List<Appointment>>(), shown())
    }

    @Test
    fun `an Appointment deleted with its only Client leaves the warnings, and one that remains is named without them`() {
        fun client(id: String, name: String) = AppointmentParticipant(clientId = id, name = name)

        val bobAlone = bob.copy(participants = listOf(client("client-bob", "Bob Noaddress")))
        val bobAndAlice = alice.copy(participants = listOf(client("client-bob", "Bob Noaddress"), client("client-alice", "Alice Home")))

        warnings.answered("wanda", listOf(bobAlone, bobAndAlice))
        warnings.answered("carol", listOf(bobAlone))
        // Named as its primary Client without being listed as one: such an Appointment is deleted with that Client as well.
        warnings.answered("dora", listOf(bob))

        warnings.clientDeleted("client-bob")

        // Bob's own Appointment went with him. The group Appointment stays, Alice's now.
        assertEquals(mapOf("wanda" to listOf(bobAndAlice.copy(participants = listOf(client("client-alice", "Alice Home"))))), shown())

        // A Client that no named Appointment has changes nothing.
        warnings.clientDeleted("client-zed")
        assertEquals(listOf("alice"), shown()["wanda"]?.map { it.id })
    }

    @Test
    fun `another Stylist starts without warnings`() {
        warnings.answered("wanda", listOf(alice))

        warnings.clear()

        assertEquals(emptyMap<String, List<Appointment>>(), shown())
    }
}
