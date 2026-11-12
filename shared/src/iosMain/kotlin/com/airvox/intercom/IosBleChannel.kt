package com.airvox.intercom

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import platform.CoreBluetooth.CBATTErrorSuccess
import platform.CoreBluetooth.CBATTRequest
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBAttributePermissionsWriteable
import platform.CoreBluetooth.CBCentral
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicPropertyNotify
import platform.CoreBluetooth.CBCharacteristicPropertyWrite
import platform.CoreBluetooth.CBCharacteristicPropertyWriteWithoutResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithoutResponse
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBMutableCharacteristic
import platform.CoreBluetooth.CBMutableService
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBPeripheralManager
import platform.CoreBluetooth.CBPeripheralManagerDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataWithBytes
import platform.darwin.NSObject
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time
import platform.posix.memcpy

/**
 * The iPhone side of the Bluetooth channel Android uses.
 * Both phones advertise the same service and keep a single link.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosBleChannel(
    private val onJoined: () -> Unit,
    private val onPresence: (Map<String, String>) -> Unit,
    private val onSignal: (String) -> Unit,
    private val onAudio: (String, ByteArray) -> Unit,
    private val onClosed: (String) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serviceUuid = CBUUID.UUIDWithString(BlePackets.SERVICE)
    private val dataUuid = CBUUID.UUIDWithString(BlePackets.DATA)
    private var room = ""
    private var selfId = ""
    private var selfName = ""
    private var closed = false
    private var characteristic: CBMutableCharacteristic? = null
    private val peers = LinkedHashMap<String, String>()
    private val centrals = LinkedHashMap<String, Link>()
    private val peripherals = LinkedHashMap<String, Link>()
    private val seen = LinkedHashMap<String, CBPeripheral>()
    private val outgoing = ArrayDeque<Outgoing>()
    private var sending = false
    private var pacing = false
    private var audioOut = 0
    private var audioIn = 0

    private val centralDelegate = object : NSObject(), CBCentralManagerDelegateProtocol {
        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            if (central.state == CBManagerStatePoweredOn) central.scanForPeripheralsWithServices(listOf(serviceUuid), null)
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber,
        ) {
            if (closed) return
            val name = advertisementData[CBAdvertisementDataLocalNameKey] as? String
            if (name == BlePackets.nameFor(selfId)) return
            val id = didDiscoverPeripheral.identifier.UUIDString
            if (peripherals.containsKey(id) || seen.containsKey(id)) return
            seen[id] = didDiscoverPeripheral
            didDiscoverPeripheral.delegate = peripheralDelegate
            central.connectPeripheral(didDiscoverPeripheral, null)
        }

        override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
            println("AirvoxBle: connected to a phone as central")
            didConnectPeripheral.discoverServices(listOf(serviceUuid))
        }

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didFailToConnectPeripheral: CBPeripheral, error: NSError?) {
            seen.remove(didFailToConnectPeripheral.identifier.UUIDString)
        }

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didDisconnectPeripheral: CBPeripheral, error: NSError?) {
            val id = didDisconnectPeripheral.identifier.UUIDString
            println("AirvoxBle: central link closed, error ${error?.localizedDescription}")
            val peer = peripherals.remove(id)?.peerId
            seen.remove(id)
            if (peer != null && centrals.none { it.value.peerId == peer }) {
                peers.remove(peer)
                publish()
            }
        }
    }

    private val peripheralDelegate = object : NSObject(), CBPeripheralDelegateProtocol {
        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            val service = peripheral.services?.filterIsInstance<CBService>()?.firstOrNull { it.UUID == serviceUuid } ?: return
            peripheral.discoverCharacteristics(listOf(dataUuid), service)
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didDiscoverCharacteristicsForService: CBService,
            error: NSError?,
        ) {
            val data = didDiscoverCharacteristicsForService.characteristics
                ?.filterIsInstance<CBCharacteristic>()
                ?.firstOrNull { it.UUID == dataUuid }
                ?: return
            val id = peripheral.identifier.UUIDString
            peripherals.getOrPut(id) { Link(Role.Central) }.characteristic = data
            peripheral.setNotifyValue(true, data)
            ensureHello(id, Role.Central)
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateValueForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            val value = didUpdateValueForCharacteristic.value ?: return
            onPacket(peripheral.identifier.UUIDString, Role.Central, value.toBytes())
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didWriteValueForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            val id = peripheral.identifier.UUIDString
            val link = peripherals[id] ?: return
            if (link.skips > 0) {
                link.skips -= 1
                return
            }
            if (!link.writing) return
            link.generation += 1
            link.writing = false
            link.audioHold = false
            drainCentral(id)
        }

        override fun peripheralIsReadyToSendWriteWithoutResponse(peripheral: CBPeripheral) {
            drainCentral(peripheral.identifier.UUIDString)
        }
    }

    private val serverDelegate = object : NSObject(), CBPeripheralManagerDelegateProtocol {
        override fun peripheralManagerDidUpdateState(peripheral: CBPeripheralManager) {
            if (peripheral.state != CBManagerStatePoweredOn || closed) return
            val data = CBMutableCharacteristic(
                type = dataUuid,
                properties = CBCharacteristicPropertyWrite or
                    CBCharacteristicPropertyWriteWithoutResponse or
                    CBCharacteristicPropertyNotify,
                value = null,
                permissions = CBAttributePermissionsWriteable,
            )
            characteristic = data
            val service = CBMutableService(type = serviceUuid, primary = true)
            service.setCharacteristics(listOf(data))
            peripheral.addService(service)
        }

        override fun peripheralManager(peripheral: CBPeripheralManager, didAddService: CBService, error: NSError?) {
            if (error != null || closed) return
            peripheral.startAdvertising(
                mapOf(
                    CBAdvertisementDataServiceUUIDsKey to listOf(serviceUuid),
                    CBAdvertisementDataLocalNameKey to BlePackets.nameFor(selfId),
                ),
            )
            onMain {
                onJoined()
                publish()
            }
        }

        @ObjCSignatureOverride
        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            central: CBCentral,
            didSubscribeToCharacteristic: CBCharacteristic,
        ) {
            val id = central.identifier.UUIDString
            println("AirvoxBle: a phone subscribed, packet size ${central.maximumUpdateValueLength}")
            // A new subscription is a new start, even from a phone seen before.
            centrals.remove(id)
            val link = centrals.getOrPut(id) { Link(Role.Peripheral) }
            link.remote = central
            ensureHello(id, Role.Peripheral)
        }

        // A phone that left must not stay in the list. Its old entry reports a tiny packet
        // size, and that used to throw away the voice meant for the phone still here.
        @ObjCSignatureOverride
        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            central: CBCentral,
            didUnsubscribeFromCharacteristic: CBCharacteristic,
        ) {
            val id = central.identifier.UUIDString
            println("AirvoxBle: a phone unsubscribed")
            val peer = centrals.remove(id)?.peerId
            outgoing.removeAll { it.target == id }
            if (peer != null && centrals.none { it.value.peerId == peer } && peripherals.none { it.value.peerId == peer }) {
                peers.remove(peer)
                publish()
            }
        }

        override fun peripheralManager(peripheral: CBPeripheralManager, didReceiveWriteRequests: List<*>) {
            didReceiveWriteRequests.filterIsInstance<CBATTRequest>().forEach { request ->
                val id = request.central?.identifier?.UUIDString
                val value = request.value
                if (id != null && value != null) {
                    centrals.getOrPut(id) { Link(Role.Peripheral) }.remote = request.central
                    onPacket(id, Role.Peripheral, value.toBytes())
                }
                peripheral.respondToRequest(request, CBATTErrorSuccess)
            }
        }

        override fun peripheralManagerIsReadyToUpdateSubscribers(peripheral: CBPeripheralManager) {
            sending = false
            drain()
        }
    }

    private val central = CBCentralManager(delegate = centralDelegate, queue = null)
    private val server = CBPeripheralManager(delegate = serverDelegate, queue = null)

    fun start(roomCode: String, peerId: String, displayName: String) {
        room = roomCode
        selfId = peerId
        selfName = displayName
        closed = false
        if (central.state == CBManagerStatePoweredOn) {
            central.scanForPeripheralsWithServices(listOf(serviceUuid), null)
        }
    }

    fun rename(name: String) {
        val next = name.trim()
        if (next.isBlank() || next == selfName) return
        selfName = next
        send(buildJsonObject {
            put("kind", "hello")
            put("name", selfName)
        }.toString())
        publish()
    }

    fun send(text: String) {
        if (closed) return
        deliver(BlePackets.TEXT, text.encodeToByteArray())
    }

    fun sendAudio(frame: ByteArray) {
        if (closed || peers.isEmpty() || frame.isEmpty()) return
        val copy = frame.copyOf()
        onMain {
            if (!closed && peers.isNotEmpty()) {
                audioOut += 1
                if (audioOut % 50 == 1) {
                    val asPeripheral = centrals.entries.joinToString { "${packetRoom(it.key, Role.Peripheral, BlePackets.AUDIO)}/sub=${it.value.remote != null}/known=${it.value.peerId != null}" }
                    val asCentral = peripherals.entries.joinToString { "${packetRoom(it.key, Role.Central, BlePackets.AUDIO)}/known=${it.value.peerId != null}" }
                    println("AirvoxBle: voice out. notify=[$asPeripheral] write=[$asCentral]")
                }
                deliver(BlePackets.AUDIO, copy)
            }
        }
    }

    fun close() {
        closed = true
        runCatching { central.stopScan() }
        peripherals.keys.toList().forEach { id ->
            seen[id]?.let { central.cancelPeripheralConnection(it) }
        }
        runCatching { server.stopAdvertising() }
        runCatching { characteristic?.let { server.removeAllServices() } }
        peers.clear()
        centrals.clear()
        peripherals.clear()
        seen.clear()
        outgoing.clear()
    }

    private fun ensureHello(id: String, role: Role) {
        val link = if (role == Role.Central) peripherals[id] else centrals[id]
        if (link == null || link.helloLoop) return
        link.helloLoop = true
        pumpHello(id, role)
    }

    private fun pumpHello(id: String, role: Role) {
        val link = if (role == Role.Central) peripherals[id] else centrals[id]
        if (link == null || closed || link.heard) {
            link?.helloLoop = false
            return
        }
        link.helloAttempts += 1
        if (link.helloAttempts > 8) {
            link.helloLoop = false
            return
        }
        sendHello(id, role, link)
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 1_200_000_000), dispatch_get_main_queue()) {
            pumpHello(id, role)
        }
    }

    private fun sendHello(id: String, role: Role, link: Link) {
        // Always 20 bytes. A longer hello is one packet Android throws away,
        // so the iPhone never appears on that phone.
        val att = localAtt(id, role)
        link.sentAtt = att
        val hello = buildJsonObject {
            put("kind", "hello")
            put("from", selfId)
            put("name", selfName)
            put("room", room)
            put("ack", link.peerId.orEmpty())
            put("att", att)
            put("os", "ios")
        }.toString().encodeToByteArray()
        BlePackets.pack(BlePackets.TEXT, hello, 20).forEach { write(id, role, it, paced = true) }
    }

    /**
     * The iPhone learns the real packet size a moment after the link opens. If the first hello
     * carried the small starting size, the other phone kept cutting voice into tiny pieces.
     * Tell it again when the size changes.
     */
    private fun refreshAtt(id: String, role: Role) {
        val link = (if (role == Role.Central) peripherals[id] else centrals[id]) ?: return
        if (link.sentAtt == 0 || link.peerId == null) return
        if (localAtt(id, role) != link.sentAtt) sendHello(id, role, link)
    }

    private fun deliver(type: Byte, payload: ByteArray) {
        val paced = type == BlePackets.TEXT
        val targets = centrals.keys.map { it to Role.Peripheral } + peripherals.keys.map { it to Role.Central }
        targets.forEach { (id, role) ->
            if (type == BlePackets.AUDIO) refreshAtt(id, role)
            val size = packetRoom(id, role, type)
            val packets = if (type == BlePackets.AUDIO) {
                BlePackets.packSolo(type, payload, size)
            } else {
                BlePackets.pack(type, payload, size)
            }
            packets.forEach { write(id, role, it, paced) }
        }
    }

    private fun localAtt(id: String, role: Role): Int {
        val room = if (role == Role.Central) {
            // The no-response size is the real size of one packet on this link. The with-response
            // size is always 512, because the iPhone splits those writes, and Android drops the pieces.
            seen[id]?.maximumWriteValueLengthForType(CBCharacteristicWriteWithoutResponse)?.toInt() ?: 20
        } else {
            centrals[id]?.remote?.maximumUpdateValueLength?.toInt() ?: 20
        }
        return room.coerceIn(20, 180)
    }

    private fun packetRoom(id: String, role: Role, type: Byte): Int {
        if (type == BlePackets.TEXT) return 20
        val link = if (role == Role.Central) peripherals[id] else centrals[id]
        val agreed = (link?.peerAtt ?: 20).coerceIn(20, 180)
        return minOf(agreed, localAtt(id, role))
    }

    private fun write(id: String, role: Role, packet: ByteArray, paced: Boolean) {
        val data = packet.toNSData()
        if (role == Role.Central) {
            val link = peripherals[id] ?: return
            if (!paced) trimPending(link.pending)
            link.pending.add(Outgoing(data, paced, id))
            drainCentral(id)
        } else {
            if (!paced) trimOutgoing(id)
            outgoing.add(Outgoing(data, paced, id))
            drain()
        }
    }

    private fun trimPending(queue: ArrayDeque<Outgoing>) {
        var audio = queue.count { !it.paced }
        while (audio > AUDIO_BACKLOG) {
            val index = queue.indexOfFirst { !it.paced }
            if (index < 0) return
            queue.removeAt(index)
            audio--
        }
    }

    private fun trimOutgoing(id: String) {
        var audio = outgoing.count { !it.paced && it.target == id }
        while (audio > AUDIO_BACKLOG) {
            val index = outgoing.indexOfFirst { !it.paced && it.target == id }
            if (index < 0) return
            outgoing.removeAt(index)
            audio--
        }
    }

    private fun drainCentral(id: String) {
        val link = peripherals[id] ?: return
        if (link.writing || link.audioHold) return
        val peripheral = seen[id] ?: return
        val dataChar = link.characteristic ?: return
        val next = link.pending.firstOrNull() ?: return
        if (!next.paced) {
            if (link.peerOs == "ios" || !ANDROID_VOICE_WITH_RESPONSE) {
                if (!peripheral.canSendWriteWithoutResponse) {
                    if (!link.audioHold) {
                        link.audioHold = true
                        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 15_000_000), dispatch_get_main_queue()) {
                            link.audioHold = false
                            drainCentral(id)
                        }
                    }
                    return
                }
                link.pending.removeFirst()
                peripheral.writeValue(next.bytes, dataChar, CBCharacteristicWriteWithoutResponse)
                link.audioHold = true
                dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 12_000_000), dispatch_get_main_queue()) {
                    link.audioHold = false
                    drainCentral(id)
                }
                return
            }
            // Kept as a fallback. A write that waits for a reply takes a full round trip,
            // about 25 a second at best, and voice needs 50.
            link.pending.removeFirst()
            link.writing = true
            link.audioHold = true
            val generation = ++link.generation
            peripheral.writeValue(next.bytes, dataChar, CBCharacteristicWriteWithResponse)
            dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 40_000_000), dispatch_get_main_queue()) {
                if (link.generation != generation || !link.writing) return@dispatch_after
                link.writing = false
                link.audioHold = false
                link.skips += 1
                drainCentral(id)
            }
            return
        }
        link.pending.removeFirst()
        link.writing = true
        val generation = ++link.generation
        peripheral.writeValue(next.bytes, dataChar, CBCharacteristicWriteWithResponse)
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 500_000_000), dispatch_get_main_queue()) {
            if (link.generation != generation || !link.writing) return@dispatch_after
            link.writing = false
            link.skips += 1
            drainCentral(id)
        }
    }

    private fun drain() {
        if (sending || pacing) return
        val next = outgoing.firstOrNull() ?: return
        val dataChar = characteristic ?: return
        // Each packet goes only to the phone it was cut for. Sending every packet to every
        // phone played voice twice, and one phone with a small size blocked the others.
        val remote = centrals[next.target]?.remote
        if (remote == null || next.bytes.length.toInt() > remote.maximumUpdateValueLength.toInt().coerceAtLeast(20)) {
            outgoing.removeFirst()
            drain()
            return
        }
        sending = true
        val sent = server.updateValue(next.bytes, dataChar, listOf(remote))
        if (!sent) {
            sending = false
            dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 15_000_000), dispatch_get_main_queue()) {
                drain()
            }
            return
        }
        outgoing.removeFirst()
        pacing = true
        sending = false
        val gap = if (next.paced) 30_000_000L else 12_000_000L
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, gap), dispatch_get_main_queue()) {
            pacing = false
            drain()
        }
    }

    private fun onPacket(id: String, role: Role, packet: ByteArray) {
        val link = if (role == Role.Central) peripherals.getOrPut(id) { Link(role) } else centrals.getOrPut(id) { Link(role) }
        val built = link.incoming.push(packet) ?: return
        val (type, payload) = built
        if (type == BlePackets.AUDIO) {
            audioIn += 1
            if (audioIn % 50 == 1) println("AirvoxBle: voice in on $role link, ${payload.size} bytes, sender known: ${link.peerId != null}")
            val peer = link.peerId ?: return
            onMain { onAudio(peer, payload) }
            return
        }
        val text = runCatching { payload.decodeToString() }.getOrNull() ?: return
        val message = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val from = message.string("from") ?: return
        if (message.string("kind") == "hello") {
            val att = message.int("att")
            if (att != null && att in 20..180) link.peerAtt = att
            val os = message.string("os")
            if (!os.isNullOrBlank()) link.peerOs = os
            if (message.string("ack") == selfId) link.heard = true
            val heardUs = message.string("ack") == selfId
            println("AirvoxBle: hello from $from on $role link, size $att, heard us: $heardUs")
            accept(id, role, from, message.string("name").orEmpty(), message.string("room").orEmpty())
            refreshAtt(id, role)
            // The other phone is still asking who is here, on a link this phone thought was
            // settled. Answer it, or it never plays this phone's voice.
            val live = if (role == Role.Central) peripherals[id] else centrals[id]
            if (!heardUs && live != null && live.sentAtt != 0 && !live.helloLoop) sendHello(id, role, live)
        }
        onMain { onSignal(text) }
    }

    private fun accept(id: String, role: Role, peerId: String, name: String, peerRoom: String) {
        if ((peerRoom.isNotBlank() && peerRoom != room) || peerId == selfId) {
            drop(id, role)
            return
        }
        val link = if (role == Role.Central) peripherals[id] else centrals[id]
        link?.peerId = peerId
        val centralId = centrals.entries.firstOrNull { it.value.peerId == peerId }?.key
        val peripheralId = peripherals.entries.firstOrNull { it.value.peerId == peerId }?.key
        if (centralId != null && peripheralId != null) {
            if (selfId > peerId) drop(centralId, Role.Peripheral) else drop(peripheralId, Role.Central)
        }
        val kept = centrals.any { it.value.peerId == peerId } || peripherals.any { it.value.peerId == peerId }
        if (!kept) return
        peers[peerId] = name.ifBlank { "Phone" }
        publish()
        ensureHello(id, role)
    }

    private fun drop(id: String, role: Role) {
        val peer = if (role == Role.Central) peripherals[id]?.peerId else centrals[id]?.peerId
        if (role == Role.Central) {
            peripherals.remove(id)
            seen[id]?.let { central.cancelPeripheralConnection(it) }
        } else {
            centrals.remove(id)
        }
        if (peer != null && centrals.none { it.value.peerId == peer } && peripherals.none { it.value.peerId == peer }) {
            peers.remove(peer)
        }
    }

    private fun publish() {
        val members = linkedMapOf(selfId to selfName)
        peers.forEach { (id, name) -> members[id] = name }
        onMain { onPresence(members) }
    }

    private fun onMain(block: () -> Unit) {
        dispatch_async(dispatch_get_main_queue()) { block() }
    }

    private class Link(val role: Role) {
        var characteristic: CBCharacteristic? = null
        var remote: CBCentral? = null
        var peerId: String? = null
        var heard = false
        var helloLoop = false
        var helloAttempts = 0
        var writing = false
        var generation = 0
        var skips = 0
        var audioHold = false
        // The other phone's limit. This phone's own limit still applies, so start high.
        var peerAtt = 180
        var sentAtt = 0
        var peerOs = ""
        val pending = ArrayDeque<Outgoing>()
        val incoming = BlePackets.Assembler()
    }

    private class Outgoing(val bytes: NSData, val paced: Boolean, val target: String)

    private enum class Role { Central, Peripheral }

    private companion object {
        /** Voice packets that may wait. The microphone hands over five at a time. */
        const val AUDIO_BACKLOG = 12

        /** Set to true to go back to voice writes that wait for Android's reply. */
        const val ANDROID_VOICE_WITH_RESPONSE = false
    }

    private fun JsonObject.string(key: String): String? {
        return this[key]?.jsonPrimitive?.contentOrNull
    }

    private fun JsonObject.int(key: String): Int? {
        return this[key]?.jsonPrimitive?.intOrNull
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toBytes(): ByteArray {
    val size = length.toInt()
    if (size <= 0) return ByteArray(0)
    val out = ByteArray(size)
    out.usePinned { pinned ->
        memcpy(pinned.addressOf(0), bytes, size.convert())
    }
    return out
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { NSData.dataWithBytes(it.addressOf(0), size.convert()) }
}
