package com.airvox.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val apiJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    explicitNulls = true
}

fun Application.airvoxApi(
    verifier: IdTokenVerifier,
    store: RiderStore,
    sync: RiderSync = NoRiderSync,
    staff: StaffGate = StaffGate { false },
    accounts: AccountDeleter = AccountDeleter { false },
) {
    // Phones that already reported a real balance. A later 0 from those phones is a real spend.
    val phonesHoldingCredits = ConcurrentHashMap.newKeySet<String>()
    install(ContentNegotiation) { json(apiJson) }
    install(CORS) {
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.Authorization)
        allowHost("127.0.0.1:5180", schemes = listOf("http"))
        allowHost("localhost:5180", schemes = listOf("http"))
    }
    install(StatusPages) {
        exception<TokenRejected> { call, cause ->
            call.respond(HttpStatusCode.Unauthorized, ApiError("invalid_token", cause.message ?: "Sign in again."))
        }
        exception<TokenCheckUnavailable> { call, cause ->
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ApiError("auth_unavailable", cause.message ?: "Try again in a moment."),
            )
        }
        exception<StaffCheckUnavailable> { call, _ ->
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ApiError("staff_unavailable", "Couldn't check staff access. Try again."),
            )
        }
        exception<RiderRemoved> { call, _ ->
            call.respond(HttpStatusCode.Gone, ApiError("account_removed", "This account was deleted."))
        }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_body", "Send a display name."))
        }
    }
    routing {
        get("/health") {
            call.respond(HealthResponse(status = "ok"))
        }
        post("/v1/session") {
            val identity = verifier.verify(call.requireToken())
            if (call.stopIfDeleted(store, identity.uid)) return@post
            val saved = store.upsert(identity)
            // Look up the portal account before copying this sign-in, or an old rider looks new.
            val known = runCatching { sync.existingAccount(identity.uid) }.getOrNull()
            val welcome = welcomeCreditsFor(store, sync, identity.uid, known)
            if (welcome > 0) sync.recordCredits(saved.rider, "grant", welcome)
            val rider = if (known == true) store.acceptReturningRider(identity.uid) ?: saved.rider else saved.rider
            sync.riderSeen(rider, call.platform())
            call.respond(
                RiderResponse(
                    rider,
                    welcomeCredits = welcome,
                    balanceOffset = store.balanceOffset(identity.uid),
                    walletBalance = runCatching { sync.walletBalance(identity.uid) }.getOrNull() ?: 0,
                    sessionId = saved.sessionId,
                ),
            )
        }
        // The app calls this while it is open. A mismatched session means a newer phone signed in.
        post("/v1/heartbeat") {
            val identity = verifier.verify(call.requireToken())
            if (call.stopIfDeleted(store, identity.uid)) return@post
            val rider = store.find(identity.uid)
            if (rider == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ApiError("unknown_rider", "Sign in again to create your Airvox profile."),
                )
            } else if (!store.sessionMatches(identity.uid, call.request.headers["X-Airvox-Session"])) {
                call.respond(
                    HttpStatusCode.Conflict,
                    ApiError("signed_in_elsewhere", "This account signed in on another phone."),
                )
            } else {
                val body = runCatching { call.receive<HeartbeatBody>() }.getOrNull()
                val credits = body?.credits ?: -1
                sync.riderSeen(rider, call.platform())
                if (credits in 0..1_000_000) {
                    val stored = runCatching { sync.walletBalance(identity.uid) }.getOrNull()
                    // A phone that just signed in, or that could not reach the server, still has 0
                    // until it loads the saved wallet. That 0 must not wipe the portal balance.
                    val emptyPhone = credits == 0 &&
                        (stored == null || stored > 0) &&
                        identity.uid !in phonesHoldingCredits
                    if (credits > 0) phonesHoldingCredits.add(identity.uid)
                    if (!emptyPhone) {
                        sync.setWallet(rider, store.walletToStore(identity.uid, credits, body?.clearOffset == true))
                    }
                }
                val room = normalizeRoom(body?.room)
                val mode = normalizeMode(body?.mode)
                if (room != null && mode != null) {
                    runCatching { sync.reportChannel(rider, room, mode, "here", null) }
                        .onFailure { error -> System.err.println("Channel check-in failed: ${error.message}") }
                } else {
                    runCatching { sync.closeStaleChannels() }
                        .onFailure { error -> System.err.println("Channel check-in failed: ${error.message}") }
                }
                call.respond(HttpStatusCode.NoContent)
            }
        }
        get("/v1/me") {
            val identity = verifier.verify(call.requireToken())
            if (call.stopIfDeleted(store, identity.uid)) return@get
            val rider = store.find(identity.uid)
            if (rider == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ApiError("unknown_rider", "Sign in again to create your Airvox profile."),
                )
            } else {
                call.respond(RiderResponse(rider))
            }
        }
        patch("/v1/me") {
            val identity = verifier.verify(call.requireToken())
            if (call.stopIfDeleted(store, identity.uid)) return@patch
            val body = call.receive<DisplayNameBody>()
            val name = body.displayName.trim()
            if (name.isEmpty() || name.length > 40 || name.any { it == '\n' || it == '\r' }) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("invalid_name", "Use a name of 1 to 40 characters."),
                )
                return@patch
            }
            store.upsert(identity)
            val rider = if (body.completeProfile) {
                val phone = normalizePhone(body.phoneNumber)
                if (phone == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("invalid_phone", "Enter a mobile number with the country code."),
                    )
                    return@patch
                }
                store.completeProfile(identity.uid, name, phone)
            } else {
                store.rename(identity.uid, name)
            } ?: error("Profile disappeared after it was created.")
            sync.riderSeen(rider, call.platform())
            call.respond(RiderResponse(rider))
        }
        get("/v1/plans") {
            val identity = verifier.verify(call.requireToken())
            if (call.stopIfDeleted(store, identity.uid)) return@get
            try {
                call.respond(PlansResponse(sync.activePlans()))
            } catch (_: Exception) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ApiError("plans_unavailable", "Plans aren't available yet."),
                )
            }
        }
        post("/v1/channels") {
            val identity = verifier.verify(call.requireToken())
            if (call.stopIfDeleted(store, identity.uid)) return@post
            val rider = store.find(identity.uid)
            if (rider == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ApiError("unknown_rider", "Sign in again to create your Airvox profile."),
                )
                return@post
            }
            val body = call.receive<ChannelBody>()
            val room = normalizeRoom(body.room)
            val mode = normalizeMode(body.mode)
            val action = body.action.trim().lowercase()
            if (room == null || mode == null || (action != "join" && action != "leave")) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("invalid_channel", "Send the room, Bluetooth, Hotspot, or Internet, and join or leave."),
                )
                return@post
            }
            try {
                sync.reportChannel(rider, room, mode, action, normalizeWhen(body.at))
                call.respond(HttpStatusCode.NoContent)
            } catch (error: Exception) {
                System.err.println("Channel update failed: ${error.message}")
                call.respond(
                    HttpStatusCode.BadGateway,
                    ApiError("channel_unavailable", "Couldn't save this channel. The phone will try again."),
                )
            }
        }
        post("/v1/channels/{id}/stop") {
            if (!staff.allow(call.requireToken())) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    ApiError("not_staff", "Your account isn't allowed to stop channels."),
                )
                return@post
            }
            val id = call.parameters["id"].orEmpty()
            if (!RIDER_ID.matches(id)) {
                call.respond(HttpStatusCode.BadRequest, ApiError("invalid_channel", "That channel id is not valid."))
                return@post
            }
            val stopped = try {
                sync.stopChannel(id)
            } catch (error: Exception) {
                System.err.println("Channel stop failed: ${error.message}")
                call.respond(
                    HttpStatusCode.BadGateway,
                    ApiError("stop_failed", "Couldn't stop this channel. Try again."),
                )
                return@post
            }
            if (!stopped) {
                call.respond(
                    HttpStatusCode.Conflict,
                    ApiError("already_ended", "This channel already ended."),
                )
                return@post
            }
            call.respond(HttpStatusCode.NoContent)
        }
        post("/v1/credits") {
            val identity = verifier.verify(call.requireToken())
            if (call.stopIfDeleted(store, identity.uid)) return@post
            val rider = store.find(identity.uid)
            if (rider == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ApiError("unknown_rider", "Sign in again to create your Airvox profile."),
                )
                return@post
            }
            val body = call.receive<CreditBody>()
            val allowed = body.kind == "consume" || body.kind == "purchase" || body.kind == "grant"
            if (!allowed || body.credits !in 1..10_000) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("invalid_credits", "Send a credit use or a credit pack."),
                )
                return@post
            }
            sync.recordCredits(rider, body.kind, body.credits)
            call.respond(HttpStatusCode.NoContent)
        }
        delete("/v1/session") {
            val identity = verifier.verify(call.requireToken())
            if (call.stopIfDeleted(store, identity.uid)) return@delete
            sync.riderSignedOut(identity.uid)
            call.respond(HttpStatusCode.NoContent)
        }
        delete("/v1/riders/{id}") {
            if (!staff.allow(call.requireToken())) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    ApiError("not_staff", "Your account isn't allowed to delete riders."),
                )
                return@delete
            }
            val id = call.parameters["id"].orEmpty()
            if (!RIDER_ID.matches(id)) {
                call.respond(HttpStatusCode.BadRequest, ApiError("invalid_user", "That rider id is not valid."))
                return@delete
            }
            val removed = try {
                sync.removeRider(id)
            } catch (error: Exception) {
                System.err.println("Rider delete failed: ${error.message}")
                call.respond(
                    HttpStatusCode.BadGateway,
                    ApiError("delete_failed", "Couldn't delete this rider. Try again."),
                )
                return@delete
            }
            if (removed == null) {
                call.respond(HttpStatusCode.NotFound, ApiError("unknown_rider", "That rider is already gone."))
                return@delete
            }
            val uid = removed.firebaseUid
            if (uid != null) store.remove(uid)
            val emailFreed = uid == null || accounts.delete(uid)
            call.respond(RemovedRiderResponse(emailFreed = emailFreed))
        }
    }
}

