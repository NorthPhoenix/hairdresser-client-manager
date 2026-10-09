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
) {
    val onboardingComplete: Boolean get() = onboardingCompletedAt != null
}

@Serializable
data class ProfileShare(
    val id: String,
    val token: String,
    val language: Language,
    val createdAt: String,
)

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
)

@Serializable
data class ServiceMenuItem(
    val id: String,
    val name: String,
    val defaultPriceCents: Int,
)

@Serializable
data class AppointmentParticipant(
    val clientId: String,
    val name: String,
    val address: String = "",
    val phone: String = "",
    val language: Language = Language.RU,
    val isPrimary: Boolean = false,
    val subtotalCents: Int = 0,
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
    val priceCents: Int,
    val note: String = "",
    val colorFormulas: List<ColorFormula> = emptyList(),
)

@Serializable
data class AppointmentPhoto(
    val id: String,
    val clientId: String,
    val category: PhotoCategory,
    val status: PhotoStatus = PhotoStatus.STORED,
    val url: String = "",
    val thumbnailUrl: String = "",
    val uploadError: String = "",
)

@Serializable
data class Appointment(
    val id: String,
    val primaryClientId: String,
    val primaryClientName: String,
    val participants: List<AppointmentParticipant> = emptyList(),
    val services: List<AppointmentService> = emptyList(),
    val photos: List<AppointmentPhoto> = emptyList(),
    val serviceTotalCents: Int = 0,
    val finalTotalCents: Int = 0,
    val finalTotalCentsOverride: Int? = null,
    val startsAt: String,
    val endsAt: String? = null,
    val status: AppointmentStatus,
    val locationType: LocationType,
    val locationAddress: String = "",
    val note: String = "",
    val mapUrl: String? = null,
)

@Serializable
data class CreateAppointmentResult(
    val appointment: Appointment,
    val conflicts: List<Appointment> = emptyList(),
)

@Serializable
data class ClientHistoryEntry(
    val id: String,
    val primaryClientName: String,
    val startsAt: String,
    val status: AppointmentStatus,
    val services: List<HistoryService> = emptyList(),
) {
    @Serializable
    data class HistoryService(val id: String, val clientId: String, val name: String, val priceCents: Int)
}

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
