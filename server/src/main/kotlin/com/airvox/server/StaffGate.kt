package com.airvox.server

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