private val RIDER_ID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

private suspend fun ApplicationCall.stopIfDeleted(store: RiderStore, uid: String): Boolean {
    if (!store.isRemoved(uid)) return false
    respond(HttpStatusCode.Gone, ApiError("account_removed", "This account was deleted."))
    return true
}

private fun readWelcomeCredits(sync: RiderSync): Int {
    return runCatching { sync.welcomeCredits() }.getOrDefault(20).coerceIn(0, 10_000)
}

/**
 * 20 only when this Firebase account has never been stored.
 * An old account stays at 0 even if the local rider file was recreated.
 * A failed lookup grants nothing and is tried again on the next sign-in.
 */
private fun welcomeCreditsFor(store: RiderStore, sync: RiderSync, uid: String, known: Boolean?): Int {
    if (store.welcomeGranted(uid)) return 0
    return when (known) {
        true -> {
            store.markWelcomeGranted(uid)
            0
        }
        false -> {
            val amount = readWelcomeCredits(sync)
            store.markWelcomeGranted(uid)
            amount
        }
        null -> 0
    }
}

private fun normalizeRoom(value: String?): String? {
    val room = value?.trim()?.lowercase()?.filter { it.isLetterOrDigit() }?.take(16).orEmpty()
    if (room.length < 3) return null
    return room
}

