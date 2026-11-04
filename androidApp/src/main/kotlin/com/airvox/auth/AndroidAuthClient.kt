package com.airvox.auth

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.airvox.credits.CreditPlan
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class AndroidAuthClient(
    private val activity: ComponentActivity,
    private val apiUrl: String,
    private val webClientId: String,
) : AuthClient {
    override val enforced: Boolean = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val http = OkHttpClient()
    private val riders = SupabaseRiderStore(
        baseUrl = com.airvox.BuildConfig.SUPABASE_URL,
        apiKey = com.airvox.BuildConfig.SUPABASE_ANON_KEY,
        http = http,
    )
    private val firebase: FirebaseAuth? = if (FirebaseGate.ready) FirebaseAuth.getInstance() else null
    private val baseUrl = apiUrl.trim().trimEnd('/')
    private val jsonType = "application/json".toMediaType()
    private val _state = mutableStateOf(AuthState(phase = AuthPhase.Restoring))
    override val state: State<AuthState> = _state
    private val googleSignIn by lazy {
        GoogleSignIn.getClient(
            activity,
            GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestIdToken(webClientId)
                .build(),
        )
    }
    private val googleLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result -> onGoogleResult(result.data) }

    init {
        val current = firebase?.currentUser
        when {
            firebase == null -> update {
                AuthState(
                    phase = AuthPhase.Ready,
                    configured = false,
                    error = FirebaseGate.reason,
                )
            }
            current == null -> update { AuthState(phase = AuthPhase.Ready, configured = true) }
            else -> scope.launch { runAuth { openSession(current) } }
        }
    }

    fun dispose() {
        scope.cancel()
    }

    override fun signInWithGoogle() {
        if (_state.value.busy) return
        if (firebase == null) {
            update { it.copy(error = FirebaseGate.reason ?: "Firebase is not connected.") }
            return
        }
        if (webClientId.isBlank()) {
            update {
                it.copy(error = "Add firebase.web.client.id to local.properties. It is the Web client ID from Firebase Authentication → Google.")
            }
            return
        }
        update { it.copy(busy = true, error = null) }
        googleSignIn.signOut().addOnCompleteListener {
            if (activity.isFinishing || activity.isDestroyed) return@addOnCompleteListener
            googleLauncher.launch(googleSignIn.signInIntent)
        }
    }

    private fun onGoogleResult(data: Intent?) {
        if (data == null) {
            update { it.copy(busy = false, error = "Google sign-in was cancelled.") }
            return
        }
        val account = try {
            GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
        } catch (error: ApiException) {
            update { it.copy(busy = false, error = googleStatusMessage(error.statusCode)) }
            return
        }
        val idToken = account.idToken
        val auth = firebase
        if (idToken.isNullOrBlank() || auth == null) {
            update { it.copy(busy = false, error = googleBlocked()) }
            return
        }
        scope.launch {
            runAuth {
                val user = auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null))
                    .await()
                    .user
                    ?: throw SessionFailed("Google did not return an account.", signedOut = false)
                openSession(user)
            }
        }
    }

    override fun signInWithEmail(email: String, password: String) {
        emailAuth(email, password, create = false)
    }

    override fun createEmailAccount(email: String, password: String) {
        emailAuth(email, password, create = true)
    }

    override fun updateDisplayName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > 40 || trimmed.any { it == '\n' || it == '\r' }) {
            update { it.copy(error = "Use a name of 1 to 40 characters.") }
            return
        }
        val auth = firebase
        val user = auth?.currentUser
        if (auth == null || user == null) {
            update { it.copy(error = "Sign in again before changing your name.") }
            return
        }
        scope.launch {
            update { it.copy(busy = true, error = null) }
            runAuth {
                val token = user.getIdToken(false).await().token
                    ?: throw SessionFailed("Sign in again.", signedOut = true)
                val saved = withContext(Dispatchers.IO) { patchProfile(token, trimmed, phone = null, complete = false) }
                val warning = withContext(Dispatchers.IO) { rememberRider(user, saved, profileSaved = true) }
                update { it.copy(account = saved, busy = false, error = warning) }
            }
        }
    }

    override fun saveProfile(name: String, phoneNumber: String) {
        val trimmed = name.trim()
        val phone = e164(phoneNumber)
        if (trimmed.isEmpty() || trimmed.length > 40 || trimmed.any { it == '\n' || it == '\r' }) {
            update { it.copy(error = "Use a name of 1 to 40 characters.") }
            return
        }
        if (phone == null) {
            update { it.copy(error = "Enter a mobile number with the country code, such as +9198…") }
            return
        }
        val user = firebase?.currentUser
        if (user == null) {
            update { it.copy(error = "Sign in again before saving your profile.") }
            return
        }
        scope.launch {
            update { it.copy(busy = true, error = null) }
            runAuth {
                val token = user.getIdToken(false).await().token
                    ?: throw SessionFailed("Sign in again.", signedOut = true)
                val saved = withContext(Dispatchers.IO) { patchProfile(token, trimmed, phone, complete = true) }
                val warning = withContext(Dispatchers.IO) { rememberRider(user, saved, profileSaved = true) }
                update { it.copy(account = saved, busy = false, error = warning) }
            }
        }
    }

    override fun refreshSession() {
        val user = firebase?.currentUser ?: return
        if (_state.value.busy) return
        scope.launch {
            update { it.copy(busy = true, error = null) }
            runAuth { openSession(user) }
        }
    }

    override fun loadPlans(onResult: (List<CreditPlan>) -> Unit) {
        val user = firebase?.currentUser
        if (user == null || _state.value.account == null) {
            onResult(emptyList())
            return
        }
        scope.launch {
            val plans = runCatching {
                val token = user.getIdToken(false).await().token ?: return@runCatching emptyList()
                withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url("$baseUrl/v1/plans")
                        .header("Authorization", "Bearer $token")
                        .get()
                        .build()
                    http.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@withContext emptyList()
                        val array = JSONObject(response.body?.string().orEmpty()).optJSONArray("plans") ?: JSONArray()
                        buildList {
                            for (index in 0 until array.length()) {
                                val plan = array.getJSONObject(index)
                                add(
                                    CreditPlan(
                                        plan.getLong("id"),
                                        plan.getString("name"),
                                        plan.getInt("credits"),
                                        plan.optInt("rupees", plan.getInt("credits")),
                                    ),
                                )
                            }
                        }
                    }
                }
            }.getOrDefault(emptyList())
            onResult(plans)
        }
    }

    override fun recordCredits(kind: String, credits: Int) {
        val user = firebase?.currentUser ?: return
        if (_state.value.account == null) return
        scope.launch {
            runCatching {
                val token = user.getIdToken(false).await().token ?: return@launch
                withContext(Dispatchers.IO) {
                    val body = JSONObject().put("kind", kind).put("credits", credits)
                    val request = Request.Builder()
                        .url("$baseUrl/v1/credits")
                        .header("Authorization", "Bearer $token")
                        .post(body.toString().toRequestBody(jsonType))
                        .build()
                    http.newCall(request).execute().close()
                }
            }
        }
    }

    override fun heartbeat(credits: Int) {
        val user = firebase?.currentUser ?: return
        if (_state.value.account == null) return
        scope.launch {
            runCatching {
                val token = user.getIdToken(false).await().token ?: return@launch
                withContext(Dispatchers.IO) {
                    val body = JSONObject().put("credits", credits)
                    val request = Request.Builder()
                        .url("$baseUrl/v1/heartbeat")
                        .header("Authorization", "Bearer $token")
                        .header(PLATFORM_HEADER, PLATFORM)
                        .post(body.toString().toRequestBody(jsonType))
                        .build()
                    http.newCall(request).execute().close()
                }
            }
        }
    }

    override fun signOut() {
        scope.launch {
            val token = runCatching { firebase?.currentUser?.getIdToken(false)?.await()?.token }.getOrNull()
            if (!token.isNullOrBlank()) {
                runCatching { withContext(Dispatchers.IO) { deleteSession(token) } }
            }
            firebase?.signOut()
            update {
                AuthState(
                    phase = AuthPhase.Ready,
                    configured = firebase != null,
                )
            }
        }
    }

    override fun clearError() {
        update { it.copy(error = null) }
    }

    private fun emailAuth(email: String, password: String, create: Boolean) {
        if (_state.value.busy) return
        val auth = firebase
        if (auth == null) {
            update { it.copy(error = FirebaseGate.reason ?: "Firebase is not connected.") }
            return
        }
        val address = email.trim()
        if (!validEmail(address)) {
            update { it.copy(error = "Enter a valid email address.") }
            return
        }
        if (password.length < 6) {
            update { it.copy(error = "Use a password of at least 6 characters.") }
            return
        }
        scope.launch {
            update { it.copy(busy = true, error = null) }
            runAuth {
                val result = if (create) {
                    auth.createUserWithEmailAndPassword(address, password).await()
                } else {
                    auth.signInWithEmailAndPassword(address, password).await()
                }
                if (create) {
                    runCatching { result.user?.sendEmailVerification()?.await() }
                }
                val user = result.user ?: throw SessionFailed("Firebase did not return a user.", signedOut = false)
                openSession(user)
            }
        }
    }

    private suspend fun openSession(user: FirebaseUser) {
        val token = user.getIdToken(false).await().token
            ?: throw SessionFailed("Firebase did not return a sign-in token.", signedOut = false)
        val rider = withContext(Dispatchers.IO) { postSession(token) }
        val warning = withContext(Dispatchers.IO) { rememberRider(user, rider, profileSaved = false) }
        update {
            it.copy(
                phase = AuthPhase.Ready,
                account = rider,
                busy = false,
                error = warning,
            )
        }
    }

    private fun rememberRider(user: FirebaseUser, rider: RiderAccount, profileSaved: Boolean): String? {
        return try {
            riders.upsert(user, rider, profileSaved)
            null
        } catch (error: SupabaseRejected) {
            error.message ?: "Supabase couldn't save this account."
        } catch (error: IOException) {
            "Can't reach Supabase to save this account."
        }
    }

    private suspend fun runAuth(block: suspend () -> Unit) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: SessionFailed) {
            if (error.signedOut) firebase?.signOut()
            update {
                it.copy(
                    phase = AuthPhase.Ready,
                    account = if (error.signedOut) null else it.account,
                    busy = false,
                    error = error.message,
                )
            }
        } catch (error: Throwable) {
            update { it.copy(phase = AuthPhase.Ready, busy = false, error = friendly(error)) }
        }
    }

    private fun postSession(token: String): RiderAccount {
        val request = Request.Builder()
            .url("$baseUrl/v1/session")
            .header("Authorization", "Bearer $token")
            .header(PLATFORM_HEADER, PLATFORM)
            .post("{}".toRequestBody(jsonType))
            .build()
        return exchange(request)
    }

    private fun patchProfile(token: String, name: String, phone: String?, complete: Boolean): RiderAccount {
        val body = JSONObject()
            .put("displayName", name)
            .put("completeProfile", complete)
        if (phone != null) body.put("phoneNumber", phone)
        val request = Request.Builder()
            .url("$baseUrl/v1/me")
            .header("Authorization", "Bearer $token")
            .header(PLATFORM_HEADER, PLATFORM)
            .patch(body.toString().toRequestBody(jsonType))
            .build()
        return exchange(request)
    }

    private fun deleteSession(token: String) {
        val request = Request.Builder()
            .url("$baseUrl/v1/session")
            .header("Authorization", "Bearer $token")
            .delete()
            .build()
        http.newCall(request).execute().close()
    }

    private fun exchange(request: Request): RiderAccount {
        try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val message = parseError(body, "Airvox couldn't open your session (${response.code}).")
                    throw SessionFailed(message, signedOut = response.code == 401)
                }
                return parseRider(body)
            }
        } catch (error: SessionFailed) {
            throw error
        } catch (error: IOException) {
            throw SessionFailed(
                "Can't reach the Airvox server at $baseUrl. From the Airvana folder run gradlew :server:run. On a phone, set airvox.api.url to this computer's address.",
                signedOut = false,
            )
        }
    }

    private fun update(block: (AuthState) -> AuthState) {
        val next = block(_state.value)
        _state.value = next.copy(
            pendingSession = firebase?.currentUser != null && next.account == null,
            configured = firebase != null && next.phase != AuthPhase.Restoring || next.configured,
        )
    }

    private class SessionFailed(message: String, val signedOut: Boolean) : Exception(message)
}

