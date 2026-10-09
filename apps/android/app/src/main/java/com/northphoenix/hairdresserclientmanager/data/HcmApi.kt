package com.northphoenix.hairdresserclientmanager.data

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/** Typed wrappers over the tRPC procedures in packages/api. Optional inputs are omitted, not sent as null. */
class HcmApi(private val trpc: TrpcClient) {
    // --- stylist ---

    suspend fun bootstrap(deviceLanguage: Language, deviceTimezone: String): Stylist =
        trpc.query(
            "stylist.bootstrap",
            buildJsonObject {
                put("deviceLanguage", deviceLanguage.tag)
                putIfNotBlank("deviceTimezone", deviceTimezone)
            },
        )

    suspend fun saveSettings(language: Language, timezone: String, salonAddress: String): Stylist =
        trpc.mutation(
            "stylist.saveSettings",
            buildJsonObject {
                put("language", language.tag)
                put("timezone", timezone)
                put("salonAddress", salonAddress)
            },
        )

    // --- clients ---

    suspend fun listClients(): List<ClientProfile> = trpc.query("clientProfile.list", JsonObject(emptyMap()))

    suspend fun saveClient(
        id: String?,
        name: String,
        language: Language,
        phone: String,
        email: String,
        address: String,
        note: String,
    ): ClientProfile =
        trpc.mutation(
            "clientProfile.save",
            buildJsonObject {
                id?.let { put("id", it) }
                put("name", name)
                put("language", language.tag)
                put("phone", phone)
                put("email", email)
                put("address", address)
                put("note", note)
            },
        )

    suspend fun deleteClient(id: String) {
        trpc.mutation<JsonObject>("clientProfile.delete", idInput(id))
    }

    suspend fun clientAppointmentHistory(clientId: String): List<ClientHistoryEntry> =
        trpc.query("clientProfile.appointmentHistory", idInput(clientId))

    suspend fun createProfileShare(clientId: String): ProfileShare =
        trpc.mutation("clientProfile.createProfileShare", buildJsonObject { put("clientId", clientId) })

    suspend fun revokeProfileShare(clientId: String) {
        trpc.mutation<JsonObject>("clientProfile.revokeProfileShare", buildJsonObject { put("clientId", clientId) })
    }

    suspend fun buildProfileShareImage(clientId: String, language: Language): ShareImage =
        trpc.mutation(
            "clientProfile.buildProfileShareImage",
            buildJsonObject {
                put("clientId", clientId)
                put("language", language.tag)
            },
        )

    // --- service menu ---

    suspend fun listServiceMenu(): List<ServiceMenuItem> = trpc.query("serviceMenu.list")

    suspend fun saveServiceMenuItem(id: String?, name: String, defaultPriceCents: Int): ServiceMenuItem =
        trpc.mutation(
            "serviceMenu.save",
            buildJsonObject {
                id?.let { put("id", it) }
                put("name", name)
                put("defaultPriceCents", defaultPriceCents)
            },
        )

    suspend fun deleteServiceMenuItem(id: String) {
        trpc.mutation<JsonObject>("serviceMenu.delete", idInput(id))
    }

    // --- appointments ---

    suspend fun listAppointments(start: Instant, end: Instant): List<Appointment> =
        trpc.query("appointment.list", rangeInput(start, end))

    suspend fun homeAppointments(start: Instant, end: Instant): List<Appointment> =
        trpc.query("appointment.home", rangeInput(start, end))

    suspend fun completedSources(): List<Appointment> = trpc.query("appointment.completedSources")

