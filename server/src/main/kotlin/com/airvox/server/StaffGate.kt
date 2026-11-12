package com.airvox.server

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The portal's signed-in staff session, not a rider's phone sign-in. */
fun interface StaffGate {
    fun allow(accessToken: String): Boolean
}

class StaffCheckUnavailable(message: String) : Exception(message)

/**
 * Accepts a Supabase staff session: the token must belong to a real user,
 * and that user must have a row in public.staff.
 */
class SupabaseStaffGate(
    supabaseUrl: String,
    private val anonKey: String,
    private val serviceKey: String,
) : StaffGate {
    private val root = supabaseUrl.trim().trimEnd('/')
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    override fun allow(accessToken: String): Boolean {
        if (accessToken.isBlank() || accessToken.length > 8_000) return false
        val user = call("$root/auth/v1/user", anonKey, accessToken) ?: return false
        val id = runCatching {
            Json.parseToJsonElement(user).jsonObject["id"]?.jsonPrimitive?.content
        }.getOrNull()?.takeIf { USER_ID.matches(it) } ?: return false
        val rows = call("$root/rest/v1/staff?user_id=eq.$id&select=role", serviceKey, serviceKey)
            ?: throw StaffCheckUnavailable("Couldn't check staff access.")
        return runCatching { Json.parseToJsonElement(rows).jsonArray.isNotEmpty() }
            .getOrElse { throw StaffCheckUnavailable("Couldn't check staff access.") }
    }

    /** Null when the token is not a signed-in user. Throws when the check itself failed. */
    private fun call(url: String, apiKey: String, bearer: String): String? {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("apikey", apiKey)
            .header("Authorization", "Bearer $bearer")
            .header("Accept", "application/json")
            .GET()
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (_: Exception) {
            throw StaffCheckUnavailable("Couldn't check staff access.")
        }
        if (response.statusCode() == 401 || response.statusCode() == 403) return null
        if (response.statusCode() !in 200..299) throw StaffCheckUnavailable("Couldn't check staff access.")
        return response.body()
    }

    private companion object {
        val USER_ID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}

/** The accounts that can sign in to the portal. */
interface StaffAccounts {
    fun list(): List<StaffAccount>

    /** Throws [StaffAccountRefused] with a message for the portal when Supabase turns the account down. */
    fun create(email: String, password: String): StaffAccount
}

class StaffAccountRefused(message: String) : Exception(message)

object NoStaffAccounts : StaffAccounts {
    override fun list(): List<StaffAccount> = emptyList()

    override fun create(email: String, password: String): StaffAccount =
        throw StaffAccountRefused("This server isn't connected to Supabase.")
}

/**
 * Creates the Supabase sign-in and its row in public.staff.
 * Needs the service_role (secret) key, which is why the portal asks this server to do it.
 */
class SupabaseStaffAccounts(
    supabaseUrl: String,
    private val serviceKey: String,
) : StaffAccounts {
    private val root = supabaseUrl.trim().trimEnd('/')
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    override fun list(): List<StaffAccount> {
        val rows = send("GET", "$root/rest/v1/staff?select=user_id,role,created_at&order=created_at.asc", null)
        check(rows.statusCode() in 200..299) { "HTTP ${rows.statusCode()} from staff" }
        val users = send("GET", "$root/auth/v1/admin/users?per_page=1000", null)
        check(users.statusCode() in 200..299) { "HTTP ${users.statusCode()} from auth users" }
        val emails = Json.parseToJsonElement(users.body()).jsonObject["users"]!!.jsonArray.associate { user ->
            user.jsonObject.text("id") to user.jsonObject.text("email")
        }
        return Json.parseToJsonElement(rows.body()).jsonArray.map { row ->
            val staff = row.jsonObject
            StaffAccount(
                email = emails[staff.text("user_id")] ?: "Unknown account",
                role = staff.text("role") ?: "admin",
                createdAt = staff.text("created_at"),
            )
        }
    }

    override fun create(email: String, password: String): StaffAccount {
        val body = buildJsonObject {
            put("email", email)
            put("password", password)
            // Staff made here can sign in at once, without a confirmation email.
            put("email_confirm", true)
        }.toString()
        val made = send("POST", "$root/auth/v1/admin/users", body)
        if (made.statusCode() == 422 || made.statusCode() == 400) {
            val reply = runCatching { Json.parseToJsonElement(made.body()).jsonObject }.getOrNull()
            val reason = reply?.text("msg") ?: reply?.text("message") ?: "Supabase didn't accept this account."
            throw StaffAccountRefused(
                if ("already" in reason) "An account with this email already exists." else reason,
            )
        }
        check(made.statusCode() in 200..299) { "HTTP ${made.statusCode()} from auth users" }
        val id = Json.parseToJsonElement(made.body()).jsonObject.text("id") ?: error("Supabase returned no user id.")
        val row = buildJsonObject {
            put("user_id", id)
            put("role", "admin")
        }.toString()
        val saved = send("POST", "$root/rest/v1/staff", row)
        if (saved.statusCode() !in 200..299) {
            // Without the staff row the sign-in is useless. Remove it so the email can be tried again.
            runCatching { send("DELETE", "$root/auth/v1/admin/users/$id", null) }
            error("HTTP ${saved.statusCode()} from staff: ${saved.body().take(200)}")
        }
        return StaffAccount(email = email, role = "admin")
    }

    private fun send(method: String, url: String, body: String?): HttpResponse<String> {
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("apikey", serviceKey)
            .header("Authorization", "Bearer $serviceKey")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .method(method, publisher)
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }
}

private fun JsonObject.text(key: String): String? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    return value.jsonPrimitive.content.takeIf { it.isNotBlank() }
}
