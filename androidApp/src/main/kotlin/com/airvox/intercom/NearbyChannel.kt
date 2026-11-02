package com.airvox.intercom

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionOptions
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionType
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Finds other Airvox phones and carries room setup.
 * Hotspot mode keeps the working Nearby link and lets WebRTC carry voice.
 * Bluetooth mode refuses a Wi-Fi upgrade and sends the voice frames on this link.
 */
class NearbyChannel(
    activity: ComponentActivity,
    private val listener: Listener,
    private val bluetoothOnly: Boolean = false,
) {
    interface Listener {
        fun onJoined()

        fun onPresence(members: Map<String, String>)

        fun onSignal(message: JSONObject)

        fun onAudio(peerId: String, audio: ByteArray)

        fun onClosed(reason: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val client: ConnectionsClient = Nearby.getConnectionsClient(activity)
    private var roomCode: String = ""
    private var selfId: String = ""
    private var selfName: String = ""
    private var closed = false
    private var joined = false
    private val pending = HashSet<String>()
    private val foundPeers = HashMap<String, PeerName>()
    private val connected = LinkedHashMap<String, PeerName>()
    private val audioSeq = AtomicInteger()
    private val serviceId = if (bluetoothOnly) "$SERVICE_ID.bt" else SERVICE_ID

    fun connect(roomCode: String, peerId: String, displayName: String) {
        this.roomCode = roomCode
        this.selfId = peerId
        this.selfName = displayName
        closed = false
        joined = false
        pending.clear()
        foundPeers.clear()
        connected.clear()
        val localName = encodeName(roomCode, peerId, displayName)
        client.startAdvertising(
            localName,
            serviceId,
            connectionLifecycle,
            advertisingOptions(),
        ).addOnSuccessListener {
            if (!closed) markJoined()
        }.addOnFailureListener { error ->
            val label = if (bluetoothOnly) "Bluetooth" else "the hotspot"
            fail("Could not start $label: ${error.message}")
        }
        beginDiscovery()
    }

    private fun beginDiscovery() {
        client.startDiscovery(
            serviceId,
            discoveryCallback,
            DiscoveryOptions.Builder().setStrategy(STRATEGY).build(),
        ).addOnFailureListener { error ->
            Log.w(TAG, "Discovery failed: ${error.message}")
        }
    }

    private fun advertisingOptions(): AdvertisingOptions {
        val builder = AdvertisingOptions.Builder().setStrategy(STRATEGY)
        if (bluetoothOnly) {
            builder
                .setConnectionType(ConnectionType.NON_DISRUPTIVE)
                .setDisruptiveUpgrade(false)
        }
        return builder.build()
    }

    fun send(body: JSONObject) {
        val bytes = Payload.fromBytes(body.toString().toByteArray(Charsets.UTF_8))
        val to = body.optString("to")
        if (to.isBlank()) {
            if (connected.isEmpty()) return
            client.sendPayload(ArrayList(connected.keys), bytes)
            return
        }
        val endpoint = connected.entries.firstOrNull { it.value.id == to }?.key ?: return
        client.sendPayload(endpoint, bytes)
    }

    fun sendAudio(mulaw: ByteArray) {
        if (closed || !bluetoothOnly || connected.isEmpty() || mulaw.isEmpty()) return
        val body = ByteArray(mulaw.size + 3)
        body[0] = AUDIO
        val seq = audioSeq.getAndIncrement() and 0xFFFF
        body[1] = (seq shr 8).toByte()
        body[2] = seq.toByte()
        System.arraycopy(mulaw, 0, body, 3, mulaw.size)
        client.sendPayload(ArrayList(connected.keys), Payload.fromBytes(body))
    }

    fun close() {
        closed = true
        runCatching { client.stopAdvertising() }
        runCatching { client.stopDiscovery() }
        runCatching { client.stopAllEndpoints() }
        pending.clear()
        foundPeers.clear()
        connected.clear()
    }

    private fun markJoined() {
        if (joined) return
        joined = true
        mainHandler.post { listener.onJoined() }
        publishPresence()
    }

    private fun publishPresence() {
        val members = linkedMapOf(selfId to selfName)
        connected.values.forEach { peer -> members[peer.id] = peer.name }
        mainHandler.post { listener.onPresence(members) }
    }

    private fun fail(reason: String) {
        if (closed) return
        closed = true
        close()
        mainHandler.post { listener.onClosed(reason) }
    }

    private fun requestIfNeeded(endpointId: String, found: PeerName) {
        if (closed || connected.containsKey(endpointId) || found.id == selfId) return
        if (!pending.add(endpointId)) return
        if (selfId < found.id) {
            pending.remove(endpointId)
            return
        }
        val name = encodeName(roomCode, selfId, selfName)
        val request = if (bluetoothOnly) {
            val options = ConnectionOptions.Builder()
                .setConnectionType(ConnectionType.NON_DISRUPTIVE)
                .setDisruptiveUpgrade(false)
                .build()
            client.requestConnection(name, endpointId, connectionLifecycle, options)
        } else {
            client.requestConnection(name, endpointId, connectionLifecycle)
        }
        request.addOnFailureListener { error ->
            pending.remove(endpointId)
            Log.w(TAG, "requestConnection failed: ${error.message}")
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (closed) return
            val found = decodeName(info.endpointName) ?: return
            if (found.room != roomCode || found.id == selfId) return
            foundPeers[endpointId] = found
            requestIfNeeded(endpointId, found)
        }

        override fun onEndpointLost(endpointId: String) {
            pending.remove(endpointId)
        }
    }

    private val connectionLifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            if (closed) {
                client.rejectConnection(endpointId)
                return
            }
            val found = decodeName(info.endpointName)
            if (found == null || found.room != roomCode || found.id == selfId) {
                client.rejectConnection(endpointId)
                return
            }
            foundPeers[endpointId] = found
            client.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            pending.remove(endpointId)
            if (closed) return
            if (result.status.statusCode != ConnectionsStatusCodes.STATUS_OK) {
                Log.w(TAG, "Connection failed: ${result.status}")
                return
            }
            val peer = foundPeers[endpointId] ?: return
            connected[endpointId] = peer
            send(
                JSONObject()
                    .put("kind", "hello")
                    .put("from", selfId)
                    .put("to", peer.id)
                    .put("name", selfName),
            )
            publishPresence()
        }

        override fun onDisconnected(endpointId: String) {
            pending.remove(endpointId)
            if (connected.remove(endpointId) != null) {
                foundPeers.remove(endpointId)
                publishPresence()
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (!connected.containsKey(endpointId)) return
            val bytes = payload.asBytes() ?: return
            if (bytes.size > 3 && bytes[0] == AUDIO) {
                val peer = connected[endpointId] ?: return
                val audio = bytes.copyOfRange(3, bytes.size)
                mainHandler.post { listener.onAudio(peer.id, audio) }
                return
            }
            val message = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull() ?: return
            val from = message.optString("from")
            val name = message.optString("name")
            val known = connected[endpointId]
            if (from.isNotBlank() && known != null) {
                connected[endpointId] = known.copy(
                    id = from,
                    name = name.ifBlank { known.name },
                )
                publishPresence()
            }
            mainHandler.post { listener.onSignal(message) }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private data class PeerName(val room: String, val id: String, val name: String)

    private companion object {
        const val TAG = "AirvoxNearby"
        const val SERVICE_ID = "com.airvox.channel"
        val AUDIO: Byte = 0xA1.toByte()
        val STRATEGY: Strategy = Strategy.P2P_CLUSTER

        fun encodeName(room: String, id: String, name: String): String {
            return "$room|$id|$name"
        }

        fun decodeName(raw: String): PeerName? {
            val parts = raw.split('|', limit = 3)
            if (parts.size < 3) return null
            return PeerName(parts[0], parts[1], parts[2])
        }
    }
}
