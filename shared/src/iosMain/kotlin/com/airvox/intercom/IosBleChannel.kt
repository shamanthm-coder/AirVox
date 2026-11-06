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
    private val outgoing = ArrayDeque<NSData>()
    private var sending = false

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
            didConnectPeripheral.discoverServices(listOf(serviceUuid))
        }

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didFailToConnectPeripheral: CBPeripheral, error: NSError?) {
            seen.remove(didFailToConnectPeripheral.identifier.UUIDString)
        }

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didDisconnectPeripheral: CBPeripheral, error: NSError?) {
            val id = didDisconnectPeripheral.identifier.UUIDString
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
            sendHello(id, Role.Central)
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
        ) = Unit

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

        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            central: CBCentral,
            didSubscribeToCharacteristic: CBCharacteristic,
        ) {
            val id = central.identifier.UUIDString
            centrals.getOrPut(id) { Link(Role.Peripheral) }
            sendHello(id, Role.Peripheral)
        }

        override fun peripheralManager(peripheral: CBPeripheralManager, didReceiveWriteRequests: List<*>) {
            didReceiveWriteRequests.filterIsInstance<CBATTRequest>().forEach { request ->
                val id = request.central?.identifier?.UUIDString
                val value = request.value
                if (id != null && value != null) {
                    centrals.getOrPut(id) { Link(Role.Peripheral) }
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
        deliver(BlePackets.AUDIO, frame)
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

    private fun sendHello(id: String, role: Role) {
        val hello = buildJsonObject {
            put("kind", "hello")
            put("from", selfId)
            put("name", selfName)
            put("room", room)
        }.toString().encodeToByteArray()
        BlePackets.pack(BlePackets.TEXT, hello, packetRoom(id, role)).forEach { write(id, role, it) }
        val again = "$id:$role"
        if (again !in helloRetried) {
            helloRetried.add(again)
            dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 1_200_000_000), dispatch_get_main_queue()) {
                val link = if (role == Role.Central) peripherals[id] else centrals[id]
                if (!closed && link?.peerId == null) sendHello(id, role)
            }
        }
    }

    private val helloRetried = HashSet<String>()

    private fun deliver(type: Byte, payload: ByteArray) {
        val targets = centrals.keys.map { it to Role.Peripheral } + peripherals.keys.map { it to Role.Central }
        targets.forEach { (id, role) ->
            BlePackets.pack(type, payload, packetRoom(id, role)).forEach { write(id, role, it) }
        }
    }

    private fun packetRoom(id: String, role: Role): Int {
        if (role != Role.Central) return 20
        val peripheral = seen[id] ?: return 20
        val room = peripheral.maximumWriteValueLengthForType(CBCharacteristicWriteWithoutResponse).toInt()
        // One write carries the whole name once the phones agree on a larger size.
        return room.coerceIn(20, 180)
    }

    private fun write(id: String, role: Role, packet: ByteArray) {
        val data = packet.toNSData()
        if (role == Role.Central) {
            val link = peripherals[id] ?: return
            link.pending.add(data)
            drainCentral(id)
        } else {
            outgoing.add(data)
            drain()
        }
    }

    private fun drainCentral(id: String) {
        val link = peripherals[id] ?: return
        val peripheral = seen[id] ?: return
        val dataChar = link.characteristic ?: return
        while (link.pending.isNotEmpty() && peripheral.canSendWriteWithoutResponse) {
            val next = link.pending.removeFirst()
            peripheral.writeValue(next, dataChar, CBCharacteristicWriteWithoutResponse)
        }
    }

    private fun drain() {
        if (sending) return
        val next = outgoing.removeFirstOrNull() ?: return
        val dataChar = characteristic ?: return
        sending = true
        val sent = server.updateValue(next, dataChar, null)
        if (sent) sending = false.also { if (outgoing.isNotEmpty()) drain() }
    }

    private fun onPacket(id: String, role: Role, packet: ByteArray) {
        val link = if (role == Role.Central) peripherals.getOrPut(id) { Link(role) } else centrals.getOrPut(id) { Link(role) }
        val built = link.incoming.push(packet) ?: return
        val (type, payload) = built
        if (type == BlePackets.AUDIO) {
            val peer = link.peerId ?: return
            onMain { onAudio(peer, payload) }
            return
        }
        val text = runCatching { payload.decodeToString() }.getOrNull() ?: return
        val message = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val from = message.string("from") ?: return
        if (message.string("kind") == "hello") {
            accept(id, role, from, message.string("name").orEmpty(), message.string("room").orEmpty())
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
        var peerId: String? = null
        var writing = false
        val pending = ArrayDeque<NSData>()
        val incoming = BlePackets.Assembler()
    }

    private enum class Role { Central, Peripheral }

    private fun JsonObject.string(key: String): String? {
        return this[key]?.jsonPrimitive?.contentOrNull
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
