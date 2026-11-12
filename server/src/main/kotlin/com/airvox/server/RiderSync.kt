package com.airvox.server

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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

    /**
     * Removes the portal row for [userId] (the app_users id).
     * Null when that rider is already gone.
     */
    fun removeRider(userId: String): RemovedRider? = null

    /** [kind] is "consume" or "purchase". [credits] is how many points moved. */
    fun recordCredits(rider: Rider, kind: String, credits: Int) = Unit

    /** The balance currently shown on the phone, so the portal wallet matches it. */
    fun setWallet(rider: Rider, credits: Int) = Unit

    /**
     * Credits saved for this account in the portal.
     * Null when the lookup failed, so a missed call does not look like an empty wallet.
     */
    fun walletBalance(firebaseUid: String): Int? = null

    /** Active credit packs, cheapest first. Empty when Supabase has none. */
    fun activePlans(): List<PlanOffer> = emptyList()

    /** Free credits for a brand-new rider. The portal stores this; 20 until it is set. */
    fun welcomeCredits(): Int = 20

    /**
     * True when this Firebase account already has a portal row.
     * False when it does not. Null when the lookup failed, so a blip does not grant credits.
     */
    fun existingAccount(firebaseUid: String): Boolean? = false

    /**
     * A rider created or left a channel. [action] is "join", "leave", or "here".
     * [at] is the phone's time, so an offline channel keeps the time it really started.
     */
    fun reportChannel(rider: Rider, room: String, mode: String, action: String, at: String?) = Unit

    /** Ends channels nobody has checked in on for five minutes. */
    fun closeStaleChannels() = Unit

    /**
     * Staff closed this channel from the portal.
     * True when it was still open and is now ended.
     */
    fun stopChannel(channelId: String): Boolean = false
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
    private val stoppedRoomsFile: Path = Path.of(System.getenv("AIRVOX_DATA") ?: "data").resolve("stopped-rooms.txt"),
) : RiderSync {
    private val restUrl = supabaseUrl.trim().trimEnd('/') + "/rest/v1"
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "supabase-sync").apply { isDaemon = true } }
    private val stoppedRooms = ConcurrentHashMap<String, StaffStop>()
    private val stoppedGate = Any()

    init {
        if (Files.isRegularFile(stoppedRoomsFile)) {
            val now = java.time.Instant.now()
            Files.readAllLines(stoppedRoomsFile).forEach { line ->
                val parts = line.trim().split("|")
                if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) return@forEach
                val stoppedAt = parts.getOrNull(2)?.toLongOrNull()?.let(java.time.Instant::ofEpochMilli) ?: now
                stoppedRooms[roomKey(parts[0], parts[1])] = StaffStop(parts[0], parts[1], stoppedAt, now)
            }
            if (stoppedRooms.isNotEmpty()) writeStopped()
        }
        Thread({
            while (!Thread.currentThread().isInterrupted) {
                try {
                    sweepStoppedRooms()
                    Thread.sleep(1_000)
                } catch (_: InterruptedException) {
                    break
                } catch (error: Exception) {
                    System.err.println("Channel stop sweep failed: ${error.message}")
                    Thread.sleep(1_000)
                }
            }
        }, "channel-stop").apply { isDaemon = true }.start()
    }

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

    override fun existingAccount(firebaseUid: String): Boolean? {
        return try {
            val saved = get("app_users?firebase_uid=eq.${encode(firebaseUid)}&select=id")
            Json.parseToJsonElement(saved).jsonArray.isNotEmpty()
        } catch (error: Exception) {
            System.err.println("Supabase account lookup failed for rider $firebaseUid: ${error.message}")
            null
        }
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

    override fun walletBalance(firebaseUid: String): Int? {
        return try {
            val saved = get("app_users?firebase_uid=eq.${encode(firebaseUid)}&select=wallet_balance")
            val rows = Json.parseToJsonElement(saved).jsonArray
            if (rows.isEmpty()) return 0
            rows.first().jsonObject["wallet_balance"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        } catch (error: Exception) {
            System.err.println("Supabase wallet lookup failed for rider $firebaseUid: ${error.message}")
            null
        }
    }

    override fun removeRider(userId: String): RemovedRider? {
        val saved = get("app_users?id=eq.${encode(userId)}&select=id,firebase_uid")
        val rows = Json.parseToJsonElement(saved).jsonArray
        if (rows.isEmpty()) return null
        val firebaseUid = rows.first().jsonObject["firebase_uid"].textOrNull()
        // Clear rows that point at this rider before the rider row itself.
        listOf(
            "user_activity?user_id=eq.${encode(userId)}",
            "credit_transactions?user_id=eq.${encode(userId)}",
            "user_reports?reported_user_id=eq.${encode(userId)}",
        ).forEach { path ->
            runCatching { send(path, "", prefer = "return=minimal", method = "DELETE") }
        }
        listOf(
            "channels?started_by=eq.${encode(userId)}" to """{"started_by":null}""",
            "sos_incidents?user_id=eq.${encode(userId)}" to """{"user_id":null}""",
            "wallet_transactions?user_id=eq.${encode(userId)}" to """{"user_id":null}""",
        ).forEach { (path, body) ->
            runCatching { send(path, body, prefer = "return=minimal", method = "PATCH") }
        }
        send("app_users?id=eq.${encode(userId)}", "", prefer = "return=minimal", method = "DELETE")
        return RemovedRider(userId, firebaseUid)
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

    override fun reportChannel(rider: Rider, room: String, mode: String, action: String, at: String?) {
        val now = java.time.Instant.now().toString()
        val whenAt = at?.takeIf { it.isNotBlank() } ?: now
        val userId = riderUserId(rider)
        val open = openChannel(room, mode)
        if (action == "leave") {
            if (open != null) leaveChannel(open.id, userId, whenAt)
        } else if (open == null && action == "here" && isStopped(room, mode)) {
            // Staff closed this room while riders were still in it. A check-in must not open a new row.
        } else if (open == null) {
            if (action == "join") clearStopped(room, mode)
            val created = createChannel(userId, room, mode, whenAt, now)
            joinMember(created, userId, whenAt, now)
        } else {
            if (action == "join") clearStopped(room, mode)
            val joined = runCatching { java.time.Instant.parse(whenAt) }.getOrNull()
            val started = runCatching { java.time.Instant.parse(open.startedAt) }.getOrNull()
            if (action == "join" && joined != null && started != null && joined.isBefore(started)) {
                send(
                    "channels?id=eq.${open.id}",
                    """{"started_at":"$whenAt","last_seen_at":"$now"}""",
                    prefer = "return=minimal",
                    method = "PATCH",
                )
            } else {
                send(
                    "channels?id=eq.${open.id}",
                    """{"last_seen_at":"$now"}""",
                    prefer = "return=minimal",
                    method = "PATCH",
                )
            }
            joinMember(open.id, userId, whenAt, now)
        }
        closeStaleChannels()
    }

    override fun stopChannel(channelId: String): Boolean {
        if (!CHANNEL_ID.matches(channelId)) return false
        val saved = get("channels?id=eq.$channelId&select=id,room,mode,ended_at")
        val rows = Json.parseToJsonElement(saved).jsonArray
        if (rows.isEmpty()) return false
        val row = rows.first().jsonObject
        if (row["ended_at"].textOrNull() != null) return false
        val room = row["room"]!!.jsonPrimitive.content
        val mode = row["mode"]!!.jsonPrimitive.content
        val now = java.time.Instant.now().toString()
        send(
            "channel_members?channel_id=eq.$channelId&left_at=is.null",
            """{"left_at":"$now","last_seen_at":"$now"}""",
            prefer = "return=minimal",
            method = "PATCH",
        )
        val updated = send(
            "channels?id=eq.$channelId&ended_at=is.null&select=id",
            """{"ended_at":"$now"}""",
            prefer = "return=representation",
            method = "PATCH",
        )
        if (Json.parseToJsonElement(updated).jsonArray.isEmpty()) return false
        markStopped(room, mode)
        return true
    }

    override fun closeStaleChannels() {
        val cutoff = java.time.Instant.now().minusSeconds(5 * 60).toString()
        send(
            "channels?ended_at=is.null&last_seen_at=lt.${encode(cutoff)}",
            """{"ended_at":"$cutoff"}""",
            prefer = "return=minimal",
            method = "PATCH",
        )
    }

    private fun riderUserId(rider: Rider): String {
        val saved = get("app_users?firebase_uid=eq.${encode(rider.id)}&select=id")
        val rows = Json.parseToJsonElement(saved).jsonArray
        if (rows.isNotEmpty()) return rows.first().jsonObject["id"]!!.jsonPrimitive.content
        sync(rider, null)
        val again = get("app_users?firebase_uid=eq.${encode(rider.id)}&select=id")
        return Json.parseToJsonElement(again).jsonArray.first().jsonObject["id"]!!.jsonPrimitive.content
    }

    private fun openChannel(room: String, mode: String): OpenChannel? {
        val saved = get(
            "channels?room=eq.${encode(room)}&mode=eq.${encode(mode)}&ended_at=is.null&select=id,started_at&limit=1",
        )
        val rows = Json.parseToJsonElement(saved).jsonArray
        if (rows.isEmpty()) return null
        val row = rows.first().jsonObject
        return OpenChannel(
            id = row["id"]!!.jsonPrimitive.content,
            startedAt = row["started_at"]!!.jsonPrimitive.content,
        )
    }

    private fun createChannel(userId: String, room: String, mode: String, startedAt: String, seenAt: String): String {
        val body = buildJsonObject {
            put("room", room)
            put("mode", mode)
            put("started_by", userId)
            put("started_at", startedAt)
            put("last_seen_at", seenAt)
        }.toString()
        return try {
            val saved = send("channels?select=id", body, prefer = "return=representation")
            Json.parseToJsonElement(saved).jsonArray.first().jsonObject["id"]!!.jsonPrimitive.content
        } catch (error: IllegalStateException) {
            // Another phone created this room at the same moment.
            openChannel(room, mode)?.id ?: throw error
        }
    }

    private fun joinMember(channelId: String, userId: String, joinedAt: String, seenAt: String) {
        val existing = get(
            "channel_members?channel_id=eq.$channelId&user_id=eq.$userId&select=user_id",
        )
        if (Json.parseToJsonElement(existing).jsonArray.isEmpty()) {
            send(
                "channel_members",
                buildJsonObject {
                    put("channel_id", channelId)
                    put("user_id", userId)
                    put("joined_at", joinedAt)
                    put("last_seen_at", seenAt)
                }.toString(),
                prefer = "return=minimal",
            )
        } else {
            send(
                "channel_members?channel_id=eq.$channelId&user_id=eq.$userId",
                """{"left_at":null,"last_seen_at":"$seenAt"}""",
                prefer = "return=minimal",
                method = "PATCH",
            )
        }
    }

    private fun leaveChannel(channelId: String, userId: String, whenAt: String) {
        send(
            "channel_members?channel_id=eq.$channelId&user_id=eq.$userId",
            """{"left_at":"$whenAt","last_seen_at":"$whenAt"}""",
            prefer = "return=minimal",
            method = "PATCH",
        )
        val stillThere = get("channel_members?channel_id=eq.$channelId&left_at=is.null&select=user_id")
        if (Json.parseToJsonElement(stillThere).jsonArray.isEmpty()) {
            send(
                "channels?id=eq.$channelId",
                """{"ended_at":"$whenAt"}""",
                prefer = "return=minimal",
                method = "PATCH",
            )
        }
    }

    private data class OpenChannel(val id: String, val startedAt: String)

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
        val publisher = if (method == "DELETE") {
            HttpRequest.BodyPublishers.noBody()
        } else {
            HttpRequest.BodyPublishers.ofString(body)
        }
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

    private fun isStopped(room: String, mode: String): Boolean = stoppedRooms.containsKey(roomKey(room, mode))

    private fun markStopped(room: String, mode: String) {
        synchronized(stoppedGate) {
            val now = java.time.Instant.now()
            stoppedRooms[roomKey(room, mode)] = StaffStop(room, mode, now, now)
            writeStopped()
        }
    }

    private fun clearStopped(room: String, mode: String) {
        synchronized(stoppedGate) {
            if (stoppedRooms.remove(roomKey(room, mode)) == null) return
            writeStopped()
        }
    }

    /**
     * The phone API that is already running opens a room again on the next check-in.
     * End the original row, and drop the copies it creates while riders are still in the room.
     * A room that has been quiet for two minutes can be opened again.
     */
    private fun sweepStoppedRooms() {
        val snapshot = stoppedRooms.values.toList()
        for (stop in snapshot) {
            val current = stoppedRooms[roomKey(stop.room, stop.mode)] ?: continue
            if (current.stoppedAt != stop.stoppedAt) continue
            val open = openRow(stop.room, stop.mode) ?: continue
            val quiet = java.time.Duration.between(stop.lastClosedAt, open.startedAt)
            if (!quiet.isNegative && quiet.seconds >= 120) {
                clearStopped(stop.room, stop.mode)
                continue
            }
            if (open.startedAt.isBefore(stop.stoppedAt)) {
                endOpen(open.id)
            } else {
                removeOpen(open.id)
            }
            val closed = java.time.Instant.now()
            synchronized(stoppedGate) {
                val current = stoppedRooms[roomKey(stop.room, stop.mode)] ?: return@synchronized
                stoppedRooms[roomKey(stop.room, stop.mode)] = current.copy(lastClosedAt = closed)
                writeStopped()
            }
        }
    }

    private fun openRow(room: String, mode: String): OpenRow? {
        val saved = get(
            "channels?room=eq.${encode(room)}&mode=eq.${encode(mode)}&ended_at=is.null&select=id,started_at&limit=1",
        )
        val rows = Json.parseToJsonElement(saved).jsonArray
        if (rows.isEmpty()) return null
        val row = rows.first().jsonObject
        val started = runCatching { java.time.Instant.parse(row["started_at"]!!.jsonPrimitive.content) }.getOrNull()
            ?: return null
        return OpenRow(row["id"]!!.jsonPrimitive.content, started)
    }

    private fun endOpen(channelId: String) {
        val now = java.time.Instant.now().toString()
        send(
            "channel_members?channel_id=eq.$channelId&left_at=is.null",
            """{"left_at":"$now","last_seen_at":"$now"}""",
            prefer = "return=minimal",
            method = "PATCH",
        )
        send(
            "channels?id=eq.$channelId&ended_at=is.null",
            """{"ended_at":"$now"}""",
            prefer = "return=minimal",
            method = "PATCH",
        )
    }

    private fun removeOpen(channelId: String) {
        send(
            "channel_members?channel_id=eq.$channelId",
            "",
            prefer = "return=minimal",
            method = "DELETE",
        )
        send(
            "channels?id=eq.$channelId",
            "",
            prefer = "return=minimal",
            method = "DELETE",
        )
    }

    private fun writeStopped() {
        val parent = stoppedRoomsFile.parent
        if (parent != null) Files.createDirectories(parent)
        val lines = stoppedRooms.values.joinToString("\n") { stop ->
            "${stop.room}|${stop.mode}|${stop.stoppedAt.toEpochMilli()}|${stop.lastClosedAt.toEpochMilli()}"
        }
        Files.writeString(stoppedRoomsFile, lines)
    }

    private fun roomKey(room: String, mode: String): String = "$room|$mode"

    private data class StaffStop(
        val room: String,
        val mode: String,
        val stoppedAt: java.time.Instant,
        val lastClosedAt: java.time.Instant,
    )

    private data class OpenRow(val id: String, val startedAt: java.time.Instant)

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8)

    private companion object {
        val CHANNEL_ID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}

private fun JsonElement?.textOrNull(): String? {
    if (this == null || this is JsonNull || !jsonPrimitive.isString) return null
    return jsonPrimitive.content.takeIf { it.isNotBlank() }
}