private fun normalizeMode(value: String?): String? = when (value?.trim()?.lowercase()) {
    "bluetooth" -> "Bluetooth"
    "hotspot" -> "Hotspot"
    "internet" -> "Internet"
    else -> null
}

/** The phone's clock, kept only when it is a real recent time. Otherwise now. */
private fun normalizeWhen(value: String?): String {
    val now = java.time.Instant.now()
    val parsed = runCatching { java.time.Instant.parse(value) }.getOrNull() ?: return now.toString()
    if (parsed.isAfter(now.plusSeconds(120))) return now.toString()
    if (parsed.isBefore(now.minusSeconds(24 * 3600))) return now.toString()
    return parsed.toString()
}

private fun normalizePhone(value: String?): String? {
    val compact = value?.trim()?.replace(" ", "")?.replace("-", "").orEmpty()
    if (!compact.matches(Regex("""\+\d{8,15}"""))) return null
    return compact
}

/** "Android" or "iOS" from the app's X-Airvox-Platform header, or null. */
private fun ApplicationCall.platform(): String? = when (request.headers["X-Airvox-Platform"]?.trim()?.lowercase()) {
    "android" -> "Android"
    "ios" -> "iOS"
    else -> null
}

private fun ApplicationCall.requireToken(): String {
    val header = request.headers["Authorization"].orEmpty()
    if (!header.startsWith("Bearer ")) {
        throw TokenRejected("Sign in again.")
    }
    return header.removePrefix("Bearer ").trim().ifBlank {
        throw TokenRejected("Sign in again.")
    }
}

