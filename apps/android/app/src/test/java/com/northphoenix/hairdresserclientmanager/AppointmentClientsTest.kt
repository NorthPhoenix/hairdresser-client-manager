package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.AppointmentParticipant
import com.northphoenix.hairdresserclientmanager.data.AppointmentPhoto
import com.northphoenix.hairdresserclientmanager.data.AppointmentService
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus
import com.northphoenix.hairdresserclientmanager.data.ClientProfile
import com.northphoenix.hairdresserclientmanager.data.Language
import com.northphoenix.hairdresserclientmanager.data.LocationType
import com.northphoenix.hairdresserclientmanager.data.PhotoCategory
import com.northphoenix.hairdresserclientmanager.data.PhotoStatus
import com.northphoenix.hairdresserclientmanager.domain.AppointmentClients
import com.northphoenix.hairdresserclientmanager.domain.AppointmentClients.Fault
import com.northphoenix.hairdresserclientmanager.domain.AppointmentClients.Kept
import com.northphoenix.hairdresserclientmanager.i18n.messageRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppointmentClientsTest {
    private val ann = AppointmentParticipant(clientId = "ann", name = "Ann", isPrimary = true)
    private val bea = AppointmentParticipant(clientId = "bea", name = "Bea")
    private val cara = AppointmentParticipant(clientId = "cara", name = "Cara")

    /** An Appointment whose primary Client on the server is Ann, with [clients] as the server lists them. */
    private fun appointment(
        vararg clients: AppointmentParticipant,
        services: List<AppointmentService> = emptyList(),
        photos: List<AppointmentPhoto> = emptyList(),
    ) = Appointment(
        id = "a1",
        primaryClientId = "ann",
        primaryClientName = "Ann",
        participants = clients.toList(),
        services = services,
        photos = photos,
        startsAt = "2026-10-12T15:00:00.000Z",
        status = AppointmentStatus.SCHEDULED,
        locationType = LocationType.IN_SALON,
    )

    private fun service(clientId: String) = AppointmentService(id = "s-$clientId", clientId = clientId, name = "Cut", priceCents = 1_000)

    private fun photo(clientId: String, status: PhotoStatus = PhotoStatus.STORED) =
        AppointmentPhoto(id = "p-$clientId", clientId = clientId, category = PhotoCategory.AFTER, status = status)

    @Test
    fun `an Appointment whose primary Client is one of its Clients has nothing to put right`() {
        assertNull(AppointmentClients.fault(appointment(ann)))
        assertNull(AppointmentClients.fault(appointment(ann, bea)))
    }

    @Test
    fun `an Appointment left without Clients is told from one whose Clients do not include the primary one`() {
        // Both Clients removed within a second, before the server ran such changes one at a time.
        assertEquals(Fault.NO_CLIENTS, AppointmentClients.fault(appointment()))
        // 'Make primary' on Ann and her removal at the same moment: Bea is listed, and nobody is primary.
        assertEquals(Fault.NO_PRIMARY, AppointmentClients.fault(appointment(bea)))
        assertEquals(Fault.NO_PRIMARY, AppointmentClients.fault(appointment(bea, cara)))
    }

    @Test
    fun `what keeps a Client in an Appointment is named, in the order the server checks`() {
        val busy = appointment(ann, bea, services = listOf(service("bea")), photos = listOf(photo("bea")))

        assertEquals(Kept.SERVICES, AppointmentClients.keptBy(busy, "bea", hasUnsavedPhotos = true))
        assertEquals(Kept.PHOTOS, AppointmentClients.keptBy(appointment(ann, bea, photos = listOf(photo("bea"))), "bea", hasUnsavedPhotos = true))
        // A photo whose upload failed on the server keeps its Client as well: it is removed first.
        assertEquals(Kept.PHOTOS, AppointmentClients.keptBy(appointment(ann, bea, photos = listOf(photo("bea", PhotoStatus.FAILED))), "bea", hasUnsavedPhotos = false))
        assertEquals(Kept.UNSAVED_PHOTOS, AppointmentClients.keptBy(appointment(ann, bea), "bea", hasUnsavedPhotos = true))

        // What another Client has in the Appointment keeps nobody else.
        assertNull(AppointmentClients.keptBy(busy, "ann", hasUnsavedPhotos = false))
        assertNull(AppointmentClients.keptBy(appointment(ann, bea), "bea", hasUnsavedPhotos = false))
    }

    @Test
    fun `each thing that keeps a Client has its own message, the server's for Services and photos`() {
        assertEquals(R.string.error_client_has_services, Kept.SERVICES.messageRes())
        assertEquals(R.string.error_client_has_photos, Kept.PHOTOS.messageRes())
        assertEquals(R.string.error_client_has_photo_uploads, Kept.UNSAVED_PHOTOS.messageRes())
    }

    @Test
    fun `a new Service is for the Client who was picked while they are in the Appointment, and for the primary Client otherwise`() {
        assertEquals("ann", AppointmentClients.serviceClientId(appointment(ann, bea), picked = null))
        assertEquals("bea", AppointmentClients.serviceClientId(appointment(ann, bea), picked = "bea"))
        // Bea was removed from the Appointment while the sheet was open.
        assertEquals("ann", AppointmentClients.serviceClientId(appointment(ann), picked = "bea"))
        // The primary Client is not the first one listed here, and is still the one a Service is for.
        assertEquals("ann", AppointmentClients.serviceClientId(appointment(bea, ann), picked = null))
    }

    @Test
    fun `a new Service of an Appointment without its primary Client is for a Client who is in it`() {
        // Ann is primary on the server and not a Client of the Appointment: the server refuses a Service for her.
        assertEquals("bea", AppointmentClients.serviceClientId(appointment(bea, cara), picked = null))
        assertEquals("cara", AppointmentClients.serviceClientId(appointment(bea, cara), picked = "cara"))
    }

    @Test
    fun `an Appointment goes by its primary Client, and by a Client who is in it when the primary one is not`() {
        val group = appointment(ann, bea, cara)

        assertEquals("Ann", AppointmentClients.leadName(group))
        assertEquals(2, AppointmentClients.othersCount(group))
        assertEquals("Ann", AppointmentClients.leadName(appointment(ann)))
        assertEquals(0, AppointmentClients.othersCount(appointment(ann)))

        // Ann was removed and is still primary on the server: the card names Bea, not "Ann +1".
        assertEquals("Bea", AppointmentClients.leadName(appointment(bea)))
        assertEquals(0, AppointmentClients.othersCount(appointment(bea)))
        assertEquals("Bea", AppointmentClients.leadName(appointment(bea, cara)))
        assertEquals(1, AppointmentClients.othersCount(appointment(bea, cara)))

        // Nobody is in it: the name the server has for it is all there is.
        assertEquals("Ann", AppointmentClients.leadName(appointment()))
        assertEquals(0, AppointmentClients.othersCount(appointment()))
    }

    @Test
    fun `the Clients picked for a new Appointment are listed in the order they were picked`() {
        fun client(id: String) = ClientProfile(id = id, name = id, language = Language.EN)
        // The Clients list has its own order.
        val clients = listOf(client("anna"), client("bob"), client("cara"), client("dana"))

        assertEquals(listOf("cara", "bob", "anna"), AppointmentClients.inPickedOrder(listOf("cara", "bob", "anna"), clients).map { it.id })
        // A Client who was deleted since they were picked is not listed.
        assertEquals(listOf("dana"), AppointmentClients.inPickedOrder(listOf("gone", "dana"), clients).map { it.id })
    }

    @Test
    fun `a Client who was deleted elsewhere is no longer picked for a new Appointment`() {
        fun client(id: String) = ClientProfile(id = id, name = id, language = Language.EN)
        val clients = listOf(client("anna"), client("bob"), client("cara"))

        // A8: the server refuses an Appointment that names a Client it no longer has, and the form shows no chip for them to take off.
        assertEquals(listOf("cara", "anna"), AppointmentClients.stillListed(listOf("cara", "gone", "anna"), clients))
        assertEquals(listOf("bob"), AppointmentClients.stillListed(listOf("bob"), clients))
        assertEquals(emptyList<String>(), AppointmentClients.stillListed(listOf("gone"), clients))
        assertEquals(emptyList<String>(), AppointmentClients.stillListed(emptyList(), clients))
    }

    @Test
    fun `deleting a Client changes the Appointments they are in and those that name them as primary`() {
        val solo = appointment(ann).copy(id = "solo")
        val group = appointment(ann, bea).copy(id = "group")
        val others = appointment(bea, cara).copy(id = "others", primaryClientId = "bea")
        // A7: left behind by two changes at the same moment. The server puts these right when the Client is deleted.
        val primaryNotListed = appointment(bea).copy(id = "primary-not-listed")
        val nobody = appointment().copy(id = "nobody")
        val loaded = listOf(solo, group, others, primaryNotListed, nobody)

        assertEquals(listOf("solo", "group", "primary-not-listed", "nobody"), AppointmentClients.changedByDeleting("ann", loaded).map { it.id })
        assertEquals(listOf("group", "others", "primary-not-listed"), AppointmentClients.changedByDeleting("bea", loaded).map { it.id })
        assertEquals(emptyList<String>(), AppointmentClients.changedByDeleting("dana", loaded).map { it.id })

        // An Appointment goes with its last Client; one that still has someone stays, with another primary Client.
        assertEquals(
            listOf("solo", "nobody"),
            AppointmentClients.changedByDeleting("ann", loaded).filter { AppointmentClients.goesWith("ann", it) }.map { it.id },
        )
        // Bea is the only Client of the Appointment that names Ann as primary without listing her: it goes with Bea, as on the server.
        assertEquals(
            listOf("primary-not-listed"),
            AppointmentClients.changedByDeleting("bea", loaded).filter { AppointmentClients.goesWith("bea", it) }.map { it.id },
        )
    }
}
