package com.airvox.intercom

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.airvox.IosConfig
import kotlin.random.Random
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.seconds
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import platform.AVFAudio.AVAudioSession
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSString
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionWebSocketMessage
import platform.Foundation.NSUUID
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
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSObject
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time

/**
 * Opens a voice channel on iPhone.
 * Internet uses the same Supabase room and WebRTC call as Android, so the phones can hear each other.
 * Bluetooth and Hotspot still find nearby iPhones, then use that same WebRTC call for the audio.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosIntercomSession(
    private val voice: IosVoiceCall,
) : IntercomSession {
    private val stateHolder = mutableStateOf(IntercomState())
    override val state: State<IntercomState> = stateHolder

    private var selfName = UIDevice.currentDevice.name.ifBlank { "iPhone" }
    private val selfId = NSUUID().UUIDString.lowercase()
    private val peerId = MCPeerID(displayName = "$selfName ${Random.nextInt(100, 999)}")
    private val json = Json { ignoreUnknownKeys = true }
    private var generation = 0
    private var roomCode = ""
    private var path = ChannelPath.Hotspot
    private var connection = IntercomConnection.Disconnected
    private var detail: String? = null
    private var localTalking = false
    private var channelReady = false
    private val presence = linkedMapOf<String, String>()
    private val meshRoster = linkedMapOf<String, String>()
    private val meshNames = linkedMapOf<String, String>()
    private val peers = linkedMapOf<String, VoicePeer>()
    private val earlyIce = linkedMapOf<String, MutableList<JsonObject>>()
    private val earlyTalk = linkedMapOf<String, Boolean>()
    private val peerPlaces = linkedMapOf<String, ChannelPlace>()
    private var lastLat: Double? = null
    private var lastLng: Double? = null
    private var lastPlaceSent = TimeSource.Monotonic.markNow() - 10.seconds
    private var advertiser: MCNearbyServiceAdvertiser? = null
    private var browser: MCNearbyServiceBrowser? = null
    private var mesh: MCSession? = null
    private var realtime: IosRealtime? = null
    private var lan: IosLanChannel? = null
    private var ble: IosBleChannel? = null
    private val bleTalkers = linkedSetOf<String>()
    private val bleTalkTicket = HashMap<String, Int>()
    private val bleVoice = IosBleVoice(onFrame = { frame -> ble?.sendAudio(frame) })
    private var pendingRoom: String? = null
    private var pendingSecret: String? = null
    private var pendingPath = ChannelPath.Hotspot
    private var currentKey: String? = null
    private var talkPulse = 0

    init {
        voice.attach(VoiceListener())
    }

    private val sessionDelegate = object : NSObject(), MCSessionDelegateProtocol {
        override fun session(session: MCSession, peer: MCPeerID, didChangeState: MCSessionState) {
            onMain {
                if (path == ChannelPath.Internet) return@onMain
                if (didChangeState == MCSessionState.MCSessionStateConnected) {
                    sendSignal(buildJsonObject {
                        put("kind", "hello")
                        put("name", selfName)
                    })
                } else if (didChangeState == MCSessionState.MCSessionStateNotConnected) {
                    val id = meshNames.remove(peer.displayName)
                    if (id != null) {
                        meshRoster.remove(id)
                        closePeer(id)
                    }
                    refreshConnection()
                }
            }
        }

        override fun session(session: MCSession, didReceiveData: NSData, fromPeer: MCPeerID) {
            val text = NSString.create(data = didReceiveData, encoding = platform.Foundation.NSUTF8StringEncoding)?.toString() ?: return
            onMain { handleSignal(text, mcName = fromPeer.displayName) }
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

    override fun setRiderName(name: String) {
        val trimmed = name.trim().take(40)
        if (trimmed.isBlank() || trimmed == selfName) return
        selfName = trimmed
        lan?.rename(trimmed)
        ble?.rename(trimmed)
        realtime?.rename(trimmed)
        if (path != ChannelPath.Internet && mesh != null) {
            sendSignal(buildJsonObject {
                put("kind", "hello")
                put("name", selfName)
            })
        }
        lastPlaceSent = TimeSource.Monotonic.markNow() - 10.seconds
        broadcastPlace(force = true)
        publish()
    }

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
        presence.clear()
        meshRoster.clear()
        publish()
    }

    override fun setTalking(talking: Boolean) {
        if (talking && connection != IntercomConnection.Connected) {
            detail = "Wait until another phone is on the channel."
            publish()
            return
        }
        localTalking = talking
        if (path == ChannelPath.Bluetooth) {
            bleVoice.mutePlayback(talking)
            if (talking) bleVoice.startCapture() else bleVoice.stopCapture()
            sendSignal(buildJsonObject {
                put("kind", "talk")
                put("talking", talking)
            })
            if (talking) startTalkPulse() else stopTalkPulse()
            publish()
            return
        }
        voice.setMicEnabled(talking)
        sendSignal(buildJsonObject {
            put("kind", "talk")
            put("talking", talking)
        })
        if (talking) startTalkPulse() else stopTalkPulse()
        publish()
    }

    private fun startJoin(normalized: String, selected: ChannelPath, password: String) {
        generation++
        val gen = generation
        tearDown()
        path = selected
        roomCode = normalized
        localTalking = false
        channelReady = false
        presence.clear()
        meshRoster.clear()
        meshNames.clear()
        connection = IntercomConnection.Connecting
        detail = joiningDetail(normalized)
        publish()
        if (selected == ChannelPath.Internet && !internetConfigured()) {
            connection = IntercomConnection.Disconnected
            detail = "Add supabase.url and supabase.anon.key in local.properties, then rebuild, to use internet voice."
            publish()
            return
        }
        val key = channelKey(normalized, password)
        currentKey = key
        if (selected == ChannelPath.Internet) {
            voice.prepare(true)
            openInternet(key, gen)
        } else if (selected == ChannelPath.Hotspot) {
            voice.prepare(false)
            channelReady = true
            openLan(key, gen)
            detail = waitingDetail()
            publish()
        } else {
            channelReady = true
            openBle(key, gen)
            detail = waitingDetail()
            publish()
        }
    }

    private fun openLan(key: String, gen: Int) {
        val channel = IosLanChannel(
            onSignal = { text -> onMain { if (gen == generation) handleSignal(text) } },
            onLost = { id ->
                onMain {
                    if (gen != generation) return@onMain
                    meshRoster.remove(id)
                    closePeer(id)
                    refreshConnection()
                }
            },
            onFailed = { reason ->
                onMain {
                    if (gen != generation) return@onMain
                    detail = reason
                    publish()
                }
            },
        )
        lan = channel
        channel.start(key, selfId, selfName)
    }

    private fun openBle(key: String, gen: Int) {
        val channel = IosBleChannel(
            onJoined = {
                if (gen != generation) return@IosBleChannel
                channelReady = true
                detail = waitingDetail()
                publish()
            },
            onPresence = { members ->
                if (gen != generation) return@IosBleChannel
                meshRoster.clear()
                members.forEach { (id, name) -> if (id != selfId) meshRoster[id] = name }
                refreshConnection()
            },
            onSignal = { text -> if (gen == generation) handleSignal(text) },
            onAudio = { id, audio ->
                if (gen != generation) return@IosBleChannel
                bleVoice.enqueue(audio)
                noteBleTalk(id, true)
            },
            onClosed = { reason ->
                if (gen != generation) return@IosBleChannel
                detail = reason
                publish()
            },
        )
        ble = channel
        channel.start(key, selfId, selfName)
    }

    private fun noteBleTalk(id: String, talking: Boolean) {
        val ticket = (bleTalkTicket[id] ?: 0) + 1
        bleTalkTicket[id] = ticket
        if (talking) {
            bleTalkers.add(id)
            dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 900_000_000), dispatch_get_main_queue()) {
                if (bleTalkTicket[id] == ticket) {
                    bleTalkers.remove(id)
                    publish()
                }
            }
        } else {
            bleTalkers.remove(id)
        }
        publish()
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

    private fun openInternet(key: String, gen: Int) {
        val url = IosConfig.SUPABASE_URL
        val anon = IosConfig.SUPABASE_ANON_KEY
        realtime = IosRealtime(
            supabaseUrl = url,
            anonKey = anon,
            onJoined = {
                onMain {
                    if (gen != generation) return@onMain
                    channelReady = true
                    detail = waitingDetail()
                    publish()
                    syncPeers()
                }
            },
            onPresence = { members ->
                onMain {
                    if (gen != generation) return@onMain
                    presence.clear()
                    presence.putAll(members)
                    publish()
                    if (channelReady) syncPeers()
                }
            },
            onSignal = { text -> onMain { if (gen == generation) handleSignal(text) } },
            onClosed = { reason ->
                onMain {
                    if (gen != generation) return@onMain
                    tearDown()
                    connection = IntercomConnection.Disconnected
                    detail = reason
                    publish()
                }
            },
        )
        realtime?.connect(key, selfId, selfName)
    }

    private fun handleSignal(text: String, mcName: String? = null) {
        val message = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val from = message.string("from") ?: return
        if (from == selfId) return
        val to = message.string("to").orEmpty()
        if (to.isNotEmpty() && to != selfId) return
        if (mcName != null) meshNames[mcName] = from
        when (message.string("kind")) {
            "hello" -> {
                if (path == ChannelPath.Internet) return
                meshRoster[from] = message.string("name") ?: "Phone"
                if (path == ChannelPath.Bluetooth) refreshConnection() else syncPeers()
            }
            "offer" -> {
                if (path != ChannelPath.Internet && meshRoster[from] == null) {
                    meshRoster[from] = "iPhone"
                }
                val link = peers[from] ?: openPeer(from)
                if (selfId > from && link.offerSent) return
                val sdp = message.string("sdp").orEmpty()
                if (sdp.isBlank()) return
                voice.acceptOffer(from, sdp)
            }
            "answer" -> {
                if (peers[from] == null) return
                val sdp = message.string("sdp").orEmpty()
                if (sdp.isBlank()) return
                voice.acceptAnswer(from, sdp)
            }
            "ice" -> {
                if (peers[from] == null) {
                    earlyIce.getOrPut(from) { mutableListOf() }.let { bucket ->
                        if (bucket.size >= 40) bucket.removeAt(0)
                        bucket.add(message)
                    }
                    return
                }
                voice.addIce(
                    peerId = from,
                    sdpMid = message.string("sdpMid").orEmpty(),
                    sdpMLineIndex = message.int("sdpMLineIndex"),
                    candidate = message.string("candidate").orEmpty(),
                )
            }
            "talk" -> {
                val talking = message.bool("talking")
                if (path == ChannelPath.Bluetooth) {
                    noteBleTalk(from, talking)
                    return
                }
                val link = peers[from]
                if (link == null) {
                    earlyTalk[from] = talking
                    return
                }
                link.talking = talking
                voice.setRemoteTalking(from, talking)
                publish()
            }
            "bye" -> {
                closePeer(from)
                earlyIce.remove(from)
                earlyTalk.remove(from)
                peerPlaces.remove(from)
                if (path == ChannelPath.Internet) presence.remove(from) else meshRoster.remove(from)
                refreshConnection()
            }
            "place" -> rememberPlace(from, message)
        }
    }

    fun sharePlace(latitude: Double, longitude: Double) {
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return
        lastLat = latitude
        lastLng = longitude
        publish()
        broadcastPlace(force = false)
    }

    private fun rememberPlace(from: String, message: JsonObject) {
        val lat = message.dbl("lat") ?: return
        val lng = message.dbl("lng") ?: return
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return
        peerPlaces[from] = ChannelPlace(
            id = from,
            name = message.string("name") ?: roster()[from] ?: "Phone",
            latitude = lat,
            longitude = lng,
        )
        publish()
    }

    private fun broadcastPlace(force: Boolean) {
        val lat = lastLat ?: return
        val lng = lastLng ?: return
        if (connection != IntercomConnection.Connecting && connection != IntercomConnection.Connected) return
        val wait = if (force) 1.seconds else 4.seconds
        if (lastPlaceSent.elapsedNow() < wait) return
        lastPlaceSent = TimeSource.Monotonic.markNow()
        sendSignal(buildJsonObject {
            put("kind", "place")
            put("name", selfName)
            put("lat", lat)
            put("lng", lng)
        })
    }

    private fun syncPeers() {
        val others = roster().filterKeys { it != selfId }
        (peers.keys - others.keys).forEach { closePeer(it) }
        others.keys.forEach { other ->
            val link = peers[other] ?: openPeer(other)
            if (selfId > other && !link.offerSent) {
                link.offerSent = true
                voice.createOffer(other)
            }
        }
        if (others.isNotEmpty()) {
            peerPlaces.keys.retainAll(others.keys)
            broadcastPlace(force = true)
        }
        refreshConnection()
    }

    private fun refreshConnection() {
        if (path == ChannelPath.Bluetooth) {
            if (roster().any { it.key != selfId }) {
                markConnected()
            } else if (connection != IntercomConnection.Disconnected) {
                connection = IntercomConnection.Connecting
                detail = if (channelReady) waitingDetail() else detail
                publish()
            }
            return
        }
        if (peers.values.any { it.iceConnected }) {
            markConnected()
            return
        }
        if (connection == IntercomConnection.Disconnected) {
            publish()
            return
        }
        connection = IntercomConnection.Connecting
        detail = if (roster().any { it.key != selfId }) {
            "Connecting voice to the other phone…"
        } else if (channelReady) {
            waitingDetail()
        } else {
            detail
        }
        publish()
    }

    private fun markConnected() {
        connection = IntercomConnection.Connected
        detail = when (path) {
            ChannelPath.Bluetooth -> "Bluetooth channel is open. Tap Talk to speak."
            ChannelPath.Hotspot -> "Hotspot is open. Tap Talk to speak."
            ChannelPath.Internet -> "Internet channel is open. Tap Talk to speak."
        }
        publish()
        if (localTalking) {
            sendSignal(buildJsonObject {
                put("kind", "talk")
                put("talking", true)
            })
        }
    }

    private fun openPeer(id: String): VoicePeer {
        val created = VoicePeer()
        peers[id] = created
        voice.openPeer(id)
        flushEarly(id)
        return created
    }

    private fun closePeer(id: String) {
        if (peers.remove(id) == null) return
        voice.closePeer(id)
    }

    private fun flushEarly(id: String) {
        earlyTalk.remove(id)?.let { talking ->
            peers[id]?.talking = talking
            voice.setRemoteTalking(id, talking)
        }
        val waiting = earlyIce.remove(id).orEmpty()
        waiting.forEach { message ->
            voice.addIce(
                peerId = id,
                sdpMid = message.string("sdpMid").orEmpty(),
                sdpMLineIndex = message.int("sdpMLineIndex"),
                candidate = message.string("candidate").orEmpty(),
            )
        }
    }

    private fun startTalkPulse() {
        val ticket = ++talkPulse
        scheduleTalkPulse(ticket)
    }

    private fun stopTalkPulse() {
        talkPulse += 1
    }

    private fun scheduleTalkPulse(ticket: Int) {
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 2_000_000_000), dispatch_get_main_queue()) {
            if (ticket != talkPulse || !localTalking) return@dispatch_after
            sendSignal(buildJsonObject {
                put("kind", "talk")
                put("talking", true)
            })
            scheduleTalkPulse(ticket)
        }
    }

    private fun sendSignal(fields: JsonObject, to: String? = null) {
        val signal = buildJsonObject {
            for ((key, value) in fields) put(key, value)
            put("from", selfId)
            put("to", to ?: "")
        }
        if (path == ChannelPath.Internet) {
            realtime?.send(signal)
        } else if (path == ChannelPath.Hotspot) {
            lan?.send(signal.toString())
        } else if (path == ChannelPath.Bluetooth) {
            ble?.send(signal.toString())
        } else {
            sendMesh(signal.toString())
        }
    }

    private fun sendMesh(text: String) {
        val data = NSString.create(string = text).dataUsingEncoding(platform.Foundation.NSUTF8StringEncoding) ?: return
        val live = mesh ?: return
        val others = live.connectedPeers
        if (others.isEmpty()) return
        live.sendData(data, others, MCSessionSendDataMode.MCSessionSendDataReliable, null)
    }

    private fun roster(): Map<String, String> = if (path == ChannelPath.Internet) presence else meshRoster

    private fun internetConfigured(): Boolean {
        return IosConfig.SUPABASE_URL.isNotBlank() && IosConfig.SUPABASE_ANON_KEY.isNotBlank()
    }

    private fun joiningDetail(room: String): String = when (path) {
        ChannelPath.Bluetooth -> "Looking over Bluetooth for $room…"
        ChannelPath.Hotspot -> "Looking for other phones on this hotspot…"
        ChannelPath.Internet -> "Joining $room over the internet…"
    }

    private fun waitingDetail(): String = when (path) {
        ChannelPath.Bluetooth -> "Bluetooth is on. Waiting for other phones with this room code."
        ChannelPath.Hotspot -> "Hotspot is open. Waiting for the other phone on this same Wi-Fi. Allow Local Network if iPhone asks."
        ChannelPath.Internet -> "Waiting for other phones on this internet channel."
    }

    private fun tearDown() {
        advertiser?.stopAdvertisingPeer()
        advertiser = null
        browser?.stopBrowsingForPeers()
        browser = null
        mesh?.disconnect()
        mesh = null
        lan?.close()
        lan = null
        ble?.close()
        ble = null
        bleVoice.stop()
        bleTalkers.clear()
        bleTalkTicket.clear()
        realtime?.close()
        realtime = null
        currentKey = null
        channelReady = false
        peers.clear()
        peerPlaces.clear()
        earlyIce.clear()
        earlyTalk.clear()
        stopTalkPulse()
        voice.stop()
    }

    private fun publish() {
        stateHolder.value = IntercomState(
            roomCode = roomCode,
            connection = connection,
            path = path,
            members = memberList(),
            localTalking = localTalking,
            remoteTalking = if (path == ChannelPath.Bluetooth) bleTalkers.isNotEmpty() else peers.values.any { it.talking },
            places = placeList(),
            detail = detail,
        )
    }

    private fun placeList(): List<ChannelPlace> {
        val self = lastLat?.let { lat ->
            lastLng?.let { lng ->
                ChannelPlace(id = selfId, name = selfName, latitude = lat, longitude = lng, self = true)
            }
        }
        if (connection == IntercomConnection.Disconnected) return listOfNotNull(self)
        val known = roster().keys
        val others = peerPlaces
            .filter { (id, _) -> id != selfId && (known.isEmpty() || id in known) }
            .values
            .toList()
        return listOfNotNull(self) + others
    }

    private fun memberList(): List<IntercomMember> {
        if (connection == IntercomConnection.Disconnected) return emptyList()
        val others = roster()
            .filterKeys { it != selfId }
            .map { (id, name) ->
                val talking = if (path == ChannelPath.Bluetooth) id in bleTalkers else peers[id]?.talking == true
                IntercomMember(id = id, name = name, talking = talking)
            }
        return listOf(IntercomMember(selfId, "$selfName (you)", localTalking)) + others
    }

    private fun channelKey(room: String, password: String): String {
        val digest = sha256(password.encodeToByteArray())
        val gate = digest.take(6).joinToString("") { byte -> byte.toHex() }
        return "$room-$gate"
    }

    private fun onMain(block: () -> Unit) {
        if (NSThread.isMainThread) block() else dispatch_async(dispatch_get_main_queue(), block)
    }

    private inner class VoiceListener : IosVoiceListener {
        override fun onLocalOffer(peerId: String, sdp: String) {
            val gen = generation
            onMain {
                if (gen != generation || peers[peerId] == null) return@onMain
                sendSignal(buildJsonObject {
                    put("kind", "offer")
                    put("sdp", sdp)
                }, to = peerId)
            }
        }

        override fun onLocalAnswer(peerId: String, sdp: String) {
            val gen = generation
            onMain {
                if (gen != generation || peers[peerId] == null) return@onMain
                sendSignal(buildJsonObject {
                    put("kind", "answer")
                    put("sdp", sdp)
                }, to = peerId)
            }
        }

        override fun onIceCandidate(peerId: String, sdpMid: String, sdpMLineIndex: Int, candidate: String) {
            val gen = generation
            onMain {
                if (gen != generation || peers[peerId] == null || candidate.isBlank()) return@onMain
                sendSignal(buildJsonObject {
                    put("kind", "ice")
                    put("sdpMid", sdpMid)
                    put("sdpMLineIndex", sdpMLineIndex)
                    put("candidate", candidate)
                }, to = peerId)
            }
        }

        override fun onConnected(peerId: String) {
            val gen = generation
            onMain {
                if (gen != generation) return@onMain
                val link = peers[peerId] ?: return@onMain
                link.iceConnected = true
                markConnected()
            }
        }

        override fun onDropped(peerId: String) {
            val gen = generation
            onMain {
                if (gen != generation) return@onMain
                closePeer(peerId)
                syncPeers()
                if (peers.values.none { it.iceConnected }) {
                    connection = IntercomConnection.Connecting
                    detail = "Voice to one phone dropped. Waiting to reconnect."
                    publish()
                }
            }
        }

        override fun onUnreachable(peerId: String) {
            val gen = generation
            onMain {
                if (gen != generation || peers[peerId]?.iceConnected == true) return@onMain
                if (path == ChannelPath.Internet) {
                    detail = "Could not reach that phone over the internet."
                    publish()
                }
            }
        }

        override fun onError(message: String) {
            val gen = generation
            onMain {
                if (gen != generation) return@onMain
                detail = message.ifBlank { "Could not open the voice channel." }
                publish()
            }
        }
    }

    private class VoicePeer {
        var offerSent: Boolean = false
        var iceConnected: Boolean = false
        var talking: Boolean = false
    }

    private companion object {
        const val SERVICE = "airvox-ch"
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.ifBlank { null }

private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: 0

private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

private fun JsonObject.dbl(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
    ?: (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/** Supabase Realtime room. Same topic and signal envelope Android uses. Voice stays on WebRTC. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class IosRealtime(
    private val supabaseUrl: String,
    private val anonKey: String,
    private val onJoined: () -> Unit,
    private val onPresence: (Map<String, String>) -> Unit,
    private val onSignal: (String) -> Unit,
    private val onClosed: (String) -> Unit,
) {
    private val session = NSURLSession.sessionWithConfiguration(NSURLSessionConfiguration.defaultSessionConfiguration())
    private var task: platform.Foundation.NSURLSessionWebSocketTask? = null
    private var topic = ""
    private var joinRef = "1"
    private var ref = 2
    private var closedByUs = false
    private var heartbeats = 0
    private var trackedName = ""
    private val members = linkedMapOf<String, String>()

    fun connect(roomKey: String, peerId: String, displayName: String) {
        closedByUs = false
        trackedName = displayName
        members.clear()
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
                        put("key", peerId)
                    })
                    put("private", false)
                })
            })
            put("ref", joinRef)
            put("join_ref", joinRef)
        }.toString())
        scheduleHeartbeat()
        listen()
    }

    fun rename(name: String) {
        val next = name.trim().take(40)
        if (next.isBlank() || next == trackedName || task == null) return
        trackedName = next
        sendPresence()
    }

    private fun sendPresence() {
        if (trackedName.isBlank()) return
        sendRaw(buildJsonObject {
            put("topic", topic)
            put("event", "presence")
            put("payload", buildJsonObject {
                put("type", "presence")
                put("event", "track")
                put("payload", buildJsonObject { put("name", trackedName) })
            })
            put("ref", nextRef())
            put("join_ref", joinRef)
        }.toString())
    }

    fun send(signal: JsonObject) {
        sendRaw(buildJsonObject {
            put("topic", topic)
            put("event", "broadcast")
            put("payload", buildJsonObject {
                put("type", "broadcast")
                put("event", "signal")
                put("payload", signal)
            })
            put("ref", nextRef())
            put("join_ref", joinRef)
        }.toString())
    }

    fun close() {
        closedByUs = true
        heartbeats += 1
        task?.cancel()
        task = null
    }

    private fun scheduleHeartbeat() {
        val ticket = heartbeats
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 25_000_000_000), dispatch_get_main_queue()) {
            if (closedByUs || ticket != heartbeats || task == null) return@dispatch_after
            sendRaw(buildJsonObject {
                put("topic", "phoenix")
                put("event", "heartbeat")
                put("payload", buildJsonObject { })
                put("ref", nextRef())
            }.toString(), reportError = false)
            scheduleHeartbeat()
        }
    }

    private fun listen() {
        val socket = task ?: return
        socket.receiveMessageWithCompletionHandler { message, error ->
            if (closedByUs) return@receiveMessageWithCompletionHandler
            if (error != null) {
                onClosed(error.localizedDescription)
                return@receiveMessageWithCompletionHandler
            }
            val text = message?.string
            if (text != null) handle(text)
            listen()
        }
    }

    private fun handle(text: String) {
        val message = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val eventTopic = message.string("topic").orEmpty()
        if (eventTopic.isNotEmpty() && eventTopic != topic && eventTopic != "phoenix") return
        when (message.string("event")) {
            "phx_reply" -> {
                if (message.string("ref") != joinRef) return
                val payload = message.obj("payload")
                if (payload?.string("status") == "ok") {
                    sendPresence()
                    onJoined()
                } else {
                    val response = payload?.obj("response")
                    val reason = response?.string("reason")
                        ?: payload?.string("response")
                        ?: "The channel rejected this room."
                    onClosed(reason)
                }
            }
            "presence_state" -> {
                members.clear()
                readPresence(message.obj("payload"), members)
                onPresence(members.toMap())
            }
            "presence_diff" -> {
                val payload = message.obj("payload") ?: return
                readPresence(payload.obj("joins"), members)
                payload.obj("leaves")?.keys?.forEach { key -> members.remove(key) }
                onPresence(members.toMap())
            }
            "broadcast" -> {
                val payload = message.obj("payload") ?: return
                if (payload.string("event") != "signal") return
                val signal = payload.obj("payload") ?: return
                onSignal(signal.toString())
            }
            "phx_error", "phx_close" -> if (!closedByUs) onClosed("The channel disconnected.")
        }
    }

    private fun readPresence(source: JsonObject?, into: MutableMap<String, String>) {
        if (source == null) return
        for ((key, value) in source) {
            val entry = value as? JsonObject ?: continue
            val metas = entry["metas"] as? JsonArray
            val meta = metas?.firstOrNull() as? JsonObject
            into[key] = meta?.string("name") ?: "Phone"
        }
    }

    private fun sendRaw(text: String, reportError: Boolean = true) {
        val message = NSURLSessionWebSocketMessage(text)
        task?.sendMessage(message) { error ->
            if (error != null && reportError && !closedByUs) onClosed(error.localizedDescription)
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
