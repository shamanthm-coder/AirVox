package com.airvox.server

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
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
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val apiJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    explicitNulls = true
}

fun Application.airvoxApi(verifier: IdTokenVerifier, store: RiderStore, sync: RiderSync = NoRiderSync) {
    install(ContentNegotiation) { json(apiJson) }
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
            val saved = store.upsert(identity)
            sync.riderSeen(saved.rider, call.platform())
            val welcome = if (saved.created) readWelcomeCredits(sync) else 0
            if (welcome > 0) sync.recordCredits(saved.rider, "grant", welcome)
            call.respond(RiderResponse(saved.rider, welcomeCredits = welcome))
        }
        // The app calls this every couple of minutes while it is open.
        post("/v1/heartbeat") {
            val identity = verifier.verify(call.requireToken())
            val rider = store.find(identity.uid)
            if (rider == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ApiError("unknown_rider", "Sign in again to create your Airvox profile."),
                )
            } else {
                val credits = runCatching { call.receive<HeartbeatBody>().credits }.getOrDefault(-1)
                sync.riderSeen(rider, call.platform())
                if (credits in 0..1_000_000) sync.setWallet(rider, credits)
                call.respond(HttpStatusCode.NoContent)
            }
        }
        get("/v1/me") {
            val identity = verifier.verify(call.requireToken())
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
            verifier.verify(call.requireToken())
            try {
                call.respond(PlansResponse(sync.activePlans()))
            } catch (error: Exception) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ApiError("plans_unavailable", "Plans aren't available yet."),
                )
            }
        }
        post("/v1/credits") {
            val identity = verifier.verify(call.requireToken())
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
            sync.riderSignedOut(identity.uid)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun readWelcomeCredits(sync: RiderSync): Int {
    return runCatching { sync.welcomeCredits() }.getOrDefault(20).coerceIn(0, 10_000)
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
    val sync = if (supabaseUrl != null && supabaseKey != null) {
        println("Copying riders to Supabase at $supabaseUrl")
        SupabaseRiderSync(supabaseUrl, supabaseKey)
    } else {
        println("Riders stay in $data only. Set supabase.url and supabase.service.role.key in local.properties to copy them to Supabase.")
        NoRiderSync
    }
    println("Airvox API on http://0.0.0.0:$port (emulator: http://10.0.2.2:$port), project $projectId")
    embeddedServer(Netty, host = "0.0.0.0", port = port) {
        airvoxApi(verifier, store, sync)
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