// Lets the Airvox API record which app the rider uses, for the admin portal.
private const val PLATFORM_HEADER = "X-Airvox-Platform"
private const val PLATFORM = "Android"

private fun validEmail(value: String): Boolean {
    val at = value.indexOf('@')
    if (at <= 0 || value.contains(' ')) return false
    val dot = value.indexOf('.', startIndex = at + 1)
    return dot > at + 1 && dot < value.lastIndex
}

private fun parseRider(body: String): RiderAccount {
    val root = JSONObject(body)
    val rider = root.getJSONObject("rider")
    return RiderAccount(
        id = rider.getString("id"),
        phoneNumber = rider.nullableString("phoneNumber"),
        email = rider.nullableString("email"),
        displayName = rider.nullableString("displayName"),
        signInProvider = rider.getString("signInProvider"),
        providers = rider.optJSONArray("providers").toList(),
        profileComplete = rider.optBoolean("profileComplete", false),
        welcomeCredits = root.optInt("welcomeCredits", 0),
    )
}

private fun parseError(body: String, fallback: String): String {
    return runCatching { JSONObject(body).getString("message") }.getOrDefault(fallback)
}

private fun JSONObject.nullableString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return getString(key).ifBlank { null }
}

private fun JSONArray?.toList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) add(getString(index))
    }
}

