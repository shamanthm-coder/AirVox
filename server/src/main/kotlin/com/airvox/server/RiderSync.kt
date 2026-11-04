package com.airvox.server

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Copies riders somewhere the admin portal can read them. */
fun interface RiderSync {
    /** [platform] is "Android" or "iOS" when the app said so. */
    fun riderSeen(rider: Rider, platform: String?)

    /** The rider signed out of the app, so the portal should stop counting them as logged in. */
    fun riderSignedOut(firebaseUid: String) = Unit

    /** [kind] is "consume" or "purchase". [credits] is how many points moved. */
    fun recordCredits(rider: Rider, kind: String, credits: Int) = Unit

    /** The balance currently shown on the phone, so the portal wallet matches it. */
    fun setWallet(rider: Rider, credits: Int) = Unit

    /** Active credit packs, cheapest first. Empty when Supabase has none. */
    fun activePlans(): List<PlanOffer> = emptyList()

    /** Free credits for a brand-new rider. The portal stores this; 20 until it is set. */
    fun welcomeCredits(): Int = 20
}

object NoRiderSync : RiderSync {
    override fun riderSeen(rider: Rider, platform: String?) = Unit
}

/**
 * Writes riders to Supabase's app_users table and marks them active today in
 * user_activity, so the portal's dashboard counts them.
 *
 * Runs in the background and never fails a sign-in: if Supabase is down the
 * rider still gets in, and the next check-in catches up.
 * Needs the service_role (secret) key; it stays on this server only.
 */
class SupabaseRiderSync(
    supabaseUrl: String,
    private val serviceKey: String,
) : RiderSync {
    private val restUrl = supabaseUrl.trim().trimEnd('/') + "/rest/v1"
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "supabase-sync").apply { isDaemon = true } }

    override fun riderSeen(rider: Rider, platform: String?) {
        worker.execute {
            runCatching { sync(rider, platform) }.onFailure { error ->
                System.err.println("Supabase sync failed for rider ${rider.id}: ${error.message}")
            }
        }
    }

    override fun activePlans(): List<PlanOffer> {
        val body = get("credit_plans?active=eq.true&select=id,name,credits,rupees&order=credits.asc")
        return Json.decodeFromString(body)
    }

    override fun welcomeCredits(): Int {
        val body = get("credit_settings?id=eq.1&select=welcome_credits")
        val rows = Json.parseToJsonElement(body).jsonArray
        if (rows.isEmpty()) return 20
        return rows.first().jsonObject["welcome_credits"]!!.jsonPrimitive.content.toInt()
    }

    override fun recordCredits(rider: Rider, kind: String, credits: Int) {
        worker.execute {
            runCatching {
                sync(rider, null)
                val saved = get("app_users?firebase_uid=eq.${encode(rider.id)}&select=id")
                val userId = Json.parseToJsonElement(saved).jsonArray.first().jsonObject["id"]!!.jsonPrimitive.content
                send(
                    "credit_transactions",
                    buildJsonObject {
                        put("user_id", userId)
                        put("kind", kind)
                        put("credits", credits)
                    }.toString(),
                    prefer = "return=minimal",
                )
            }.onFailure { error ->
                System.err.println("Supabase credit transaction failed for rider ${rider.id}: ${error.message}")
            }
        }
    }

    override fun setWallet(rider: Rider, credits: Int) {
        worker.execute {
            runCatching {
                send(
                    "app_users?firebase_uid=eq.${encode(rider.id)}",
                    """{"wallet_balance":$credits}""",
                    prefer = "return=minimal",
                    method = "PATCH",
                )
            }.onFailure { error ->
                System.err.println("Supabase wallet update failed for rider ${rider.id}: ${error.message}")
            }
        }
    }

    override fun riderSignedOut(firebaseUid: String) {
        worker.execute {
            runCatching {
                send(
                    "app_users?firebase_uid=eq.${encode(firebaseUid)}",
                    """{"logged_in":false}""",
                    prefer = "return=minimal",
                    method = "PATCH",
                )
            }.onFailure { error ->
                System.err.println("Supabase sign-out failed for rider $firebaseUid: ${error.message}")
            }
        }
    }

    private fun sync(rider: Rider, platform: String?) {
        val row = buildJsonObject {
            put("firebase_uid", rider.id)
            put("name", rider.displayName?.takeIf { it.isNotBlank() } ?: rider.email ?: "Rider")
            put("phone", rider.phoneNumber)
            put("email", rider.email)
            put("sign_in_provider", rider.signInProvider)
            // Leave the stored platform alone when this request didn't say.
            if (platform != null) put("platform", platform)
            put("logged_in", true)
            put("last_active_at", java.time.Instant.now().toString())
        }
        // Columns not sent (status, wallet, created_at) keep their stored values.
        val saved = send(
            "app_users?on_conflict=firebase_uid&select=id",
            row.toString(),
            prefer = "resolution=merge-duplicates,return=representation",
        )
        val userId = Json.parseToJsonElement(saved).jsonArray.first().jsonObject["id"]!!.jsonPrimitive.content
        // The day defaults to today in the database.
        send(
            "user_activity?on_conflict=user_id,day",
            buildJsonObject { put("user_id", userId) }.toString(),
            prefer = "resolution=ignore-duplicates,return=minimal",
        )
    }

    private fun send(path: String, body: String, prefer: String, method: String = "POST"): String {
        val publisher = HttpRequest.BodyPublishers.ofString(body)
        val request = HttpRequest.newBuilder(URI.create("$restUrl/$path"))
            .timeout(Duration.ofSeconds(15))
            .header("apikey", serviceKey)
            .header("Content-Type", "application/json")
            .header("Prefer", prefer)
            .apply {
                // Legacy service_role keys are JWTs and also go in Authorization; new sb_secret_ keys don't.
                if (!serviceKey.startsWith("sb_")) header("Authorization", "Bearer $serviceKey")
            }
            .method(method, publisher)
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("HTTP ${response.statusCode()} from $path: ${response.body().take(300)}")
        }
        return response.body()
    }

    private fun get(path: String): String {
        val request = HttpRequest.newBuilder(URI.create("$restUrl/$path"))
            .timeout(Duration.ofSeconds(15))
            .header("apikey", serviceKey)
            .header("Accept", "application/json")
            .apply {
                if (!serviceKey.startsWith("sb_")) header("Authorization", "Bearer $serviceKey")
            }
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("HTTP ${response.statusCode()} from $path: ${response.body().take(300)}")
        }
        return response.body()
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8)
}
