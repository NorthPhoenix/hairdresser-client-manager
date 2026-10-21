package com.northphoenix.hairdresserclientmanager.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire models for the tRPC API in packages/api. Field names match the JSON exactly.

@Serializable
enum class Language(val tag: String) {
    @SerialName("ru")
    RU("ru"),

    @SerialName("en")
    EN("en"),
}

@Serializable
enum class AppointmentStatus(val wire: String) {
    @SerialName("scheduled")
    SCHEDULED("scheduled"),

    @SerialName("completed")
    COMPLETED("completed"),

    @SerialName("canceled")
    CANCELED("canceled"),

    @SerialName("noShow")
    NO_SHOW("noShow"),
}

@Serializable
enum class LocationType(val wire: String) {
    @SerialName("inSalon")
    IN_SALON("inSalon"),

    @SerialName("atHome")
    AT_HOME("atHome"),
}

@Serializable
enum class PhotoCategory(val wire: String) {
    @SerialName("before")
    BEFORE("before"),

    @SerialName("after")
    AFTER("after"),

    @SerialName("other")
    OTHER("other"),
}

@Serializable
enum class PhotoStatus {
    @SerialName("pendingUpload")
    PENDING_UPLOAD,

    @SerialName("stored")
    STORED,

    @SerialName("failed")
    FAILED,
}

@Serializable
data class Stylist(
    val id: String,
    val language: Language,
    val timezone: String,
    val salonAddress: String = "",
    val onboardingCompletedAt: String? = null,
    /** When the settings were last saved, as the server states it: the version a save names (see [HcmApi.saveSettings]). */
    val updatedAt: String? = null,
    /**
     * Whether the server has photo storage. It is about the server, not the Stylist: without it no
     * Appointment Photo can be added, and the app says so instead of offering an upload that cannot
     * work. A server that does not say (an older one) has it.
     */
    val photoStorageConfigured: Boolean = true,
) {
    val onboardingComplete: Boolean get() = onboardingCompletedAt != null
}

/**
 * What a Profile Share can show of the last completed Appointment, each for the Stylist to include
 * or leave out. [field] is its name in the API; [default] is what a new Profile Share starts with.
 */
enum class ShareChoice(val field: String, val default: Boolean) {
    COLOR_FORMULAS("includeColorFormulas", true),
    AFTER_PHOTOS("includeAfterPhotos", true),

    /** Before photos and other photos: one choice for both kinds. */
    OTHER_PHOTOS("includeOtherPhotos", false),
    ;

    companion object {
        val defaults: Set<ShareChoice> = entries.filter { it.default }.toSet()
    }
}

@Serializable
data class ProfileShare(
    val id: String,
    val token: String,
    val language: Language,
    val createdAt: String,
    val includeColorFormulas: Boolean = ShareChoice.COLOR_FORMULAS.default,
    val includeAfterPhotos: Boolean = ShareChoice.AFTER_PHOTOS.default,
    val includeOtherPhotos: Boolean = ShareChoice.OTHER_PHOTOS.default,
) {
    /** What the Stylist chose to show. */
    val included: Set<ShareChoice>
        get() = setOfNotNull(
            ShareChoice.COLOR_FORMULAS.takeIf { includeColorFormulas },
            ShareChoice.AFTER_PHOTOS.takeIf { includeAfterPhotos },
            ShareChoice.OTHER_PHOTOS.takeIf { includeOtherPhotos },
        )
}

@Serializable
data class ClientProfile(
    val id: String,
    val name: String,
    val language: Language,
    val phone: String = "",
    val email: String = "",
    val address: String = "",
    val note: String = "",
    val activeProfileShare: ProfileShare? = null,
    /** The Client who recommended this one ("Посоветовала"), and their name as the server last stated it. Null: nobody. */
    val referredById: String? = null,
    val referredByName: String? = null,
    /** When the Client Profile was last saved, as the server states it: the version a save names (see [HcmApi.saveClient]). */
    val updatedAt: String? = null,
    /**
     * The other Clients the Stylist has said this one is not, although their phone numbers match. Only the
     * list says: null in the reply to a save, which does not change it.
     */
    val distinctFromIds: List<String>? = null,
)

/** What a merge of two Clients has left: the kept Client as they are now, and the Appointments it changed. */
@Serializable
data class ClientMergeResult(
    val client: ClientProfile,
    val removedId: String,
    val appointmentIds: List<String> = emptyList(),
)

// Every amount is whole cents in a Long: the totals of one Appointment can be more than an Int holds.

@Serializable
data class ServiceMenuItem(
    val id: String,
    val name: String,
    val defaultPriceCents: Long,
    /** One line, such as "Покраска в один цвет": the items of a group are its variants. Null: no group. */
    val group: String? = null,
    /** How many Services were started from this item. Only the menu's list says; a saved item does not. */
    val useCount: Int = 0,
    /** Whether this is the Travel Fee: added by itself to a Scheduled Appointment at home. At most one item is. */
    val travelFee: Boolean = false,
)

