package com.airvox.server

import kotlinx.serialization.Serializable

@Serializable
data class Rider(
    val id: String,
    val phoneNumber: String? = null,
    val email: String? = null,
    val displayName: String? = null,
    val signInProvider: String,
    val providers: List<String> = emptyList(),
    val profileComplete: Boolean = false,
    val createdAt: String,
    val lastSeenAt: String,
)

@Serializable
data class RiderResponse(
    val rider: Rider,
    /** 20 the first time this account signs in, otherwise 0. */
    val welcomeCredits: Int = 0,
)

/** Result of saving a rider. [created] is true only on that account's first sign-in. */
data class UpsertedRider(val rider: Rider, val created: Boolean)

/** A rider removed from the portal. [firebaseUid] is the phone sign-in, when there is one. */
data class RemovedRider(val id: String, val firebaseUid: String?)

@Serializable
data class RemovedRiderResponse(val emailFreed: Boolean)

@Serializable
data class PlanOffer(
    val id: Long,
    val name: String,
    val credits: Int,
    val rupees: Int,
)

@Serializable
data class PlansResponse(val plans: List<PlanOffer>)

@Serializable
data class HeartbeatBody(
    val credits: Int = -1,
    val room: String = "",
    val mode: String = "",
)

@Serializable
data class ChannelBody(
    val room: String = "",
    val mode: String = "",
    val action: String = "",
    /** When the phone joined or left. Offline events send the real time, not the time the network returned. */
    val at: String? = null,
)

@Serializable
data class CreditBody(
    val kind: String = "",
    val credits: Int = 0,
)

@Serializable
data class DisplayNameBody(
    val displayName: String = "",
    val phoneNumber: String? = null,
    val completeProfile: Boolean = false,
)

@Serializable
data class ApiError(val error: String, val message: String)

@Serializable
data class HealthResponse(val status: String)

@Serializable
internal data class StoredRider(
    val id: String,
    val phoneNumber: String? = null,
    val email: String? = null,
    val displayName: String? = null,
    val displayNameCustom: Boolean = false,
    val profileComplete: Boolean = false,
    val signInProvider: String,
    val providers: List<String> = emptyList(),
    val createdAt: String,
    val lastSeenAt: String,
) {
    fun toPublic() = Rider(
        id = id,
        phoneNumber = phoneNumber,
        email = email,
        displayName = displayName,
        signInProvider = signInProvider,
        providers = providers,
        profileComplete = profileComplete,
        createdAt = createdAt,
        lastSeenAt = lastSeenAt,
    )
}

@Serializable
internal data class RiderFile(
    val riders: List<StoredRider> = emptyList(),
    val removed: List<String> = emptyList(),
)

/** The portal deleted this rider. Sign-in must not create the server record again. */
class RiderRemoved : Exception("This account was deleted.")