private fun e164(value: String): String? {
    val compact = value.trim().replace(" ", "").replace("-", "")
    if (!compact.matches(Regex("""\+\d{8,15}"""))) return null
    return compact
}

private fun friendly(error: Throwable): String {
    when (error) {
        is FirebaseAuthException -> {
            val code = error.errorCode.uppercase()
            return when {
                "TOO_MANY_REQUESTS" in code || "QUOTA" in code ->
                    "Too many attempts. Wait a few minutes and try again."
                "EMAIL_ALREADY_IN_USE" in code -> "An account already uses that email. Sign in instead."
                "INVALID_EMAIL" in code -> "Enter a valid email address."
                "WRONG_PASSWORD" in code || "INVALID_CREDENTIAL" in code -> "Email or password is incorrect."
                "USER_NOT_FOUND" in code -> "No account uses that email. Create one first."
                "WEAK_PASSWORD" in code -> "Use a password of at least 6 characters."
                "NETWORK" in code -> "No internet. Google sign-in needs a connection."
                "WEB_CONTEXT_CANCELED" in code -> "Google sign-in was cancelled."
                "APP_NOT_AUTHORIZED" in code || "MISSING_CLIENT_IDENTIFIER" in code || "DEVELOPER_ERROR" in code ->
                    googleBlocked()
                else -> error.message ?: "Sign-in failed."
            }
        }
    }
    val text = error.message.orEmpty()
    if (
        text.contains("not authorized", ignoreCase = true) ||
        text.contains("certificate hash", ignoreCase = true) ||
        text.contains("Developer console", ignoreCase = true)
    ) {
        return googleBlocked()
    }
    return text.ifBlank { "Sign-in failed." }
}

private fun googleStatusMessage(code: Int): String {
    return when (code) {
        GoogleSignInStatusCodes.SIGN_IN_CANCELLED -> "Google sign-in was cancelled."
        GoogleSignInStatusCodes.SIGN_IN_CURRENTLY_IN_PROGRESS -> "Google sign-in is already open."
        CommonStatusCodes.NETWORK_ERROR -> "No internet. Google sign-in needs a connection."
        CommonStatusCodes.DEVELOPER_ERROR -> googleBlocked()
        else -> "Google sign-in failed. Try again."
    }
}

private fun googleBlocked(): String {
    return "Google sign-in is not authorized for this install. Check the Android app in Firebase."
}
