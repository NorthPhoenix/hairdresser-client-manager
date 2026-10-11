package com.northphoenix.hairdresserclientmanager.data

import kotlinx.serialization.builtins.ListSerializer
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

    suspend fun saveServiceMenuItem(id: String?, name: String, defaultPriceCents: Long): ServiceMenuItem =
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

    /**
     * The Completed Appointments that have something to copy into [targetAppointmentId], newest
     * first, each with what a copy from it adds (`copyableServiceCount`, `copyableClientIds`). The
     * first is the one a copy takes when it names no source. None for an Appointment the server
     * no longer has: nothing can be copied into it.
     */
    suspend fun completedSources(targetAppointmentId: String): List<Appointment> =
        try {
            trpc.query("appointment.completedSources", buildJsonObject { put("targetAppointmentId", targetAppointmentId) })
        } catch (error: ApiException) {
            if (error.isNotFound && error.message == TARGET_NOT_FOUND) emptyList() else throw error
        }

    /**
     * One Appointment as the server has it now, by its id, or null when the server has no such
     * Appointment. Only the API's own answer counts as that: a "not found" of anything else on
     * the way, such as a server that does not know the procedure, is a failed request.
     */
    suspend fun findAppointment(id: String): Appointment? =
        try {
            trpc.query<Appointment>("appointment.get", idInput(id))
        } catch (error: ApiException) {
            if (error.isNotFound && error.message == APPOINTMENT_NOT_FOUND) null else throw error
        }

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

    /** A status that goes back to Scheduled can land on a time that is taken, and an in-salon Appointment then takes the salon's address of today. */
    suspend fun updateAppointmentStatus(id: String, status: AppointmentStatus): UpdatedAppointment =
        updateAppointment(id) { put("status", status.wire) }

    /**
     * Edit Appointment. The start and the end are sent together or not at all: without [startsAt]
     * both stay as they are, and with it an [endsAt] of null clears the end time.
     * [customLocationAddress] counts at home only, where empty means the primary Client's address.
     */
    suspend fun updateAppointmentTimeAndPlace(
        id: String,
        startsAt: Instant?,
        endsAt: Instant?,
        locationType: LocationType,
        customLocationAddress: String,
    ): UpdatedAppointment =
        updateAppointment(id) {
            if (startsAt != null) {
                put("startsAt", startsAt.toString())
                put("endsAt", endsAt?.toString())
            }
            put("locationType", locationType.wire)
            if (locationType == LocationType.AT_HOME) {
                put("customLocationAddress", customLocationAddress)
            }
        }

    suspend fun updateAppointmentPrimary(id: String, clientId: String) {
        updateAppointment<JsonObject>(id) { put("primaryClientId", clientId) }
    }

    suspend fun updateAppointmentNote(id: String, note: String) {
        updateAppointment<JsonObject>(id) { put("note", note) }
    }

    /** A null override clears it, so the final total follows the Services total again. */
    suspend fun updateAppointmentFinalTotal(id: String, overrideCents: Long?) {
        updateAppointment<JsonObject>(id) {
            if (overrideCents == null) put("finalTotalCentsOverride", JsonNull) else put("finalTotalCentsOverride", overrideCents)
        }
    }

    private suspend inline fun <reified T> updateAppointment(id: String, fields: JsonObjectBuilder.() -> Unit): T =
        trpc.mutation(
            "appointment.update",
            buildJsonObject {
                put("id", id)
                fields()
            },
        )

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
        priceCents: Long?,
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

    /**
     * An edit of a Service is a save of its whole form: the name, the price and the note are sent
     * together, and an empty note clears the note.
     */
    suspend fun updateService(id: String, name: String, priceCents: Long, note: String) {
        trpc.mutation<JsonObject>(
            "appointment.updateService",
            buildJsonObject {
                put("id", id)
                put("name", name)
                put("priceCents", priceCents)
                put("note", note)
            },
        )
    }

    suspend fun deleteService(id: String) {
        trpc.mutation<JsonObject>("appointment.deleteService", idInput(id))
    }

    /**
     * Copies from [sourceAppointmentId], or from the last Completed Appointment that has something
     * to copy when none is named. A copy that would add nothing is refused. [requestKey] names
     * this copy for the server (16 to 64 of `A-Z a-z 0-9 _ -`): sent again with the key of a copy
     * that was made, the call copies nothing and is answered like the first.
     * Returns how many Services the copy added, or null when the reply does not say.
     */
    suspend fun copyServices(targetAppointmentId: String, sourceAppointmentId: String?, requestKey: String): Int? =
        trpc.mutation<CopiedServices>(
            "appointment.copyServices",
            buildJsonObject {
                put("targetAppointmentId", targetAppointmentId)
                sourceAppointmentId?.let { put("sourceAppointmentId", it) }
                put("requestKey", requestKey)
            },
        ).copiedServiceCount

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

    /**
     * [requestKey] names this photo for the server (16 to 64 of `A-Z a-z 0-9 _ -`). A call that is
     * sent again with the key of a photo the Appointment already has creates nothing and is
     * answered like the first, so a try whose reply was lost can be repeated.
     * Returns the Appointment's photos as the reply states them, or null when it does not state
     * them in a form this app reads: the photo is recorded either way.
     */
    suspend fun addStoredPhoto(
        appointmentId: String,
        clientId: String,
        category: PhotoCategory,
        fileKey: String,
        url: String,
        thumbnailUrl: String,
        requestKey: String,
    ): List<AppointmentPhoto>? {
        val reply = trpc.mutation<JsonObject>(
            "appointment.addPhoto",
            buildJsonObject {
                put("appointmentId", appointmentId)
                put("clientId", clientId)
                put("category", category.wire)
                put("status", "stored")
                put("fileKey", fileKey)
                put("url", url)
                put("thumbnailUrl", thumbnailUrl)
                put("requestKey", requestKey)
            },
        )

        return try {
            reply["photos"]?.let { hcmJson.decodeFromJsonElement(ListSerializer(AppointmentPhoto.serializer()), it) }
        } catch (_: Exception) {
            null
        }
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

    private companion object {
        /** What `appointment.get` answers for an Appointment that is gone or is another Stylist's. */
        const val APPOINTMENT_NOT_FOUND = "Appointment not found."

        /** What `appointment.completedSources` answers for a target that is gone or is another Stylist's. */
        const val TARGET_NOT_FOUND = "Target Appointment not found."
    }

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
