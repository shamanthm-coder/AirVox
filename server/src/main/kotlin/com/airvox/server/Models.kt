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
    /**
     * Credits to take back off the phone. Set when a welcome grant was given again
     * to an account that already had one. Stays until the phone confirms it.
     */
    val balanceOffset: Int = 0,
    /** This sign-in. An older phone still holding the previous id is signed out. */
    val sessionId: String = "",
)

/** Result of saving a rider. [created] is true only on that account's first sign-in. */
data class UpsertedRider(val rider: Rider, val created: Boolean, val sessionId: String = "")

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
    /** The phone has already removed [RiderResponse.balanceOffset] from its wallet. */
    val clearOffset: Boolean = false,
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
    /** True after the one welcome grant was decided, so a rebuilt local file cannot grant it again. */
    val welcomeGranted: Boolean = false,
    /** Subtracted from the balance the phone reports, until the phone confirms it removed the same amount. */
    val balanceOffset: Int = 0,
    val signInProvider: String,
    val providers: List<String> = emptyList(),
    /** The phone that last called POST /v1/session. Empty until the first sign-in after this was added. */
    val sessionId: String = "",
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