@Serializable
data class AppointmentParticipant(
    val clientId: String,
    val name: String,
    val address: String = "",
    val phone: String = "",
    val language: Language = Language.RU,
    val isPrimary: Boolean = false,
    val subtotalCents: Long = 0,
)

@Serializable
data class ColorFormula(
    val id: String,
    val formula: String,
    val placement: String = "",
)

@Serializable
data class AppointmentService(
    val id: String,
    val clientId: String,
    val menuItemId: String? = null,
    val name: String,
    val priceCents: Long,
    val note: String = "",
    val colorFormulas: List<ColorFormula> = emptyList(),
)

/**
 * A failed upload is a state without a reason: the app words it itself, in the Stylist's language.
 * Whatever text a reply may still carry for it is not read.
 */
@Serializable
data class AppointmentPhoto(
    val id: String,
    val clientId: String,
    val category: PhotoCategory,
    val status: PhotoStatus = PhotoStatus.STORED,
    val url: String = "",
    val thumbnailUrl: String = "",
)

@Serializable
data class Appointment(
    val id: String,
    val primaryClientId: String,
    val primaryClientName: String,
    val participants: List<AppointmentParticipant> = emptyList(),
    val services: List<AppointmentService> = emptyList(),
    val photos: List<AppointmentPhoto> = emptyList(),
    val serviceTotalCents: Long = 0,
    val finalTotalCents: Long = 0,
    val finalTotalCentsOverride: Long? = null,
    /**
     * The tip for the whole visit, apart from the price of the Services: [finalTotalCents] does not
     * include it. Null when no tip is recorded, and from a server that does not know tips.
     */
    val tipCents: Long? = null,
    val startsAt: String,
    val endsAt: String? = null,
    val status: AppointmentStatus,
    val locationType: LocationType,
    val locationAddress: String = "",
    /** The at-home address the Stylist typed. Empty when [locationAddress] is the salon's or the primary Client's. */
    val customLocationAddress: String = "",
    val note: String = "",
    val mapUrl: String? = null,
    /**
     * Only in the list of what can be copied into one Appointment (`appointment.completedSources`
     * for that target): how many Services a copy from this one adds to it.
     */
    val copyableServiceCount: Int = 0,
    /** Only in that list too: the Clients whose Services are copied, those attached to both Appointments. */
    val copyableClientIds: List<String> = emptyList(),
)

/** What the app reads of the reply of `appointment.copyServices`: how many Services the copy added. */
@Serializable
data class CopiedServices(val copiedServiceCount: Int? = null)

/**
 * What the app reads of the reply of `appointment.update`: the time and the place as they are
 * saved, and the other Scheduled Appointments the Appointment overlaps now that it was moved or
 * set back to Scheduled ([conflicts], which name an Appointment and have no Services or photos).
 */
@Serializable
data class UpdatedAppointment(
    val startsAt: String,
    val endsAt: String? = null,
    val locationType: LocationType,
    val locationAddress: String = "",
    val customLocationAddress: String = "",
    val mapUrl: String? = null,
    val conflicts: List<Appointment> = emptyList(),
    val travelFee: TravelFeeChange = TravelFeeChange(),
)

@Serializable
data class CreateAppointmentResult(
    val appointment: Appointment,
    val conflicts: List<Appointment> = emptyList(),
    val travelFee: TravelFeeChange = TravelFeeChange(),
)

/**
 * What the server did with the Travel Fee when an Appointment was created or changed place: the
 * Services it [added] (at home) or [removed] (back in the salon). Both empty when it did nothing,
 * also from a server that has no Travel Fee.
 */
@Serializable
data class TravelFeeChange(
    val added: List<AppointmentService> = emptyList(),
    val removed: List<AppointmentService> = emptyList(),
)

/**
 * One Appointment in a Client's Appointment history. [services] and [photos] are that Client's
 * alone, also in a group Appointment, and every photo is a stored one.
 */
@Serializable
data class ClientHistoryEntry(
    val id: String,
    val primaryClientName: String,
    val startsAt: String,
    val status: AppointmentStatus,
    /** Null from a server that does not say (an older one). */
    val primaryClientId: String? = null,
    /** Where it was, as on [Appointment]. Null from a server that does not say (an older one). */
    val locationType: LocationType? = null,
    val locationAddress: String = "",
    val customLocationAddress: String = "",
    /** Every Client of it, the primary one first. Empty from a server that does not say (an older one). */
    val participantClientIds: List<String> = emptyList(),
    /** The tip for the whole visit, also of a group Appointment. Null when none is recorded. */
    val tipCents: Long? = null,
    val services: List<AppointmentService> = emptyList(),
    val photos: List<AppointmentPhoto> = emptyList(),
)

@Serializable
data class ShareImage(val svg: String)

/** What the UploadThing route's `onUploadComplete` returns for an Appointment Photo. */
@Serializable
data class UploadedPhoto(
    val appointmentId: String? = null,
    val clientId: String? = null,
    val category: PhotoCategory? = null,
    val fileKey: String? = null,
    val url: String? = null,
    val thumbnailUrl: String? = null,
)