fun main() {
    val projectId = loadProjectId()
    if (projectId.isNullOrBlank()) {
        System.err.println(
            "Set firebase.project.id in local.properties, place google-services.json in androidApp, or set FIREBASE_PROJECT_ID.",
        )
        exitProcess(1)
    }
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val data = Path.of(System.getenv("AIRVOX_DATA") ?: "data").resolve("riders.json")
    val store = RiderStore(data)
    val verifier = FirebaseIdTokenVerifier(projectId, GoogleCertSigningKeys())
    val supabaseUrl = setting("SUPABASE_URL", "supabase.url")
    val supabaseKey = setting("SUPABASE_SERVICE_ROLE_KEY", "supabase.service.role.key")
    val anonKey = setting("SUPABASE_ANON_KEY", "supabase.anon.key")
    val sync = if (supabaseUrl != null && supabaseKey != null) {
        println("Copying riders to Supabase at $supabaseUrl")
        SupabaseRiderSync(supabaseUrl, supabaseKey)
    } else {
        println("Riders stay in $data only. Set supabase.url and supabase.service.role.key in local.properties to copy them to Supabase.")
        NoRiderSync
    }
    val staff = if (supabaseUrl != null && supabaseKey != null && anonKey != null) {
        SupabaseStaffGate(supabaseUrl, anonKey, supabaseKey)
    } else {
        StaffGate { false }
    }
    val serviceAccount = setting("FIREBASE_SERVICE_ACCOUNT", "firebase.service.account")?.let { path ->
        val file = Path.of(path)
        if (Files.isRegularFile(file)) Files.readString(file) else null
    }
    if (serviceAccount == null) {
        println("No firebase.service.account file. Deleting a rider may leave their sign-in email reserved.")
    }
    val accounts = FirebaseAccountDeleter(projectId, setting("FIREBASE_API_KEY", "firebase.api.key"), serviceAccount)
    println("Airvox API on http://0.0.0.0:$port (emulator: http://10.0.2.2:$port), project $projectId")
    embeddedServer(Netty, host = "0.0.0.0", port = port) {
        airvoxApi(verifier, store, sync, staff, accounts)
    }.start(wait = true)
}

/** An environment variable, or else a key from the nearest local.properties. */
internal fun setting(env: String, property: String): String? {
    System.getenv(env)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    var dir = Path.of("").toAbsolutePath()
    repeat(5) {
        val properties = dir.resolve("local.properties")
        if (Files.exists(properties)) {
            val value = Properties().apply {
                Files.newInputStream(properties).use(::load)
            }.getProperty(property)?.trim().orEmpty()
            if (value.isNotEmpty()) return value
        }
        dir = dir.parent ?: return null
    }
    return null
}

internal fun loadProjectId(): String? {
    System.getenv("FIREBASE_PROJECT_ID")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    var dir = Path.of("").toAbsolutePath()
    repeat(5) {
        val properties = dir.resolve("local.properties")
        if (Files.exists(properties)) {
            val id = Properties().apply {
                Files.newInputStream(properties).use(::load)
            }.getProperty("firebase.project.id")?.trim().orEmpty()
            if (id.isNotEmpty()) return id
        }
        val services = dir.resolve("androidApp").resolve("google-services.json")
        if (Files.exists(services)) {
            val id = projectIdFromGoogleServices(Files.readString(services))
            if (!id.isNullOrBlank()) return id
        }
        dir = dir.parent ?: return null
    }
    return null
}

private fun projectIdFromGoogleServices(text: String): String? {
    return runCatching {
        Json.parseToJsonElement(text).jsonObject["project_info"]
            ?.jsonObject
            ?.get("project_id")
            ?.jsonPrimitive
            ?.content
    }.getOrNull()
}
