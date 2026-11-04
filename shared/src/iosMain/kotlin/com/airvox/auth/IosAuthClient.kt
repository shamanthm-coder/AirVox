package com.airvox.auth

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.airvox.credits.CreditPlan
import kotlin.coroutines.resume
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import platform.Foundation.NSDate
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.dataUsingEncoding
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.Foundation.timeIntervalSince1970

/**
 * Firebase email sign-in for iPhone, over Firebase's REST API, so it needs no
 * Firebase iOS SDK. Like Android, the Airvox API then checks the Firebase ID token.
 * The password goes only to Firebase, never to the Airvox API.
 */
class IosAuthClient(
    private val firebaseApiKey: String,
    apiUrl: String,
) : AuthClient {
    override val enforced: Boolean = true

    private val scope = MainScope()
    private val baseUrl = apiUrl.trim().trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }
    private val store = NSUserDefaults.standardUserDefaults
    private var tokens: Tokens? = loadTokens()
    private val _state = mutableStateOf(AuthState(phase = AuthPhase.Restoring))
    override val state: State<AuthState> = _state

    init {
        when {
            firebaseApiKey.isBlank() -> update {
                AuthState(
                    phase = AuthPhase.Ready,
                    configured = false,
                    error = "Add firebase.api.key to local.properties, then rebuild.",
                )
            }
            tokens == null -> update { AuthState(phase = AuthPhase.Ready, configured = true) }
            else -> scope.launch { runAuth { openSession() } }
        }
    }

    override fun signInWithGoogle() {
        // Google on iPhone needs the Google Sign-In SDK and an iOS app in Firebase.
        update { it.copy(error = "Google sign-in on iPhone isn't set up yet. Use email and password.") }
    }

    override fun signInWithEmail(email: String, password: String) {
        emailAuth(email, password, create = false)
    }

    override fun createEmailAccount(email: String, password: String) {
        emailAuth(email, password, create = true)
    }

    override fun updateDisplayName(name: String) {
        val trimmed = name.trim()
        if (!validName(trimmed)) {
            update { it.copy(error = "Use a name of 1 to 40 characters.") }
            return
        }
        if (tokens == null) {
            update { it.copy(error = "Sign in again before changing your name.") }
            return
        }
        scope.launch {
            update { it.copy(busy = true, error = null) }
            runAuth {
                val rider = patchProfile(idToken(), trimmed, phone = null, complete = false)
                update { it.copy(account = rider, busy = false, error = null) }
            }
        }
    }

    override fun saveProfile(name: String, phoneNumber: String) {
        val trimmed = name.trim()
        val phone = e164(phoneNumber)
        if (!validName(trimmed)) {
            update { it.copy(error = "Use a name of 1 to 40 characters.") }
            return
        }
        if (phone == null) {
            update { it.copy(error = "Enter a mobile number with the country code, such as +9198…") }
            return
        }
        if (tokens == null) {
            update { it.copy(error = "Sign in again before saving your profile.") }
            return
        }
        scope.launch {
            update { it.copy(busy = true, error = null) }
            runAuth {
                val rider = patchProfile(idToken(), trimmed, phone, complete = true)
                update { it.copy(account = rider, busy = false, error = null) }
            }
        }
    }

    override fun refreshSession() {
        if (tokens == null || _state.value.busy) return
        scope.launch {
            update { it.copy(busy = true, error = null) }
            runAuth { openSession() }
        }
    }

    override fun loadPlans(onResult: (List<CreditPlan>) -> Unit) {
        if (tokens == null || _state.value.account == null) {
            onResult(emptyList())
            return
        }
        scope.launch {
            val plans = runCatching {
                val reply = send("GET", "$baseUrl/v1/plans", idToken(), body = null)
                val parsed = parse(reply.body)
                if (reply.status !in 200..299 || parsed == null) return@runCatching emptyList()
                parsed["plans"]!!.jsonArray.map { item ->
                    val plan = item.jsonObject
                    val credits = plan.string("credits")?.toIntOrNull() ?: 0
                    CreditPlan(
                        id = plan.string("id")?.toLongOrNull() ?: 0L,
                        name = plan.string("name").orEmpty(),
                        credits = credits,
                        rupees = plan.string("rupees")?.toIntOrNull() ?: credits,
                    )
                }.filter { it.credits > 0 && it.rupees > 0 && it.name.isNotBlank() }
            }.getOrDefault(emptyList())
            onResult(plans)
        }
    }

    override fun recordCredits(kind: String, credits: Int) {
        if (tokens == null || _state.value.account == null) return
        scope.launch {
            runCatching {
                val body = buildJsonObject {
                    put("kind", kind)
                    put("credits", credits)
                }
                send("POST", "$baseUrl/v1/credits", idToken(), body.toString())
            }
        }
    }

    override fun heartbeat(credits: Int) {
        if (tokens == null || _state.value.account == null) return
        scope.launch {
            runCatching {
                val body = buildJsonObject { put("credits", credits) }
                send("POST", "$baseUrl/v1/heartbeat", idToken(), body.toString())
            }
        }
    }

    override fun signOut() {
        scope.launch {
            val token = runCatching { idToken() }.getOrNull()
            if (token != null) runCatching { send("DELETE", "$baseUrl/v1/session", token, body = null) }
            saveTokens(null)
            update { AuthState(phase = AuthPhase.Ready, configured = firebaseApiKey.isNotBlank()) }
        }
    }

    override fun clearError() {
        update { it.copy(error = null) }
    }

    private fun emailAuth(email: String, password: String, create: Boolean) {
        if (_state.value.busy) return
        if (firebaseApiKey.isBlank()) {
            update { it.copy(error = "Add firebase.api.key to local.properties, then rebuild.") }
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
                val method = if (create) "signUp" else "signInWithPassword"
                val body = buildJsonObject {
                    put("email", address)
                    put("password", password)
                    put("returnSecureToken", true)
                }
                val reply = firebase("https://identitytoolkit.googleapis.com/v1/accounts:$method", body.toString())
                val idToken = reply.string("idToken") ?: throw SessionFailed("Firebase did not return a user.", signedOut = false)
                saveTokens(
                    Tokens(
                        idToken = idToken,
                        refreshToken = reply.string("refreshToken").orEmpty(),
                        expiresAt = now() + (reply.string("expiresIn")?.toLongOrNull() ?: 3600L),
                    ),
                )
                if (create) {
                    val verify = buildJsonObject {
                        put("requestType", "VERIFY_EMAIL")
                        put("idToken", idToken)
                    }
                    runCatching { firebase("https://identitytoolkit.googleapis.com/v1/accounts:sendOobCode", verify.toString()) }
                }
                openSession()
            }
        }
    }

    private suspend fun openSession() {
        val rider = exchange(send("POST", "$baseUrl/v1/session", idToken(), body = "{}"))
        update { it.copy(phase = AuthPhase.Ready, account = rider, busy = false, error = null) }
    }

    /** A Firebase ID token that is good for at least another minute. */
    private suspend fun idToken(): String {
        val current = tokens ?: throw SessionFailed("Sign in again.", signedOut = true)
        if (current.expiresAt - now() > 60) return current.idToken
        val reply = send(
            "POST",
            "https://securetoken.googleapis.com/v1/token?key=$firebaseApiKey",
            token = null,
            body = "grant_type=refresh_token&refresh_token=${current.refreshToken}",
            contentType = "application/x-www-form-urlencoded",
        )
        val parsed = parse(reply.body)
        if (reply.status !in 200..299) {
            throw SessionFailed(firebaseMessage(parsed), signedOut = true)
        }
        val fresh = Tokens(
            idToken = parsed?.string("id_token") ?: throw SessionFailed("Sign in again.", signedOut = true),
            refreshToken = parsed.string("refresh_token") ?: current.refreshToken,
            expiresAt = now() + (parsed.string("expires_in")?.toLongOrNull() ?: 3600L),
        )
        saveTokens(fresh)
        return fresh.idToken
    }

    private suspend fun firebase(url: String, body: String): JsonObject {
        val reply = send("POST", "$url?key=$firebaseApiKey", token = null, body = body)
        val parsed = parse(reply.body)
        if (reply.status !in 200..299 || parsed == null) {
            throw SessionFailed(firebaseMessage(parsed), signedOut = false)
        }
        return parsed
    }

    private suspend fun patchProfile(token: String, name: String, phone: String?, complete: Boolean): RiderAccount {
        val body = buildJsonObject {
            put("displayName", name)
            put("completeProfile", complete)
            if (phone != null) put("phoneNumber", phone)
        }
        return exchange(send("PATCH", "$baseUrl/v1/me", token, body.toString()))
    }

    private fun exchange(reply: Reply): RiderAccount {
        if (reply.status == 0) {
            throw SessionFailed(
                "Can't reach the Airvox server at $baseUrl. From the Airvana folder run gradlew :server:run.",
                signedOut = false,
            )
        }
        val parsed = parse(reply.body)
        if (reply.status !in 200..299 || parsed == null) {
            val message = parsed?.string("message") ?: "Airvox couldn't open your session (${reply.status})."
            throw SessionFailed(message, signedOut = reply.status == 401)
        }
        val rider = parseRider(parsed["rider"]!!.jsonObject)
        return rider.copy(welcomeCredits = parsed.string("welcomeCredits")?.toIntOrNull() ?: 0)
    }

    private suspend fun runAuth(block: suspend () -> Unit) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: SessionFailed) {
            if (error.signedOut) saveTokens(null)
            update {
                it.copy(
                    phase = AuthPhase.Ready,
                    account = if (error.signedOut) null else it.account,
                    busy = false,
                    error = error.message,
                )
            }
        } catch (error: Throwable) {
            update { it.copy(phase = AuthPhase.Ready, busy = false, error = error.message ?: "Sign-in failed.") }
        }
    }

    private fun update(block: (AuthState) -> AuthState) {
        val next = block(_state.value)
        _state.value = next.copy(
            pendingSession = tokens != null && next.account == null,
            configured = firebaseApiKey.isNotBlank() && next.phase != AuthPhase.Restoring || next.configured,
        )
    }

    // The session is kept in this app's settings, like Firebase does on Android.
    private fun loadTokens(): Tokens? {
        val refresh = store.stringForKey(KEY_REFRESH) ?: return null
        return Tokens(
            idToken = store.stringForKey(KEY_ID).orEmpty(),
            refreshToken = refresh,
            expiresAt = 0, // Refresh on start.
        )
    }

    private fun saveTokens(value: Tokens?) {
        tokens = value
        if (value == null) {
            store.removeObjectForKey(KEY_ID)
            store.removeObjectForKey(KEY_REFRESH)
        } else {
            store.setObject(value.idToken, KEY_ID)
            store.setObject(value.refreshToken, KEY_REFRESH)
        }
    }

    private fun parse(body: String): JsonObject? = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()

    private class SessionFailed(message: String, val signedOut: Boolean) : Exception(message)

    private data class Tokens(val idToken: String, val refreshToken: String, val expiresAt: Long)

    private class Reply(val status: Int, val body: String)

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    private suspend fun send(
        method: String,
        url: String,
        token: String?,
        body: String?,
        contentType: String = "application/json",
    ): Reply = suspendCancellableCoroutine { done ->
        val request = NSMutableURLRequest(uRL = NSURL(string = url))
        request.setHTTPMethod(method)
        request.setValue(contentType, forHTTPHeaderField = "Content-Type")
        if (token != null) request.setValue("Bearer $token", forHTTPHeaderField = "Authorization")
        // Lets the Airvox API record which app the rider uses, for the admin portal.
        if (url.startsWith(baseUrl)) request.setValue("iOS", forHTTPHeaderField = "X-Airvox-Platform")
        if (body != null) request.setHTTPBody(NSString.create(string = body).dataUsingEncoding(NSUTF8StringEncoding))
        val task = NSURLSession.sharedSession.dataTaskWithRequest(request) { data, response, error ->
            val reply = if (error != null) {
                Reply(0, "")
            } else {
                val text = data?.let { NSString.create(data = it, encoding = NSUTF8StringEncoding)?.toString() }.orEmpty()
                Reply((response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0, text)
            }
            scope.launch { if (done.isActive) done.resume(reply) }
        }
        done.invokeOnCancellation { task.cancel() }
        task.resume()
    }

    private companion object {
        const val KEY_ID = "airvox.auth.idToken"
        const val KEY_REFRESH = "airvox.auth.refreshToken"

        fun now(): Long = NSDate().timeIntervalSince1970.toLong()
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.ifBlank { null }

private fun parseRider(rider: JsonObject): RiderAccount = RiderAccount(
    id = rider.string("id").orEmpty(),
    phoneNumber = rider.string("phoneNumber"),
    email = rider.string("email"),
    displayName = rider.string("displayName"),
    signInProvider = rider.string("signInProvider").orEmpty(),
    providers = runCatching { rider["providers"]!!.jsonArray.map { it.jsonPrimitive.content } }.getOrDefault(emptyList()),
    profileComplete = (rider["profileComplete"] as? JsonPrimitive)?.booleanOrNull ?: false,
)

/** Same wording as Android for the same Firebase errors. */
private fun firebaseMessage(reply: JsonObject?): String {
    val code = runCatching { reply!!["error"]!!.jsonObject.string("message") }.getOrNull().orEmpty().uppercase()
    return when {
        code.isEmpty() -> "Can't reach Firebase. Check the internet connection."
        "TOO_MANY_ATTEMPTS" in code || "QUOTA" in code -> "Too many attempts. Wait a few minutes and try again."
        "EMAIL_EXISTS" in code -> "An account already uses that email. Sign in instead."
        "INVALID_EMAIL" in code -> "Enter a valid email address."
        "INVALID_PASSWORD" in code || "INVALID_LOGIN_CREDENTIALS" in code -> "Email or password is incorrect."
        "EMAIL_NOT_FOUND" in code -> "No account uses that email. Create one first."
        "WEAK_PASSWORD" in code -> "Use a password of at least 6 characters."
        "OPERATION_NOT_ALLOWED" in code -> "Email sign-in is turned off. In Firebase, enable Email/Password."
        "USER_DISABLED" in code -> "This account is disabled."
        "TOKEN_EXPIRED" in code || "INVALID_REFRESH_TOKEN" in code || "USER_NOT_FOUND" in code -> "Sign in again."
        else -> "Sign-in failed ($code)."
    }
}

private fun validName(name: String) = name.isNotEmpty() && name.length <= 40 && name.none { it == '\n' || it == '\r' }

private fun validEmail(value: String): Boolean {
    val at = value.indexOf('@')
    if (at <= 0 || value.contains(' ')) return false
    val dot = value.indexOf('.', startIndex = at + 1)
    return dot > at + 1 && dot < value.lastIndex
}

private fun e164(value: String): String? {
    val compact = value.trim().replace(" ", "").replace("-", "")
    if (!compact.matches(Regex("""\+\d{8,15}"""))) return null
    return compact
}
