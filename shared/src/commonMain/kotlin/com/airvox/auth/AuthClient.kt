package com.airvox.auth

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.airvox.credits.CreditPlan

data class RiderAccount(
    val id: String,
    val phoneNumber: String?,
    val email: String?,
    val displayName: String?,
    val signInProvider: String,
    val providers: List<String>,
    val profileComplete: Boolean = false,
)

/** The wallet the server holds. [spentCounted] is the phone's spent total it had already taken off. */
data class ServerWallet(val balance: Int, val spentCounted: Int)

enum class AuthPhase {
    Restoring,
    Ready,
}

data class AuthState(
    val phase: AuthPhase = AuthPhase.Ready,
    val account: RiderAccount? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val configured: Boolean = false,
    val pendingSession: Boolean = false,
    /** True while the account still needs a name and mobile number. */
    val needsProfile: Boolean = false,
    /** From the last check-in the server answered. Null until then. */
    val wallet: ServerWallet? = null,
    /** This phone's sign-in was replaced. The account stays until the rider taps OK. */
    val signedInElsewhere: Boolean = false,
)

/**
 * Firebase signs the rider in. The Airvox API then checks that Firebase ID token.
 * Google is the primary method. Email and password is the other.
 * The password stays with Firebase and never reaches the API.
 */
interface AuthClient {
    /** When true, the app stays on the sign-in screen until [AuthState.account] is set. */
    val enforced: Boolean

    val state: State<AuthState>

    fun signInWithGoogle()

    fun signInWithEmail(email: String, password: String)

    fun createEmailAccount(email: String, password: String, name: String)

    fun updateDisplayName(name: String)

    fun saveProfile(name: String, phoneNumber: String)

    fun refreshSession()

    /**
     * Tells the Airvox API the signed-in rider still has the app open, and every credit this install has used.
     * The server's balance comes back in [AuthState.wallet].
     * [room] and [mode] are set while they are on a channel, so the portal can keep that channel live.
     */
    fun heartbeat(install: String, spent: Int, room: String = "", mode: String = "") = Unit

    /** A rider created or left a channel. The phone keeps it until the server accepts it. */
    fun reportChannel(room: String, mode: String, action: String) = Unit

    /** Adds a credit pack to the wallet on the server. Calls back on the main thread with whether it was saved. */
    fun buyCredits(credits: Int, onResult: (Boolean) -> Unit) = onResult(false)

    /** Active plans from the portal. Calls back on the main thread. */
    fun loadPlans(onResult: (List<CreditPlan>) -> Unit) = onResult(emptyList())

    fun signOut()

    /** Leaves this phone only. Does not end the session on the phone that just signed in. */
    fun confirmOtherDevice() = Unit

    fun clearError()
}

class UnavailableAuthClient : AuthClient {
    override val enforced: Boolean = false
    override val state: State<AuthState> = mutableStateOf(AuthState())

    override fun signInWithGoogle() = Unit

    override fun signInWithEmail(email: String, password: String) = Unit

    override fun createEmailAccount(email: String, password: String, name: String) = Unit

    override fun updateDisplayName(name: String) = Unit

    override fun saveProfile(name: String, phoneNumber: String) = Unit

    override fun refreshSession() = Unit

    override fun signOut() = Unit

    override fun clearError() = Unit
}

/** Room code and Bluetooth, Hotspot, or Internet. Null when the phone didn't send a real channel. */
fun normalizeChannel(room: String, mode: String): Pair<String, String>? {
    val clean = room.trim().lowercase().filter { it.isLetterOrDigit() }.take(16)
    if (clean.length < 3) return null
    val path = when (mode.trim().lowercase()) {
        "bluetooth" -> "Bluetooth"
        "hotspot" -> "Hotspot"
        "internet" -> "Internet"
        else -> return null
    }
    return clean to path
}

fun providerLabel(provider: String): String = when (provider) {
    "phone" -> "Mobile number"
    "google.com" -> "Google"
    "password" -> "Email"
    else -> provider
}
