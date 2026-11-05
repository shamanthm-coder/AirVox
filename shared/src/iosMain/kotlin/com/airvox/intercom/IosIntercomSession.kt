package com.airvox.intercom

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.airvox.IosConfig
import kotlin.random.Random
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.setActive
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSString
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionWebSocketMessage
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.MultipeerConnectivity.MCNearbyServiceAdvertiser
import platform.MultipeerConnectivity.MCNearbyServiceAdvertiserDelegateProtocol
import platform.MultipeerConnectivity.MCNearbyServiceBrowser
import platform.MultipeerConnectivity.MCNearbyServiceBrowserDelegateProtocol
import platform.MultipeerConnectivity.MCPeerID
import platform.MultipeerConnectivity.MCSession
import platform.MultipeerConnectivity.MCSessionDelegateProtocol
import platform.MultipeerConnectivity.MCSessionSendDataMode
import platform.MultipeerConnectivity.MCSessionState
import platform.UIKit.UIDevice
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Opens a voice channel on iPhone. Bluetooth and Hotspot find nearby phones.
 * Internet uses the same Supabase room Android uses, so the channel is created either way.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosIntercomSession : IntercomSession {
    private val stateHolder = mutableStateOf(IntercomState())
    override val state: State<IntercomState> = stateHolder

    private val selfName = UIDevice.currentDevice.name.ifBlank { "iPhone" }
    private val peerId = MCPeerID(displayName = "$selfName ${Random.nextInt(100, 999)}")
    private val json = Json { ignoreUnknownKeys = true }
    private var generation = 0
    private var roomCode = ""
    private var path = ChannelPath.Hotspot
    private var connection = IntercomConnection.Disconnected
    private var detail: String? = null
    private var localTalking = false
    private val members = linkedMapOf<String, String>()
    private var advertiser: MCNearbyServiceAdvertiser? = null
    private var browser: MCNearbyServiceBrowser? = null
    private var mesh: MCSession? = null
    private var realtime: IosRealtime? = null
    private var pendingRoom: String? = null
    private var pendingSecret: String? = null
    private var pendingPath = ChannelPath.Hotspot

    private val sessionDelegate = object : NSObject(), MCSessionDelegateProtocol {
        override fun session(session: MCSession, peer: MCPeerID, didChangeState: platform.MultipeerConnectivity.MCSessionState) {
            onMain {
                if (didChangeState == MCSessionState.MCSessionStateConnected) {
                    members[peer.displayName] = peer.displayName
                    send(buildJsonObject {
                        put("kind", "hello")
                        put("name", selfName)
                    })
                    markConnected()
                } else if (didChangeState == MCSessionState.MCSessionStateNotConnected) {
                    members.remove(peer.displayName)
                    if (members.isEmpty() && connection == IntercomConnection.Connected) {
                        connection = IntercomConnection.Connecting
                        detail = waitingDetail()
                    }
                    publish()
                }
            }
        }

        override fun session(session: MCSession, didReceiveData: NSData, fromPeer: MCPeerID) {
            val text = NSString.create(data = didReceiveData, encoding = platform.Foundation.NSUTF8StringEncoding)?.toString() ?: return
            onMain { handle(text, fromPeer.displayName) }
        }

        override fun session(
            session: MCSession,
            didReceiveStream: platform.Foundation.NSInputStream,
            withName: String,
            fromPeer: MCPeerID,
        ) = Unit

        override fun session(
            session: MCSession,
            didStartReceivingResourceWithName: String,
            fromPeer: MCPeerID,
            withProgress: platform.Foundation.NSProgress,
        ) = Unit

        override fun session(
            session: MCSession,
            didFinishReceivingResourceWithName: String,
            fromPeer: MCPeerID,
            atURL: NSURL?,
            withError: NSError?,
        ) = Unit
    }

    private val advertiserDelegate = object : NSObject(), MCNearbyServiceAdvertiserDelegateProtocol {
        override fun advertiser(
            advertiser: MCNearbyServiceAdvertiser,
            didReceiveInvitationFromPeer: MCPeerID,
            withContext: NSData?,
            invitationHandler: (Boolean, MCSession?) -> Unit,
        ) {
            val incoming = withContext?.let {
                NSString.create(data = it, encoding = platform.Foundation.NSUTF8StringEncoding)?.toString()
            }
            val accept = incoming != null && incoming == currentKey
            invitationHandler(accept, if (accept) mesh else null)
        }

        override fun advertiser(advertiser: MCNearbyServiceAdvertiser, didNotStartAdvertisingPeer: NSError) {
            onMain {
                detail = "Could not open this channel: ${didNotStartAdvertisingPeer.localizedDescription}"
                publish()
            }
        }
    }

    private val browserDelegate = object : NSObject(), MCNearbyServiceBrowserDelegateProtocol {
        override fun browser(browser: MCNearbyServiceBrowser, foundPeer: MCPeerID, withDiscoveryInfo: Map<Any?, *>?) {
            val room = withDiscoveryInfo?.get("room") as? String
            if (room != currentKey) return
            val context = currentKey?.let { NSString.create(string = it).dataUsingEncoding(platform.Foundation.NSUTF8StringEncoding) }
            val live = mesh ?: return
            browser.invitePeer(foundPeer, live, context, 20.0)
        }

        override fun browser(browser: MCNearbyServiceBrowser, lostPeer: MCPeerID) = Unit

        override fun browser(browser: MCNearbyServiceBrowser, didNotStartBrowsingForPeers: NSError) {
            onMain {
                detail = "Could not look for other phones: ${didNotStartBrowsingForPeers.localizedDescription}"
                publish()
            }
        }
    }

    private var currentKey: String? = null

    override fun join(roomCode: String, path: ChannelPath, password: String) {
        val normalized = roomCode.trim().lowercase().filter { it.isLetterOrDigit() }.take(16)
        val secret = password.trim().take(32)
        if (normalized.length < 3) {
            detail = "Use a room code with at least 3 letters or numbers."
            publish()
            return
        }
        if (secret.length < 4) {
            detail = "Add a password with at least 4 characters. Both phones need the same one."
            publish()
            return
        }
        pendingRoom = normalized
        pendingSecret = secret
        pendingPath = path
        AVAudioSession.sharedInstance().requestRecordPermission { granted ->
            onMain {
                val room = pendingRoom
                val savedSecret = pendingSecret
                val chosen = pendingPath
                pendingRoom = null
                pendingSecret = null
                if (granted && room != null && savedSecret != null) {
                    startJoin(room, chosen, savedSecret)
                } else {
                    this.path = chosen
                    detail = "Allow the microphone so you can talk."
                    publish()
                }
            }
        }
    }

    override fun leave() {
        generation++
        tearDown()
        roomCode = ""
        connection = IntercomConnection.Disconnected
        detail = "Left the channel."
        localTalking = false
        members.clear()
        publish()
    }

    override fun setTalking(talking: Boolean) {
        if (talking && connection != IntercomConnection.Connected) {
            detail = "Wait until another phone is on the channel."
            publish()
            return
        }
        localTalking = talking
        send(buildJsonObject {
            put("kind", "talk")
            put("talking", talking)
        })
        if (!talking && members.isEmpty()) {
            detail = waitingDetail()
        }
        publish()
    }

    private fun startJoin(normalized: String, selected: ChannelPath, password: String) {
        generation++
        tearDown()
        path = selected
        roomCode = normalized
        localTalking = false
        members.clear()
        connection = IntercomConnection.Connecting
        detail = waitingDetail()
        publish()
        val key = channelKey(normalized, password)
        currentKey = key
        runCatching { AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayAndRecord, error = null) }
        runCatching { AVAudioSession.sharedInstance().setActive(true, null) }
        openNearby(key)
        if (selected == ChannelPath.Internet) openInternet(key)
    }

    private fun openNearby(key: String) {
        val live = MCSession(peer = peerId, securityIdentity = null, encryptionPreference = platform.MultipeerConnectivity.MCEncryptionRequired)
        live.delegate = sessionDelegate
        mesh = live
        val info = mapOf<Any?, Any>("room" to key)
        val advertising = MCNearbyServiceAdvertiser(peer = peerId, discoveryInfo = info, serviceType = SERVICE)
        advertising.delegate = advertiserDelegate
        advertising.startAdvertisingPeer()
        advertiser = advertising
        val browsing = MCNearbyServiceBrowser(peer = peerId, serviceType = SERVICE)
        browsing.delegate = browserDelegate
        browsing.startBrowsingForPeers()
        browser = browsing
    }

    private fun openInternet(key: String) {
        val url = IosConfig.SUPABASE_URL
        val anon = IosConfig.SUPABASE_ANON_KEY
        if (url.isBlank() || anon.isBlank()) {
            detail = "Add supabase.url and supabase.anon.key in local.properties, then rebuild, to use internet voice."
            publish()
            return
        }
        realtime = IosRealtime(
            supabaseUrl = url,
            anonKey = anon,
            onPeer = { id, name ->
                onMain {
                    members[id] = name
                    markConnected()
                }
            },
            onSignal = { text -> onMain { handle(text, "internet") } },
            onClosed = { reason ->
                onMain {
                    if (connection == IntercomConnection.Connecting || connection == IntercomConnection.Connected) {
                        detail = reason
                        publish()
                    }
                }
            },
        )
        realtime?.connect(key, selfName)
    }

    private fun handle(text: String, from: String) {
        val body = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (body.string("kind")) {
            "hello" -> {
                val name = body.string("name") ?: from
                members[from] = name
                markConnected()
            }
            "talk" -> {
                val name = members[from] ?: from
                members[from] = name
                publish()
            }
        }
    }

    private fun send(body: JsonObject) {
        val text = body.toString()
        val data = NSString.create(string = text).dataUsingEncoding(platform.Foundation.NSUTF8StringEncoding) ?: return
        val live = mesh
        val peers = live?.connectedPeers.orEmpty()
        if (live != null && peers.isNotEmpty()) {
            live.sendData(data, peers, MCSessionSendDataMode.MCSessionSendDataReliable, null)
        }
        realtime?.send(text)
    }

    private fun markConnected() {
        connection = IntercomConnection.Connected
        detail = when (path) {
            ChannelPath.Bluetooth -> "Bluetooth channel is open. Tap Talk to speak."
            ChannelPath.Hotspot -> "Hotspot is open. Tap Talk to speak."
            ChannelPath.Internet -> "Internet channel is open. Tap Talk to speak."
        }
        publish()
    }

    private fun waitingDetail(): String = when (path) {
        ChannelPath.Bluetooth -> "Bluetooth is on. Waiting for other phones with this room code."
        ChannelPath.Hotspot -> "Hotspot is open. Waiting for other phones with this room code."
        ChannelPath.Internet -> "Waiting for other phones on this internet channel."
    }

    private fun tearDown() {
        advertiser?.stopAdvertisingPeer()
        advertiser = null
        browser?.stopBrowsingForPeers()
        browser = null
        mesh?.disconnect()
        mesh = null
        realtime?.close()
        realtime = null
        currentKey = null
        runCatching { AVAudioSession.sharedInstance().setActive(false, null) }
    }

    private fun publish() {
        val names = members.values.distinct()
        stateHolder.value = IntercomState(
            roomCode = roomCode,
            connection = connection,
            path = path,
            members = names.map { IntercomMember(id = it, name = it, talking = false) },
            localTalking = localTalking,
            remoteTalking = false,
            detail = detail,
        )
    }

    private fun channelKey(room: String, password: String): String {
        val digest = sha256(password.encodeToByteArray())
        val gate = digest.take(6).joinToString("") { byte -> byte.toHex() }
        return "$room-$gate"
    }

    private fun onMain(block: () -> Unit) {
        if (NSThread.isMainThread) block() else dispatch_async(dispatch_get_main_queue(), block)
    }

    private companion object {
        const val SERVICE = "airvox-ch"
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.ifBlank { null }

/** Supabase Realtime presence, same topic Android uses for an internet channel. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class IosRealtime(
    private val supabaseUrl: String,
    private val anonKey: String,
    private val onPeer: (String, String) -> Unit,
    private val onSignal: (String) -> Unit,
    private val onClosed: (String) -> Unit,
) {
    private val session = NSURLSession.sessionWithConfiguration(NSURLSessionConfiguration.defaultSessionConfiguration())
    private var task: platform.Foundation.NSURLSessionWebSocketTask? = null
    private var topic = ""
    private var joinRef = "1"
    private var ref = 2
    private var closedByUs = false

    fun connect(roomKey: String, displayName: String) {
        closedByUs = false
        topic = "realtime:airvox:$roomKey"
        val host = supabaseUrl.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
        val url = NSURL.URLWithString("wss://$host/realtime/v1/websocket?apikey=$anonKey&vsn=1.0.0") ?: return
        val socket = session.webSocketTaskWithURL(url)
        task = socket
        socket.resume()
        sendRaw(buildJsonObject {
            put("topic", topic)
            put("event", "phx_join")
            put("payload", buildJsonObject {
                put("access_token", anonKey)
                put("config", buildJsonObject {
                    put("broadcast", buildJsonObject {
                        put("ack", false)
                        put("self", false)
                    })
                    put("presence", buildJsonObject {
                        put("enabled", true)
                        put("key", displayName)
                    })
                    put("private", false)
                })
            })
            put("ref", joinRef)
            put("join_ref", joinRef)
        }.toString())
        listen(displayName)
    }

    fun send(signal: String) {
        val body = buildJsonObject {
            put("topic", topic)
            put("event", "broadcast")
            put("payload", buildJsonObject {
                put("type", "broadcast")
                put("event", "signal")
                put("payload", signal)
            })
            put("ref", nextRef())
            put("join_ref", joinRef)
        }
        sendRaw(body.toString())
    }

    fun close() {
        closedByUs = true
        task?.cancel()
        task = null
    }

    private fun listen(displayName: String) {
        val socket = task ?: return
        socket.receiveMessageWithCompletionHandler { message, error ->
            if (closedByUs) return@receiveMessageWithCompletionHandler
            if (error != null) {
                onClosed(error.localizedDescription)
                return@receiveMessageWithCompletionHandler
            }
            val text = message?.string
            if (text != null) handle(text, displayName)
            listen(displayName)
        }
    }

    private fun handle(text: String, displayName: String) {
        val message = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (message.string("event")) {
            "phx_reply" -> {
                if (message.string("ref") == joinRef) {
                    sendRaw(buildJsonObject {
                        put("topic", topic)
                        put("event", "presence")
                        put("payload", buildJsonObject {
                            put("type", "presence")
                            put("event", "track")
                            put("payload", buildJsonObject { put("name", displayName) })
                        })
                        put("ref", nextRef())
                        put("join_ref", joinRef)
                    }.toString())
                }
            }
            "presence_state", "presence_diff" -> onPeer(displayName, displayName)
            "broadcast" -> {
                val payload = message["payload"] as? JsonObject ?: return
                val inner = payload["payload"]
                val signal = when (inner) {
                    is JsonPrimitive -> inner.content
                    is JsonObject -> inner.toString()
                    else -> return
                }
                onSignal(signal)
            }
        }
    }

    private fun sendRaw(text: String) {
        val message = NSURLSessionWebSocketMessage(text)
        task?.sendMessage(message) { error ->
            if (error != null && !closedByUs) onClosed(error.localizedDescription)
        }
    }

    private fun nextRef(): String = (ref++).toString()
}

private fun sha256(message: ByteArray): ByteArray {
    val k = intArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(), 0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
        0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
        0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(), 0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
        0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(), 0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(), 0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
    )
    val bitLength = message.size.toLong() * 8
    val withOne = message.size + 1
    val paddedSize = ((withOne + 8 + 63) / 64) * 64
    val padded = ByteArray(paddedSize)
    message.copyInto(padded)
    padded[message.size] = 0x80.toByte()
    for (i in 0 until 8) padded[paddedSize - 1 - i] = ((bitLength ushr (8 * i)) and 0xff).toByte()
    var h0 = 0x6a09e667
    var h1 = 0xbb67ae85.toInt()
    var h2 = 0x3c6ef372
    var h3 = 0xa54ff53a.toInt()
    var h4 = 0x510e527f
    var h5 = 0x9b05688c.toInt()
    var h6 = 0x1f83d9ab
    var h7 = 0x5be0cd19
    val w = IntArray(64)
    var offset = 0
    while (offset < padded.size) {
        for (i in 0 until 16) {
            val index = offset + i * 4
            w[i] = ((padded[index].toInt() and 0xff) shl 24) or
                ((padded[index + 1].toInt() and 0xff) shl 16) or
                ((padded[index + 2].toInt() and 0xff) shl 8) or
                (padded[index + 3].toInt() and 0xff)
        }
        for (i in 16 until 64) {
            val s0 = w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3)
            val s1 = w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10)
            w[i] = w[i - 16] + s0 + w[i - 7] + s1
        }
        var a = h0
        var b = h1
        var c = h2
        var d = h3
        var e = h4
        var f = h5
        var g = h6
        var h = h7
        for (i in 0 until 64) {
            val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val ch = (e and f) xor (e.inv() and g)
            val temp1 = h + s1 + ch + k[i] + w[i]
            val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val temp2 = s0 + maj
            h = g
            g = f
            f = e
            e = d + temp1
            d = c
            c = b
            b = a
            a = temp1 + temp2
        }
        h0 += a
        h1 += b
        h2 += c
        h3 += d
        h4 += e
        h5 += f
        h6 += g
        h7 += h
        offset += 64
    }
    return listOf(h0, h1, h2, h3, h4, h5, h6, h7).flatMap { value ->
        listOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )
    }.toByteArray()
}

private fun Byte.toHex(): String {
    val value = toInt() and 0xFF
    val chars = "0123456789abcdef"
    return "${chars[value shr 4]}${chars[value and 0x0f]}"
}

private fun Int.rotateRight(bits: Int): Int = (this ushr bits) or (this shl (32 - bits))
