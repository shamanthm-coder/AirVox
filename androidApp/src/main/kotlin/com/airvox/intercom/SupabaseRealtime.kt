package com.airvox.intercom

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Supabase Realtime channel used only to introduce two phones and pass call setup.
 * Voice itself travels over WebRTC, not through Supabase.
 */
class SupabaseRealtime(
    private val supabaseUrl: String,
    private val anonKey: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onJoined()

        fun onPresence(members: Map<String, String>)

        fun onSignal(message: JSONObject)

        fun onClosed(reason: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val refCounter = AtomicInteger(1)
    private val members = linkedMapOf<String, String>()
    private var socket: WebSocket? = null
    private var topic: String = ""
    private var joinRef: String = ""
    private var joinMessageRef: String = ""
    private var closedByUs = false

    private val heartbeat = object : Runnable {
        override fun run() {
            sendRaw(
                JSONObject()
                    .put("topic", "phoenix")
                    .put("event", "heartbeat")
                    .put("payload", JSONObject())
                    .put("ref", nextRef()),
            )
            mainHandler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    fun connect(roomCode: String, peerId: String, displayName: String) {
        closedByUs = false
        members.clear()
        topic = "realtime:airvox:$roomCode"
        joinRef = nextRef()
        joinMessageRef = joinRef
        val host = supabaseUrl
            .trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
        val request = Request.Builder()
            .url("wss://$host/realtime/v1/websocket?apikey=$anonKey&vsn=1.0.0")
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                sendJoin(peerId, displayName)
                mainHandler.postDelayed(heartbeat, HEARTBEAT_MS)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text, displayName)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (closedByUs) return
                Log.e(TAG, "Realtime connection failed", t)
                notifyClosed(t.message ?: "Could not reach the channel.")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (closedByUs) return
                notifyClosed(reason.ifBlank { "Channel closed." })
            }
        })
    }

    fun rename(name: String) {
        val next = name.trim().take(40)
        if (next.isBlank() || next == pendingName) return
        pendingName = next
        trackPresence()
    }

    fun send(signal: JSONObject) {
        sendRaw(
            JSONObject()
                .put("topic", topic)
                .put("event", "broadcast")
                .put("payload", JSONObject()
                    .put("type", "broadcast")
                    .put("event", "signal")
                    .put("payload", signal))
                .put("ref", nextRef())
                .put("join_ref", joinRef),
        )
    }

    fun close() {
        closedByUs = true
        mainHandler.removeCallbacks(heartbeat)
        socket?.close(1000, "leave")
        socket = null
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun sendJoin(peerId: String, displayName: String) {
        sendRaw(
            JSONObject()
                .put("topic", topic)
                .put("event", "phx_join")
                .put("payload", JSONObject()
                    .put("access_token", anonKey)
                    .put("config", JSONObject()
                        .put("broadcast", JSONObject().put("ack", false).put("self", false))
                        .put("presence", JSONObject().put("enabled", true).put("key", peerId))
                        .put("private", false)))
                .put("ref", joinMessageRef)
                .put("join_ref", joinRef),
        )
        pendingName = displayName
    }

    private var pendingName: String = ""

    private fun trackPresence() {
        sendRaw(
            JSONObject()
                .put("topic", topic)
                .put("event", "presence")
                .put("payload", JSONObject()
                    .put("type", "presence")
                    .put("event", "track")
                    .put("payload", JSONObject().put("name", pendingName)))
                .put("ref", nextRef())
                .put("join_ref", joinRef),
        )
    }

    private fun handleMessage(text: String, displayName: String) {
        val message = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (message.optString("topic") != topic && message.optString("event") != "phx_reply") {
            if (message.optString("topic").isNotEmpty() && message.optString("topic") != topic) return
        }
        when (message.optString("event")) {
            "phx_reply" -> {
                if (message.optString("ref") != joinMessageRef) return
                val payload = message.optJSONObject("payload") ?: JSONObject()
                if (payload.optString("status") == "ok") {
                    trackPresence()
                    mainHandler.post { listener.onJoined() }
                } else {
                    val response = payload.optJSONObject("response")
                    val reason = response?.optString("reason").orEmpty().ifBlank {
                        payload.optString("response").ifBlank { "The channel rejected this room." }
                    }
                    notifyClosed(reason)
                }
            }
            "presence_state" -> {
                members.clear()
                readPresence(message.optJSONObject("payload"), members)
                if (pendingName.isNotBlank() && members.none { it.value == displayName }) {
                    // Self may not be in the first snapshot. The session adds itself separately.
                }
                publishPresence()
            }
            "presence_diff" -> {
                val payload = message.optJSONObject("payload") ?: return
                readPresence(payload.optJSONObject("joins"), members)
                payload.optJSONObject("leaves")?.keys()?.forEach { key -> members.remove(key) }
                publishPresence()
            }
            "broadcast" -> {
                val payload = message.optJSONObject("payload") ?: return
                if (payload.optString("event") != "signal") return
                val signal = payload.optJSONObject("payload") ?: return
                mainHandler.post { listener.onSignal(signal) }
            }
            "phx_error", "phx_close" -> {
                if (!closedByUs) notifyClosed("The channel disconnected.")
            }
        }
    }

    private fun publishPresence() {
        val snapshot = members.toMap()
        mainHandler.post { listener.onPresence(snapshot) }
    }

    private fun readPresence(source: JSONObject?, into: MutableMap<String, String>) {
        if (source == null) return
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val entry = source.optJSONObject(key) ?: continue
            val metas = entry.optJSONArray("metas")
            val meta = metas?.optJSONObject(0)
            val name = meta?.optString("name").orEmpty().ifBlank { "Phone" }
            into[key] = name
        }
    }

    private fun notifyClosed(reason: String) {
        mainHandler.post {
            if (!closedByUs) listener.onClosed(reason)
        }
    }

    private fun sendRaw(message: JSONObject) {
        socket?.send(message.toString())
    }

    private fun nextRef(): String = refCounter.getAndIncrement().toString()

    private companion object {
        const val TAG = "AirvoxIntercom"
        const val HEARTBEAT_MS = 25_000L
    }
}
