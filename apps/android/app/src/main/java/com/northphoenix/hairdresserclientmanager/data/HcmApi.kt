package com.northphoenix.hairdresserclientmanager.data

import com.northphoenix.hairdresserclientmanager.domain.MenuItemValues
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

    /**
     * The Stylist of the account that is signed in. The first call of a new account creates the
     * Stylist, with the device's language and timezone; for a Stylist who exists the two are not
     * read. A device can name a timezone the server does not know (a newer list of timezones, an
     * offset in place of a name), and the server creates no Stylist with one: the Stylist is then
     * created without it, in UTC, and confirms the timezone in onboarding like every new Stylist.
     */
    suspend fun bootstrap(deviceLanguage: Language, deviceTimezone: String): Stylist =
        try {
            stylist(deviceLanguage, deviceTimezone.takeIf(String::isNotBlank))
        } catch (error: ApiException) {
            // The language is always one the server takes, so what it refuses of this input is the timezone.
            if (error.isRefusedInput && deviceTimezone.isNotBlank()) stylist(deviceLanguage, timezone = null) else throw error
        }

    private suspend fun stylist(deviceLanguage: Language, timezone: String?): Stylist =
        trpc.query(
            "stylist.bootstrap",
            buildJsonObject {
                put("deviceLanguage", deviceLanguage.tag)
                timezone?.let { put("deviceTimezone", it) }
            },
        )

    /**
     * A save sends all three settings. [loadedUpdatedAt] is the [Stylist.updatedAt] of the settings
     * the unchanged ones are taken from: when the server has another version, it refuses the save
     * ([ApiException.isChangedElsewhere]) instead of putting old settings back over newer ones.
     */
    suspend fun saveSettings(language: Language, timezone: String, salonAddress: String, loadedUpdatedAt: String?): Stylist =
        trpc.mutation(
            "stylist.saveSettings",
            buildJsonObject {
                put("language", language.tag)
                put("timezone", timezone)
                put("salonAddress", salonAddress)
                loadedUpdatedAt?.let { put("expectedUpdatedAt", it) }
            },
        )

    // --- clients ---

    suspend fun listClients(): List<ClientProfile> = trpc.query("clientProfile.list", JsonObject(emptyMap()))

    /**
     * A save is the whole form. For a Client who exists ([id]), [loadedUpdatedAt] is the
     * [ClientProfile.updatedAt] of the Client the form was filled from: when the server has
     * another version, it refuses the save ([ApiException.isChangedElsewhere]) instead of putting
     * old fields back over newer ones. For a new Client, [requestKey] names this creation (see
     * [REQUEST_KEY]). Each is sent only in its own case. [referredById] is who recommended the Client,
     * null for nobody: the form states it, so a save from it clears it.
     */
    suspend fun saveClient(
        id: String?,
        name: String,
        language: Language,
        phone: String,
        email: String,
        address: String,
        note: String,
        loadedUpdatedAt: String?,
        requestKey: String,
        referredById: String? = null,
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
                put("referredById", referredById)
                if (id == null) put(REQUEST_KEY, requestKey) else loadedUpdatedAt?.let { put("expectedUpdatedAt", it) }
            },
        )

    suspend fun deleteClient(id: String) {
        trpc.mutation<JsonObject>("clientProfile.delete", idInput(id))
    }

    /**
     * Makes [removeId] one with [keepId]: their Appointments, Services, photos and Profile Share go to the kept
     * Client, who takes the details they do not have, and [removeId] is deleted. All of it or nothing. The same
     * merge sent again answers with the kept Client as they are now.
     */
    suspend fun mergeClients(keepId: String, removeId: String): ClientMergeResult =
        trpc.mutation(
            "clientProfile.merge",
            buildJsonObject {
                put("keepId", keepId)
                put("removeId", removeId)
            },
        )

    /** The two Clients are different people although their phone numbers match. Saying it again changes nothing. */
    suspend fun markClientsDistinct(clientId: String, otherClientId: String) {
        trpc.mutation<JsonObject>(
            "clientProfile.markDistinct",
            buildJsonObject {
                put("clientId", clientId)
                put("otherClientId", otherClientId)
            },
        )
    }

    suspend fun clientAppointmentHistory(clientId: String): List<ClientHistoryEntry> =
        trpc.query("clientProfile.appointmentHistory", idInput(clientId))

    /**
     * Answers the Client's Profile Share. When the Client already had one, that is the answer as
     * it is, with its own choices and not [included].
     */
    suspend fun createProfileShare(clientId: String, included: Set<ShareChoice>): ProfileShare =
        trpc.mutation(
            "clientProfile.createProfileShare",
            buildJsonObject {
                put("clientId", clientId)
                ShareChoice.entries.forEach { put(it.field, it in included) }
            },
        )

    /** Changes one choice of the Client's Profile Share. Its link stays the same. */
    suspend fun updateProfileShare(clientId: String, choice: ShareChoice, included: Boolean): ProfileShare =
        trpc.mutation(
            "clientProfile.updateProfileShare",
            buildJsonObject {
                put("clientId", clientId)
                put(choice.field, included)
            },
        )

    suspend fun revokeProfileShare(clientId: String) {
        trpc.mutation<JsonObject>("clientProfile.revokeProfileShare", buildJsonObject { put("clientId", clientId) })
    }

    /** The picture shows what the Client's Profile Share shows: the server takes the choices from it. */
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

    /** The names, as typed, of the Services that have a Color Formula, each once. */
    suspend fun listFormulaNames(): List<String> = trpc.query("serviceMenu.formulaNames")

    /**
     * Saves the whole editor of a Service Menu Item: an empty group clears it, and marking it the
     * Travel Fee unmarks any other item. [requestKey] names
     * the creation of a new one (see [REQUEST_KEY]); an edit ([id]) does not send it.
     */
    suspend fun saveServiceMenuItem(id: String?, values: MenuItemValues, requestKey: String): ServiceMenuItem =
        trpc.mutation(
            "serviceMenu.save",
            buildJsonObject {
                id?.let { put("id", it) }
                put("name", values.name)
                put("defaultPriceCents", values.defaultPriceCents)
                put("group", values.group)
                put("travelFee", values.travelFee)
                if (id == null) put(REQUEST_KEY, requestKey)
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
        requestKey: String,
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
                put(REQUEST_KEY, requestKey)
            },
        )

    /** A status that goes back to Scheduled can land on a time that is taken, and an in-salon Appointment then takes the salon's address of today. */
    suspend fun updateAppointmentStatus(id: String, status: AppointmentStatus): UpdatedAppointment =
        updateAppointment(id) { put("status", status.wire) }

    /**
     * Edit Appointment. The start and the end are sent together or not at all: without [startsAt]
     * both stay as they are, and with it an [endsAt] of null clears the end time. Without
     * [locationType] the place stays as it is, address included.
     * [customLocationAddress] counts at home only, where empty means the primary Client's address.
     */
    suspend fun updateAppointmentTimeAndPlace(
        id: String,
        startsAt: Instant?,
        endsAt: Instant?,
        locationType: LocationType?,
        customLocationAddress: String,
    ): UpdatedAppointment =
        updateAppointment(id) {
            if (startsAt != null) {
                put("startsAt", startsAt.toString())
                put("endsAt", endsAt?.toString())
            }
            if (locationType != null) {
                put("locationType", locationType.wire)
            }
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

    /** A null tip clears it: no tip is recorded then. The price of the Services stays as it is. */
    suspend fun updateAppointmentTip(id: String, tipCents: Long?) {
        updateAppointment<JsonObject>(id) { putAmount("tipCents", tipCents) }
    }

    /**
     * The custom total and the tip in one save, such as the difference of a custom total above the
     * Services moved into the tip: both are the Appointment's together, or neither is.
     */
    suspend fun updateAppointmentTotalAndTip(id: String, overrideCents: Long?, tipCents: Long?) {
        updateAppointment<JsonObject>(id) {
            putAmount("finalTotalCentsOverride", overrideCents)
            putAmount("tipCents", tipCents)
        }
    }

    private fun JsonObjectBuilder.putAmount(key: String, cents: Long?) {
        if (cents == null) put(key, JsonNull) else put(key, cents)
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
        requestKey: String,
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
                put(REQUEST_KEY, requestKey)
            },
        )
    }

    /**
     * Adds a Service from each of [menuItemIds] for [clientId], at the items' default prices and
     * in this order, all in one request: all are added or none. [requestKey] names this addition
     * (see [REQUEST_KEY]).
     */
    suspend fun addMenuServices(appointmentId: String, clientId: String, menuItemIds: List<String>, requestKey: String) =
        addMenuServices(appointmentId, listOf(clientId to menuItemIds), requestKey)

    /**
     * Adds a Service from each picked menu item for each Client of [picks], at the items' default
     * prices, Client after Client and each one's items in their order, all in one request: all are
     * added or none. Picks for one Client go in the shape the API has always taken, so that they
     * also work with an API from before several Clients at once.
     */
    suspend fun addMenuServices(appointmentId: String, picks: List<Pair<String, List<String>>>, requestKey: String) {
        fun ids(menuItemIds: List<String>) = buildJsonArray { menuItemIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }

        trpc.mutation<JsonObject>(
            "appointment.addMenuServices",
            buildJsonObject {
                put("appointmentId", appointmentId)
                val single = picks.singleOrNull()
                if (single != null) {
                    put("clientId", single.first)
                    put("menuItemIds", ids(single.second))
                } else {
                    put(
                        "clients",
                        buildJsonArray {
                            picks.forEach { (clientId, menuItemIds) ->
                                add(
                                    buildJsonObject {
                                        put("clientId", clientId)
                                        put("menuItemIds", ids(menuItemIds))
                                    },
                                )
                            }
                        },
                    )
                }
                put(REQUEST_KEY, requestKey)
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
     * this copy (see [REQUEST_KEY]): sent again with the key of a copy that was made, the call
     * copies nothing and is answered like the first.
     * Returns how many Services the copy added, or null when the reply does not say.
     */
    suspend fun copyServices(targetAppointmentId: String, sourceAppointmentId: String?, requestKey: String): Int? =
        trpc.mutation<CopiedServices>(
            "appointment.copyServices",
            buildJsonObject {
                put("targetAppointmentId", targetAppointmentId)
                sourceAppointmentId?.let { put("sourceAppointmentId", it) }
                put(REQUEST_KEY, requestKey)
            },
        ).copiedServiceCount

    suspend fun addColorFormula(appointmentServiceId: String, formula: String, placement: String?, requestKey: String) {
        trpc.mutation<JsonObject>(
            "appointment.addColorFormula",
            buildJsonObject {
                put("appointmentServiceId", appointmentServiceId)
                put("formula", formula)
                putIfNotBlank("placement", placement)
                put(REQUEST_KEY, requestKey)
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
     * [requestKey] names this photo (see [REQUEST_KEY]). A call that is sent again with the key of
     * a photo the Appointment already has creates nothing and is answered like the first, so a try
     * whose reply was lost can be repeated.
     * Returns the Appointment's photos as the reply states them, or null when it does not state
     * them in a form this app reads: the photo is recorded either way.
     */
    suspend fun addStoredPhoto(
        appointmentId: String,
        clientId: String,
        category: PhotoCategory,
        stored: StoredFile,
        requestKey: String,
    ): List<AppointmentPhoto>? {
        val reply = trpc.mutation<JsonObject>(
            "appointment.addPhoto",
            buildJsonObject {
                put("appointmentId", appointmentId)
                put("clientId", clientId)
                put("category", category.wire)
                put("status", "stored")
                put("fileKey", stored.fileKey)
                put("url", stored.url)
                put("thumbnailUrl", stored.thumbnailUrl)
                // Both or neither: a photo is never recorded with one side only.
                if (stored.width != null && stored.height != null) {
                    put("width", stored.width)
                    put("height", stored.height)
                }
                put(REQUEST_KEY, requestKey)
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
        /**
         * The input field that names one intended creation, chosen by the app: 16 to 64 of
         * `A-Z a-z 0-9 _ -`, a UUID fits. A creating call sent again under the key of a creation
         * the server has carried out creates nothing more and is answered like the first, so a try
         * whose reply never came can be repeated. The same key with other values is refused
         * ([ApiException.isMadeWithOtherValues]). One key per form or sheet that is opened, kept
         * for every try from it.
         */
        const val REQUEST_KEY = "requestKey"

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