    suspend fun createAppointment(
        primaryClientId: String?,
        additionalClientIds: List<String>,
        inlineClientName: String?,
        startsAt: Instant,
        endsAt: Instant?,
        locationType: LocationType,
        customLocationAddress: String?,
    ): CreateAppointmentResult =
        trpc.mutation(
            "appointment.create",
            buildJsonObject {
                primaryClientId?.let { put("primaryClientId", it) }
                put("additionalClientIds", buildJsonArray { additionalClientIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                putIfNotBlank("inlineClientName", inlineClientName)
                put("startsAt", startsAt.toString())
                endsAt?.let { put("endsAt", it.toString()) }
                put("locationType", locationType.wire)
                putIfNotBlank("customLocationAddress", customLocationAddress)
            },
        )

    suspend fun updateAppointmentStatus(id: String, status: AppointmentStatus) {
        updateAppointment(id) { put("status", status.wire) }
    }

    suspend fun updateAppointmentPrimary(id: String, clientId: String) {
        updateAppointment(id) { put("primaryClientId", clientId) }
    }

    suspend fun updateAppointmentNote(id: String, note: String) {
        updateAppointment(id) { put("note", note) }
    }

    /** A null override clears it, so the final total follows the Services total again. */
    suspend fun updateAppointmentFinalTotal(id: String, overrideCents: Int?) {
        updateAppointment(id) {
            if (overrideCents == null) put("finalTotalCentsOverride", JsonNull) else put("finalTotalCentsOverride", overrideCents)
        }
    }

    private suspend fun updateAppointment(id: String, fields: JsonObjectBuilder.() -> Unit) {
        trpc.mutation<JsonObject>(
            "appointment.update",
            buildJsonObject {
                put("id", id)
                fields()
            },
        )
    }

    suspend fun deleteAppointment(id: String) {
        trpc.mutation<JsonObject>("appointment.delete", idInput(id))
    }

    suspend fun addParticipant(appointmentId: String, clientId: String) {
        trpc.mutation<JsonObject>("appointment.addParticipant", participantInput(appointmentId, clientId))
    }

    suspend fun removeParticipant(appointmentId: String, clientId: String) {
        trpc.mutation<JsonObject>("appointment.removeParticipant", participantInput(appointmentId, clientId))
    }

    suspend fun addService(
        appointmentId: String,
        clientId: String,
        menuItemId: String?,
        name: String?,
        priceCents: Int?,
        note: String?,
    ) {
        trpc.mutation<JsonObject>(
            "appointment.addService",
            buildJsonObject {
                put("appointmentId", appointmentId)
                put("clientId", clientId)
                menuItemId?.let { put("menuItemId", it) }
                putIfNotBlank("name", name)
                priceCents?.let { put("priceCents", it) }
                putIfNotBlank("note", note)
            },
        )
    }

    suspend fun deleteService(id: String) {
        trpc.mutation<JsonObject>("appointment.deleteService", idInput(id))
    }

    suspend fun copyServices(targetAppointmentId: String, sourceAppointmentId: String?) {
        trpc.mutation<JsonObject>(
            "appointment.copyServices",
            buildJsonObject {
                put("targetAppointmentId", targetAppointmentId)
                sourceAppointmentId?.let { put("sourceAppointmentId", it) }
            },
        )
    }

    suspend fun addColorFormula(appointmentServiceId: String, formula: String, placement: String?) {
        trpc.mutation<JsonObject>(
            "appointment.addColorFormula",
            buildJsonObject {
                put("appointmentServiceId", appointmentServiceId)
                put("formula", formula)
                putIfNotBlank("placement", placement)
            },
        )
    }

    suspend fun updateColorFormula(id: String, formula: String, placement: String?) {
        trpc.mutation<JsonObject>(
            "appointment.updateColorFormula",
            buildJsonObject {
                put("id", id)
                put("formula", formula)
                putIfNotBlank("placement", placement)
            },
        )
    }

    suspend fun deleteColorFormula(id: String) {
        trpc.mutation<JsonObject>("appointment.deleteColorFormula", idInput(id))
    }

    suspend fun addStoredPhoto(
        appointmentId: String,
        clientId: String,
        category: PhotoCategory,
        fileKey: String,
        url: String,
        thumbnailUrl: String,
    ) {
        trpc.mutation<JsonObject>(
            "appointment.addPhoto",
            buildJsonObject {
                put("appointmentId", appointmentId)
                put("clientId", clientId)
                put("category", category.wire)
                put("status", "stored")
                put("fileKey", fileKey)
                put("url", url)
                put("thumbnailUrl", thumbnailUrl)
            },
        )
    }

    suspend fun updatePhotoCategory(id: String, category: PhotoCategory) {
        trpc.mutation<JsonObject>(
            "appointment.updatePhoto",
            buildJsonObject {
                put("id", id)
                put("category", category.wire)
            },
        )
    }

    suspend fun deletePhoto(id: String) {
        trpc.mutation<JsonObject>("appointment.deletePhoto", idInput(id))
    }

    private fun idInput(id: String) = buildJsonObject { put("id", id) }

    private fun participantInput(appointmentId: String, clientId: String) =
        buildJsonObject {
            put("appointmentId", appointmentId)
            put("clientId", clientId)
        }

    private fun rangeInput(start: Instant, end: Instant) =
        buildJsonObject {
            put("start", start.toString())
            put("end", end.toString())
        }

    private fun JsonObjectBuilder.putIfNotBlank(key: String, value: String?) {
        if (!value.isNullOrBlank()) {
            put(key, value)
        }
    }
}
