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
    /** Free credits given once, on this account's first sign-in. Later sessions send 0. */
    val welcomeCredits: Int = 0,
)

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

    fun createEmailAccount(email: String, password: String)

    fun updateDisplayName(name: String)

    fun saveProfile(name: String, phoneNumber: String)

    fun refreshSession()

    /** Tells the Airvox API the signed-in rider still has the app open, and how many credits are on the phone. */
    fun heartbeat(credits: Int) = Unit

    /** Records credits spent on a channel ("consume") or a pack added ("purchase"). Quiet on failure. */
    fun recordCredits(kind: String, credits: Int) = Unit

    /** Active plans from the portal. Calls back on the main thread. */
    fun loadPlans(onResult: (List<CreditPlan>) -> Unit) = onResult(emptyList())

    fun signOut()

    fun clearError()
}

class UnavailableAuthClient : AuthClient {
    override val enforced: Boolean = false
    override val state: State<AuthState> = mutableStateOf(AuthState())

    override fun signInWithGoogle() = Unit

    override fun signInWithEmail(email: String, password: String) = Unit

    override fun createEmailAccount(email: String, password: String) = Unit

    override fun updateDisplayName(name: String) = Unit

    override fun saveProfile(name: String, phoneNumber: String) = Unit

    override fun refreshSession() = Unit

    override fun signOut() = Unit

    override fun clearError() = Unit
}

fun providerLabel(provider: String): String = when (provider) {
    "phone" -> "Mobile number"
    "google.com" -> "Google"
    "password" -> "Email"
    else -> provider
}
