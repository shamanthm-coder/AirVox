package com.airvox.intercom

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.airvox.BuildConfig
import com.airvox.playback.PlaybackController
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.security.MessageDigest
import java.util.UUID

class AndroidIntercomSession(
    private val activity: ComponentActivity,
    private val playback: PlaybackController,
) : IntercomSession {
    private val stateHolder = mutableStateOf(IntercomState())
    override val state: State<IntercomState> = stateHolder

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = activity.getSystemService(AudioManager::class.java)
    private val selfId = UUID.randomUUID().toString()
    private var selfName = Build.MODEL.ifBlank { "Phone" }

    private var generation = 0
    private var nearby: NearbyChannel? = null
    private var ble: BleChannel? = null
    private var lan: LanChannel? = null
    private var internet: SupabaseRealtime? = null
    private var path = ChannelPath.Hotspot
    private val remoteTalkers = LinkedHashSet<String>()
    private val talkTimeouts = HashMap<String, Runnable>()
    private var pendingPath = ChannelPath.Hotspot
    @Volatile var onMicLevel: ((Int) -> Unit)? = null

    private val bluetoothVoice = BluetoothVoice(
        context = activity.applicationContext,
        onFrame = { frame ->
            nearby?.sendAudio(frame)
            ble?.sendAudio(frame)
        },
        onError = { message ->
            mainHandler.post {
                detail = message
                publish()
            }
        },
    )

    init {
        bluetoothVoice.onLevel = { level -> onMicLevel?.invoke(level) }
    }
    private var factory: PeerConnectionFactory? = null
    private var audioModule: JavaAudioDeviceModule? = null
    private var localSource: AudioSource? = null
    private val peers = LinkedHashMap<String, VoiceLink>()
    private val earlySignals = LinkedHashMap<String, MutableList<JSONObject>>()
    private var roomCode: String = ""
    private var connection = IntercomConnection.Disconnected
    private var detail: String? = null
    private var localTalking = false
    private var voiceLatched = false
    private var routeActive = false
    private var channelReady = false
    private var presence = emptyMap<String, String>()
    private var lastLat: Double? = null
    private var lastLng: Double? = null
    private var lastSentAt = 0L
    private var placeAudience: Set<String> = emptySet()
    private val peerPlaces = LinkedHashMap<String, Spot>()
    private var pendingRoom: String? = null
    private var pendingSecret: String? = null
    private var scoReceiverRegistered = false

    private val permissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val room = pendingRoom
        val secret = pendingSecret
        val chosen = pendingPath
        pendingRoom = null
        pendingSecret = null
        if (joinPermissions(chosen).all { results[it] == true } && room != null && secret != null) {
            startJoin(room, chosen, secret)
        } else {
            path = chosen
            detail = permissionDetail(chosen)
            publish()
        }
    }

    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val scoState = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
            if (scoState == AudioManager.SCO_AUDIO_STATE_CONNECTED && localTalking) {
                setLocalTracksEnabled(true)
            }
        }
    }

    override fun setRiderName(name: String) {
        val trimmed = name.trim().take(40)
        if (trimmed.isBlank() || trimmed == selfName) return
        selfName = trimmed
        lan?.rename(trimmed)
        nearby?.rename(trimmed)
        ble?.rename(trimmed)
        internet?.rename(trimmed)
        lastSentAt = 0L
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
        val missing = joinPermissions(path).filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            pendingRoom = normalized
            pendingSecret = secret
            pendingPath = path
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        startJoin(normalized, path, secret)
    }

    override fun leave() {
        generation++
        tearDown(resumeMusic = true)
        roomCode = ""
        connection = IntercomConnection.Disconnected
        detail = "Left the channel."
        localTalking = false
        presence = emptyMap()
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
            // Both people can talk at once. The other phone keeps playing while this one talks.
            if (talking) {
                refreshVoice()
                bluetoothVoice.startCapture()
            } else {
                bluetoothVoice.stopCapture()
                refreshVoice()
            }
            sendSignal(JSONObject().put("kind", "talk").put("talking", talking))
            publish()
            return
        }
        if (talking) {
            refreshVoice()
            sendSignal(JSONObject().put("kind", "talk").put("talking", true))
            setLocalTracksEnabled(true)
            mainHandler.removeCallbacks(talkPulse)
            mainHandler.postDelayed(talkPulse, 2_000)
        } else {
            mainHandler.removeCallbacks(talkPulse)
            setLocalTracksEnabled(false)
            sendSignal(JSONObject().put("kind", "talk").put("talking", false))
            refreshVoice()
        }
        publish()
    }

    fun dispose() {
        leave()
    }

    private fun startJoin(normalized: String, selected: ChannelPath, password: String) {
        generation++
        val gen = generation
        tearDown(resumeMusic = true)
        path = selected
        roomCode = normalized
        localTalking = false
        presence = emptyMap()
        remoteTalkers.clear()
        if (selected == ChannelPath.Internet && !internetConfigured()) {
            connection = IntercomConnection.Disconnected
            detail = "Add supabase.url and supabase.anon.key in local.properties, then rebuild, to use internet voice."
            publish()
            return
        }
        connection = IntercomConnection.Connecting
        detail = joiningDetail(normalized)
        publish()
        val channel = channelKey(normalized, password)
        when (selected) {
            ChannelPath.Internet -> connectInternet(gen, channel)
            else -> connectNearby(gen, channel)
        }
    }

    private fun channelKey(room: String, password: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(password.toByteArray(Charsets.UTF_8))
        val gate = digest.take(6).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
        return "$room-$gate"
    }

    private fun connectNearby(gen: Int, normalized: String) {
        if (path == ChannelPath.Hotspot) {
            ensureFactory()
            val channel = LanChannel(
                context = activity,
                listener = object : LanChannel.Listener {
                    override fun onJoined() = signalJoined(gen)

                    override fun onPresence(members: Map<String, String>) = signalPresence(gen, members)

                    override fun onSignal(message: JSONObject) = signalMessage(gen, message)

                    override fun onClosed(reason: String) = signalClosed(gen, reason)
                },
            )
            lan = channel
            channel.connect(normalized, selfId, selfName)
            return
        }
        ble = BleChannel(
            context = activity,
            listener = object : NearbyChannel.Listener {
                override fun onJoined() = signalJoined(gen)

                override fun onPresence(members: Map<String, String>) = signalPresence(gen, members)

                override fun onSignal(message: JSONObject) = signalMessage(gen, message)

                override fun onAudio(peerId: String, audio: ByteArray) {
                    if (gen != generation || path != ChannelPath.Bluetooth) return
                    noteRemoteAudio(peerId)
                    bluetoothVoice.enqueue(audio)
                }

                override fun onClosed(reason: String) = signalClosed(gen, reason)
            },
        )
        ble?.connect(normalized, selfId, selfName)
    }

    private fun connectInternet(gen: Int, normalized: String) {
        ensureFactory()
        val realtime = SupabaseRealtime(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            anonKey = BuildConfig.SUPABASE_ANON_KEY,
            listener = object : SupabaseRealtime.Listener {
                override fun onJoined() = signalJoined(gen)

                override fun onPresence(members: Map<String, String>) = signalPresence(gen, members)

                override fun onSignal(message: JSONObject) = signalMessage(gen, message)

                override fun onClosed(reason: String) = signalClosed(gen, reason)
            },
        )
        internet = realtime
        realtime.connect(normalized, selfId, selfName)
    }

    private fun signalJoined(gen: Int) {
        if (gen != generation) return
        channelReady = true
        detail = waitingDetail()
        publish()
        if (path == ChannelPath.Bluetooth) refreshBluetoothPeers() else syncPeers()
        notePlaceAudience(presence)
    }

    private fun signalPresence(gen: Int, members: Map<String, String>) {
        if (gen != generation) return
        presence = members
        notePlaceAudience(members)
        publish()
        if (!channelReady) return
        if (path == ChannelPath.Bluetooth) refreshBluetoothPeers() else syncPeers()
    }

    private fun signalMessage(gen: Int, message: JSONObject) {
        if (gen != generation) return
        handleSignal(message)
    }

    private fun signalClosed(gen: Int, reason: String) {
        if (gen != generation) return
        Log.w(TAG, "Channel closed: $reason")
        tearDown(resumeMusic = true)
        connection = IntercomConnection.Disconnected
        detail = reason
        publish()
    }

    private fun refreshBluetoothPeers() {
        val others = presence.filterKeys { it != selfId }.keys
        remoteTalkers.filter { it !in others }.toList().forEach { peerId ->
            talkTimeouts.remove(peerId)?.let { mainHandler.removeCallbacks(it) }
            remoteTalkers.remove(peerId)
        }
        if (others.isEmpty()) {
            connection = IntercomConnection.Connecting
            detail = waitingDetail()
        } else if (connection != IntercomConnection.Connected) {
            markConnected()
        }
        refreshVoice()
        publish()
    }

    private fun noteRemoteTalk(peerId: String, talking: Boolean) {
        talkTimeouts.remove(peerId)?.let { mainHandler.removeCallbacks(it) }
        if (talking) {
            remoteTalkers.add(peerId)
            val timeout = Runnable {
                talkTimeouts.remove(peerId)
                if (remoteTalkers.remove(peerId)) {
                    refreshVoice()
                    publish()
                }
            }
            talkTimeouts[peerId] = timeout
            mainHandler.postDelayed(timeout, 900)
        } else {
            remoteTalkers.remove(peerId)
        }
        refreshVoice()
        publish()
    }

    private fun noteRemoteAudio(peerId: String) {
        val existing = talkTimeouts[peerId]
        if (existing != null && remoteTalkers.contains(peerId)) {
            mainHandler.removeCallbacks(existing)
            mainHandler.postDelayed(existing, 900)
            return
        }
        noteRemoteTalk(peerId, true)
    }

    private fun joiningDetail(room: String): String = when (path) {
        ChannelPath.Bluetooth -> "Looking over Bluetooth for $room…"
        ChannelPath.Hotspot -> "Looking for other phones on this hotspot…"
        ChannelPath.Internet -> "Joining $room over the internet…"
    }

    private fun waitingDetail(): String = when (path) {
        ChannelPath.Bluetooth -> "Bluetooth is on. Waiting for other phones with this room code."
        ChannelPath.Hotspot -> "Hotspot is open. Waiting for the other phone on this same Wi-Fi."
        ChannelPath.Internet -> "Waiting for other phones on this internet channel."
    }

    private fun permissionDetail(forPath: ChannelPath): String = when (forPath) {
        ChannelPath.Internet -> "Allow the microphone so you can talk."
        ChannelPath.Bluetooth -> "Allow the microphone and Bluetooth so phones can find each other."
        ChannelPath.Hotspot -> "Allow the microphone, nearby devices, and Bluetooth so phones can open a hotspot."
    }

    private fun internetConfigured(): Boolean {
        return BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()
    }

    private fun syncPeers() {
        val others = presence.filterKeys { it != selfId }
        val leftover = peers.keys - others.keys
        leftover.forEach { closePeer(it) }
        others.keys.forEach { other ->
            val link = peers[other] ?: openPeer(other) ?: return@forEach
            if (selfId > other && !link.offerSent) {
                link.offerSent = true
                createOffer(link)
            }
        }
        if (others.isEmpty()) {
            if (channelReady) {
                connection = IntercomConnection.Connecting
                detail = waitingDetail()
            }
        } else if (peers.values.any { it.iceConnected }) {
            markConnected()
        }
        refreshVoice()
        publish()
    }

    private fun handleSignal(message: JSONObject) {
        val from = message.optString("from")
        val to = message.optString("to")
        if (from.isBlank() || from == selfId) return
        if (to.isNotBlank() && to != selfId) return
        when (message.optString("kind")) {
            "offer" -> {
                if (path == ChannelPath.Bluetooth) return
                val link = peers[from] ?: openPeer(from) ?: return
                if (selfId > from && link.offerSent) return
                setRemoteDescription(link, SessionDescription.Type.OFFER, message.optString("sdp")) {
                    createAnswer(link)
                }
            }
            "answer" -> {
                if (path == ChannelPath.Bluetooth) return
                val link = peers[from] ?: return
                setRemoteDescription(link, SessionDescription.Type.ANSWER, message.optString("sdp"))
            }
            "ice" -> {
                if (path == ChannelPath.Bluetooth) return
                val link = peers[from]
                if (link == null) {
                    rememberEarly(from, message)
                    return
                }
                val candidate = IceCandidate(
                    message.optString("sdpMid"),
                    message.optInt("sdpMLineIndex"),
                    message.optString("candidate"),
                )
                if (link.remoteDescriptionSet) {
                    link.connection.addIceCandidate(candidate)
                } else {
                    link.pendingIce.add(candidate)
                }
            }
            "talk" -> {
                if (path == ChannelPath.Bluetooth) {
                    noteRemoteTalk(from, message.optBoolean("talking"))
                } else {
                    val link = peers[from]
                    if (link == null) {
                        rememberEarly(from, message)
                        return
                    }
                    onRemoteTalk(from, message.optBoolean("talking"))
                }
            }
            "hello" -> Unit
            "place" -> rememberPlace(from, message)
            "bye" -> {
                earlySignals.remove(from)
                closePeer(from)
                if (peers.isEmpty() && channelReady) {
                    connection = IntercomConnection.Connecting
                    detail = "A phone left. The channel stays open for others."
                }
                refreshVoice()
                publish()
            }
        }
    }

    private fun onRemoteTalk(peerId: String, talking: Boolean) {
        val link = peers[peerId] ?: return
        link.talking = talking
        link.remoteTrack?.setEnabled(talking)
        refreshVoice()
        publish()
    }

    private fun anyoneTalking(): Boolean {
        return localTalking || remoteTalkers.isNotEmpty() || peers.values.any { it.talking }
    }

    private fun refreshVoice() {
        val active = anyoneTalking()
        if (active && !voiceLatched) {
            voiceLatched = true
            playback.pauseForVoice()
            startVoiceRoute()
        } else if (!active && voiceLatched) {
            voiceLatched = false
            setLocalTracksEnabled(false)
            peers.values.forEach { it.remoteTrack?.setEnabled(false) }
            stopVoiceRoute()
            playback.resumeAfterVoice()
        }
    }

    private fun setLocalTracksEnabled(enabled: Boolean) {
        peers.values.forEach { it.localTrack.setEnabled(enabled && localTalking) }
    }

    private fun sendSignal(body: JSONObject, to: String? = null) {
        body.put("from", selfId)
        if (to != null) {
            body.put("to", to)
        } else if (!body.has("to")) {
            body.put("to", "")
        }
        when (path) {
            ChannelPath.Hotspot -> lan?.send(body)
            ChannelPath.Internet -> internet?.send(body)
            else -> {
                nearby?.send(body)
                ble?.send(body)
            }
        }
    }

    private fun ensureFactory() {
        if (factory != null) return
        if (!factoryInitialized) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(activity.applicationContext)
                    .createInitializationOptions(),
            )
            factoryInitialized = true
        }
        val module = JavaAudioDeviceModule.builder(activity)
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setSamplesReadyCallback { samples ->
                val listener = onMicLevel ?: return@setSamplesReadyCallback
                listener(pcm16Rms(samples.data))
            }
            .createAudioDeviceModule()
        audioModule = module
        // When this phone is the hotspot, Android does not list the hotspot as one of its networks.
        // With the network monitor on, the voice link then has no address the other phone can reach.
        // Off, it reads the phone's network interfaces itself and finds the hotspot one.
        val options = PeerConnectionFactory.Options().apply { disableNetworkMonitor = true }
        factory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setAudioDeviceModule(module)
            .createPeerConnectionFactory()
        localSource = factory?.createAudioSource(MediaConstraints())
    }

    private fun openPeer(peerId: String): VoiceLink? {
        val currentFactory = factory ?: return null
        val source = localSource ?: return null
        closePeer(peerId)
        val created = currentFactory.createPeerConnection(rtcConfig(), observerFor(peerId))
        if (created == null) {
            detail = "Could not open the voice channel."
            publish()
            return null
        }
        val track = currentFactory.createAudioTrack("airvox-voice-$peerId", source)
        track.setEnabled(localTalking)
        created.addTrack(track, listOf("airvox-voice"))
        val link = VoiceLink(
            peerId = peerId,
            connection = created,
            localTrack = track,
        )
        peers[peerId] = link
        flushEarly(peerId)
        return link
    }

    private fun observerFor(peerId: String) = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            Log.i(TAG, "Voice link to $peerId: $state")
            if (state == PeerConnection.IceConnectionState.CONNECTED ||
                state == PeerConnection.IceConnectionState.COMPLETED
            ) {
                mainHandler.post {
                    peers[peerId]?.iceConnected = true
                    markConnected()
                }
            } else if (state == PeerConnection.IceConnectionState.FAILED && path == ChannelPath.Internet) {
                mainHandler.post {
                    if (peers[peerId]?.iceConnected != true) {
                        detail = "Could not reach that phone over the internet."
                        publish()
                    }
                }
            }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            Log.i(TAG, "Voice connection to $peerId: $newState")
            mainHandler.post {
                val link = peers[peerId] ?: return@post
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        link.iceConnected = true
                        markConnected()
                    }
                    PeerConnection.PeerConnectionState.FAILED -> {
                        closePeer(peerId)
                        detail = "Voice to one phone dropped. Waiting to reconnect."
                        if (peers.values.none { it.iceConnected }) {
                            connection = IntercomConnection.Connecting
                        }
                        publish()
                        syncPeers()
                    }
                    else -> Unit
                }
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit

        override fun onIceCandidate(candidate: IceCandidate) {
            sendSignal(
                JSONObject()
                    .put("kind", "ice")
                    .put("sdpMid", candidate.sdpMid)
                    .put("sdpMLineIndex", candidate.sdpMLineIndex)
                    .put("candidate", candidate.sdp),
                to = peerId,
            )
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit

        override fun onAddStream(stream: org.webrtc.MediaStream) = Unit

        override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit

        override fun onDataChannel(channel: org.webrtc.DataChannel) = Unit

        override fun onRenegotiationNeeded() = Unit

        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out org.webrtc.MediaStream>) {
            val track = receiver.track()
            if (track is AudioTrack) {
                mainHandler.post {
                    val link = peers[peerId] ?: return@post
                    link.remoteTrack = track
                    track.setEnabled(link.talking)
                }
            }
        }
    }

    private fun markConnected() {
        connection = IntercomConnection.Connected
        detail = when (path) {
            ChannelPath.Bluetooth -> "Bluetooth channel is open. Tap Talk to speak."
            ChannelPath.Hotspot -> "Hotspot is open. Tap Talk to speak. Music pauses while someone is talking."
            ChannelPath.Internet -> "Internet channel is open. Tap Talk to speak."
        }
        publish()
        if (localTalking && path != ChannelPath.Bluetooth) {
            sendSignal(JSONObject().put("kind", "talk").put("talking", true))
        }
    }

    private fun rtcConfig(): PeerConnection.RTCConfiguration {
        return PeerConnection.RTCConfiguration(iceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            if (path == ChannelPath.Internet) {
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            }
        }
    }

    private fun iceServers(): List<PeerConnection.IceServer> {
        if (path != ChannelPath.Internet) {
            // The phones are on the same hotspot. Local addresses are enough,
            // and a STUN lookup cannot work when there is no internet.
            return emptyList()
        }
        return listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder(
                listOf(
                    "turn:global.relay.metered.ca:80",
                    "turn:global.relay.metered.ca:443",
                    "turns:global.relay.metered.ca:443?transport=tcp",
                ),
            ).setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
        )
    }

    private fun createOffer(link: VoiceLink) {
        link.connection.createOffer(object : SdpAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                link.connection.setLocalDescription(SdpAdapter(), sdp)
                sendSignal(JSONObject().put("kind", "offer").put("sdp", sdp.description), to = link.peerId)
            }

            override fun onCreateFailure(error: String) {
                mainHandler.post {
                    detail = error
                    publish()
                }
            }
        }, audioConstraints())
    }

    private fun createAnswer(link: VoiceLink) {
        link.connection.createAnswer(object : SdpAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                link.connection.setLocalDescription(SdpAdapter(), sdp)
                sendSignal(JSONObject().put("kind", "answer").put("sdp", sdp.description), to = link.peerId)
            }

            override fun onCreateFailure(error: String) {
                mainHandler.post {
                    detail = error
                    publish()
                }
            }
        }, audioConstraints())
    }

    private fun setRemoteDescription(
        link: VoiceLink,
        type: SessionDescription.Type,
        sdp: String,
        after: () -> Unit = {},
    ) {
        if (sdp.isBlank()) return
        link.connection.setRemoteDescription(object : SdpAdapter() {
            override fun onSetSuccess() {
                link.remoteDescriptionSet = true
                link.pendingIce.forEach { link.connection.addIceCandidate(it) }
                link.pendingIce.clear()
                after()
            }

            override fun onSetFailure(error: String) {
                mainHandler.post {
                    detail = error
                    publish()
                }
            }
        }, SessionDescription(type, sdp))
    }

    private fun rememberEarly(from: String, message: JSONObject) {
        if (from.isBlank()) return
        val bucket = earlySignals.getOrPut(from) { mutableListOf() }
        if (bucket.size >= 40) bucket.removeAt(0)
        bucket.add(JSONObject(message.toString()))
    }

    private fun flushEarly(peerId: String) {
        val waiting = earlySignals.remove(peerId) ?: return
        waiting.forEach { handleSignal(it) }
    }

    private val talkPulse = object : Runnable {
        override fun run() {
            if (!localTalking || path == ChannelPath.Bluetooth) return
            sendSignal(JSONObject().put("kind", "talk").put("talking", true))
            mainHandler.postDelayed(this, 2_000)
        }
    }

    private fun audioConstraints(): MediaConstraints {
        return MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }
    }

    private fun startVoiceRoute() {
        if (routeActive) return
        routeActive = true
        if (path == ChannelPath.Bluetooth) {
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = true
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (max > 0 && current < max / 2) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (max / 2).coerceAtLeast(1), 0)
            }
            return
        }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        val bluetooth = bluetoothRoute()
        if (bluetooth != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.setCommunicationDevice(bluetooth)
            audioManager.isSpeakerphoneOn = false
        } else if (bluetooth != null) {
            registerScoReceiver()
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
            audioManager.isSpeakerphoneOn = false
        } else {
            audioManager.isSpeakerphoneOn = true
        }
    }

    private fun stopVoiceRoute() {
        if (!routeActive) return
        routeActive = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
        audioManager.isBluetoothScoOn = false
        audioManager.stopBluetoothSco()
        audioManager.isSpeakerphoneOn = false
        audioManager.mode = AudioManager.MODE_NORMAL
        mainHandler.postDelayed({
            if (!routeActive) audioManager.mode = AudioManager.MODE_NORMAL
        }, 400)
    }

    private fun speakerDevice(): AudioDeviceInfo? {
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    }

    private fun bluetoothRoute(): AudioDeviceInfo? {
        val outputs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices +
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        } else {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }
        return outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        }
    }

    private fun registerScoReceiver() {
        if (scoReceiverRegistered) return
        val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(scoReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            activity.registerReceiver(scoReceiver, filter)
        }
        scoReceiverRegistered = true
    }

    private fun closePeer(peerId: String) {
        val link = peers.remove(peerId) ?: return
        link.localTrack.setEnabled(false)
        link.connection.close()
        link.localTrack.dispose()
    }

    private fun closeAllPeers() {
        peers.keys.toList().forEach { closePeer(it) }
    }

    private fun tearDown(resumeMusic: Boolean) {
        channelReady = false
        bluetoothVoice.stopCapture()
        bluetoothVoice.stopPlayback()
        clearTalkTimeouts()
        remoteTalkers.clear()
        peerPlaces.clear()
        earlySignals.clear()
        placeAudience = emptySet()
        nearby?.close()
        nearby = null
        ble?.close()
        ble = null
        lan?.close()
        lan = null
        internet?.close()
        internet = null
        closeAllPeers()
        localSource?.dispose()
        localSource = null
        factory?.dispose()
        factory = null
        runCatching { audioModule?.release() }
        audioModule = null
        if (scoReceiverRegistered) {
            runCatching { activity.unregisterReceiver(scoReceiver) }
            scoReceiverRegistered = false
        }
        localTalking = false
        mainHandler.removeCallbacks(talkPulse)
        if (voiceLatched && resumeMusic) {
            voiceLatched = false
            stopVoiceRoute()
            playback.resumeAfterVoice()
        } else if (voiceLatched) {
            voiceLatched = false
            stopVoiceRoute()
        } else {
            stopVoiceRoute()
        }
    }

    private fun publish() {
        val remoteTalking = peers.values.any { it.talking } || remoteTalkers.isNotEmpty()
        val next = IntercomState(
            roomCode = roomCode,
            connection = connection,
            path = path,
            members = memberList(),
            localTalking = localTalking,
            remoteTalking = remoteTalking,
            places = placeList(),
            detail = detail,
        )
        if (Looper.myLooper() == Looper.getMainLooper()) {
            stateHolder.value = next
        } else {
            mainHandler.post { stateHolder.value = next }
        }
    }

    fun sharePlace(latitude: Double, longitude: Double) {
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return
        lastLat = latitude
        lastLng = longitude
        publish()
        broadcastPlace(force = false)
    }

    private fun notePlaceAudience(members: Map<String, String>) {
        val others = members.keys - selfId
        peerPlaces.keys.retainAll(others)
        val grew = others.any { it !in placeAudience }
        placeAudience = others
        if (grew) broadcastPlace(force = true)
    }

    private fun broadcastPlace(force: Boolean) {
        val lat = lastLat ?: return
        val lng = lastLng ?: return
        if (connection != IntercomConnection.Connecting && connection != IntercomConnection.Connected) return
        val now = SystemClock.elapsedRealtime()
        val wait = if (force) 1_000L else 4_000L
        if (now - lastSentAt < wait) return
        lastSentAt = now
        sendSignal(
            JSONObject()
                .put("kind", "place")
                .put("name", selfName)
                .put("lat", lat)
                .put("lng", lng),
        )
    }

    private fun rememberPlace(peerId: String, message: JSONObject) {
        val lat = message.optDouble("lat", Double.NaN)
        val lng = message.optDouble("lng", Double.NaN)
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return
        val name = message.optString("name").ifBlank { presence[peerId] ?: "Phone" }
        peerPlaces[peerId] = Spot(name = name, latitude = lat, longitude = lng)
        publish()
    }

    private fun placeList(): List<ChannelPlace> {
        val self = lastLat?.let { lat ->
            lastLng?.let { lng ->
                ChannelPlace(id = selfId, name = selfName, latitude = lat, longitude = lng, self = true)
            }
        }
        if (connection == IntercomConnection.Disconnected) {
            return listOfNotNull(self)
        }
        val others = peerPlaces
            .filterKeys { it != selfId && (presence.isEmpty() || presence.containsKey(it)) }
            .map { (id, spot) ->
                ChannelPlace(
                    id = id,
                    name = spot.name.ifBlank { presence[id] ?: "Phone" },
                    latitude = spot.latitude,
                    longitude = spot.longitude,
                )
            }
        return listOfNotNull(self) + others
    }

    private fun memberList(): List<IntercomMember> {
        if (connection == IntercomConnection.Disconnected) return emptyList()
        val others = presence
            .filterKeys { it != selfId }
            .map { (id, name) ->
                IntercomMember(
                    id = id,
                    name = name,
                    talking = peers[id]?.talking == true || remoteTalkers.contains(id),
                )
            }
        return listOf(IntercomMember(selfId, "$selfName (you)", localTalking)) + others
    }

    private data class Spot(
        val name: String,
        val latitude: Double,
        val longitude: Double,
    )

    private class VoiceLink(
        val peerId: String,
        val connection: PeerConnection,
        val localTrack: AudioTrack,
        var remoteTrack: AudioTrack? = null,
        var offerSent: Boolean = false,
        var remoteDescriptionSet: Boolean = false,
        val pendingIce: MutableList<IceCandidate> = mutableListOf(),
        var talking: Boolean = false,
        var iceConnected: Boolean = false,
    )

    private open class SdpAdapter : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = Unit

        override fun onSetSuccess() = Unit

        override fun onCreateFailure(error: String) {
            Log.w(TAG, "SDP create failed: $error")
        }

        override fun onSetFailure(error: String) {
            Log.w(TAG, "SDP set failed: $error")
        }
    }

    private fun clearTalkTimeouts() {
        talkTimeouts.values.forEach { mainHandler.removeCallbacks(it) }
        talkTimeouts.clear()
    }

    private fun joinPermissions(forPath: ChannelPath): Array<String> {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (forPath == ChannelPath.Internet) return permissions.toTypedArray()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_ADVERTISE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return permissions.toTypedArray()
    }

    private fun pcm16Rms(data: ByteArray): Int {
        if (data.size < 2) return 0
        var sum = 0.0
        var count = 0
        var index = 0
        while (index + 1 < data.size) {
            val sample = (data[index].toInt() and 0xFF) or (data[index + 1].toInt() shl 8)
            val value = sample.toShort().toInt()
            sum += value * value.toDouble()
            count++
            index += 2
        }
        if (count == 0) return 0
        return kotlin.math.sqrt(sum / count).toInt()
    }

    private companion object {
        const val TAG = "AirvoxIntercom"
        var factoryInitialized = false
    }
}
