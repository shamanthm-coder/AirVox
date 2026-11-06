package com.airvox.intercom

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.airvox.intercom.BlePackets
import org.json.JSONObject
import java.util.UUID

/**
 * Bluetooth channel shared with iPhone.
 * Both phones advertise and scan the same service, then keep one link.
 */
@SuppressLint("MissingPermission")
class BleChannel(
    context: Context,
    private val listener: NearbyChannel.Listener,
) {
    private val main = Handler(Looper.getMainLooper())
    private val app = context.applicationContext
    private val manager = app.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = manager?.adapter
    private val serviceUuid = UUID.fromString(BlePackets.SERVICE)
    private val dataUuid = UUID.fromString(BlePackets.DATA)
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var room = ""
    private var selfId = ""
    private var selfName = ""
    private var closed = false
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var characteristic: BluetoothGattCharacteristic? = null

    private val subscribers = LinkedHashMap<String, BluetoothDevice>()
    private val clients = LinkedHashMap<String, Client>()
    private val peers = LinkedHashMap<String, String>()
    private val incoming = HashMap<String, BlePackets.Assembler>()
    private val mtu = HashMap<String, Int>()

    fun connect(roomCode: String, peerId: String, displayName: String) {
        room = roomCode
        selfId = peerId
        selfName = displayName
        closed = false
        val radio = adapter
        if (radio == null || !radio.isEnabled) {
            fail("Turn Bluetooth on, then join the channel again.")
            return
        }
        val gatt = manager?.openGattServer(app, serverCallback)
        if (gatt == null) {
            fail("This phone could not open Bluetooth.")
            return
        }
        server = gatt
        val data = BluetoothGattCharacteristic(
            dataUuid,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        data.addDescriptor(
            BluetoothGattDescriptor(
                cccdUuid,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        characteristic = data
        val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(data)
        gatt.addService(service)
    }

    fun rename(name: String) {
        val next = name.trim()
        if (next.isBlank() || closed || next == selfName) return
        selfName = next
        send(JSONObject().put("kind", "hello").put("from", selfId).put("name", selfName).put("room", room))
        publish()
    }

    fun send(body: JSONObject) {
        if (closed) return
        body.put("room", room)
        deliver(BlePackets.TEXT, body.toString().toByteArray(Charsets.UTF_8))
    }

    fun sendAudio(frame: ByteArray) {
        if (closed || peers.isEmpty() || frame.isEmpty()) return
        deliver(BlePackets.AUDIO, frame)
    }

    fun close() {
        closed = true
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        advertiser = null
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        clients.values.toList().forEach { client ->
            runCatching { client.gatt.disconnect() }
            runCatching { client.gatt.close() }
        }
        clients.clear()
        runCatching { server?.close() }
        server = null
        subscribers.clear()
        peers.clear()
        incoming.clear()
    }

    private fun startRadio() {
        if (closed) return
        val radio = adapter ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(serviceUuid))
            .build()
        val savedName = radio.name
        runCatching { radio.name = BlePackets.nameFor(selfId) }
        advertiser = radio.bluetoothLeAdvertiser
        advertiser?.startAdvertising(settings, data, advertiseCallback)
        main.postDelayed({
            if (!closed && savedName != null) runCatching { radio.name = savedName }
        }, 1_500)
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build()
        val scan = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { radio.bluetoothLeScanner?.startScan(listOf(filter), scan, scanCallback) }
        main.post { listener.onJoined() }
        publish()
    }

    private fun deliver(type: Byte, payload: ByteArray) {
        val char = characteristic ?: return
        val targets = LinkedHashSet<String>()
        targets += subscribers.keys
        targets += clients.keys
        targets.forEach { address ->
            val size = ((mtu[address] ?: 23) - 3).coerceIn(20, 180)
            BlePackets.pack(type, payload, size).forEach { packet ->
                if (subscribers.containsKey(address)) {
                    char.value = packet
                    val device = subscribers[address]
                    if (device != null) server?.notifyCharacteristicChanged(device, char, false)
                }
                val client = clients[address]
                if (client?.ready == true) client.write(packet)
            }
        }
    }

    private fun accept(address: String, role: Role, peerId: String, name: String, peerRoom: String) {
        if ((peerRoom.isNotBlank() && peerRoom != room) || peerId == selfId) {
            drop(address, role)
            return
        }
        if (role == Role.Central) clients[address]?.peerId = peerId else serverPeers[address] = peerId
        val central = clients.entries.firstOrNull { it.value.peerId == peerId }?.key
        val peripheral = serverPeers.entries.firstOrNull { it.value == peerId }?.key
        // Two links to the same phone. Keep the one already showing, and close the extra.
        if (central != null && peripheral != null && peers.containsKey(peerId)) {
            if (role == Role.Central) drop(central, Role.Central) else drop(peripheral, Role.Peripheral)
            return
        }
        peers[peerId] = name.ifBlank { "Phone" }
        Log.i(TAG, "Channel now includes $name")
        publish()
    }

    private fun drop(address: String, role: Role) {
        val peer = if (role == Role.Central) clients[address]?.peerId else serverPeers[address]
        if (role == Role.Central) {
            clients.remove(address)?.let { client ->
                runCatching { client.gatt.disconnect() }
                runCatching { client.gatt.close() }
            }
        } else {
            serverPeers.remove(address)
            subscribers.remove(address)?.let { device -> runCatching { server?.cancelConnection(device) } }
        }
        incoming.remove(address)
        mtu.remove(address)
        if (peer != null && clients.none { it.value.peerId == peer } && !serverPeers.containsValue(peer)) {
            peers.remove(peer)
        }
    }

    private fun onPacket(address: String, role: Role, packet: ByteArray) {
        // A phone can be connected both ways. Each direction has its own packets.
        val built = incoming.getOrPut("$address:$role") { BlePackets.Assembler() }.push(packet) ?: return
        val (type, payload) = built
        if (type == BlePackets.AUDIO) {
            val peer = clients[address]?.peerId ?: serverPeerId(address) ?: return
            main.post { listener.onAudio(peer, payload) }
            return
        }
        val text = runCatching { String(payload, Charsets.UTF_8) }.getOrNull() ?: return
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        val from = json.optString("from")
        if (from.isNotBlank() && json.optString("kind") == "hello") {
            accept(address, role, from, json.optString("name"), json.optString("room"))
        }
        main.post { listener.onSignal(json) }
    }

    private val serverPeers = HashMap<String, String>()

    private fun serverPeerId(address: String): String? = serverPeers[address]

    private fun helloPackets(address: String): List<ByteArray> {
        val hello = JSONObject()
            .put("kind", "hello")
            .put("from", selfId)
            .put("name", selfName)
            .put("room", room)
        val size = ((mtu[address] ?: 23) - 3).coerceIn(20, 180)
        return BlePackets.pack(BlePackets.TEXT, hello.toString().toByteArray(Charsets.UTF_8), size)
    }

    private fun publish() {
        val members = linkedMapOf(selfId to selfName)
        peers.forEach { (id, name) -> members[id] = name }
        main.post { listener.onPresence(members) }
    }

    private fun fail(reason: String) {
        if (closed) return
        close()
        main.post { listener.onClosed(reason) }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "Advertise failed: $errorCode")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (closed) return
            val device = result.device ?: return
            val address = device.address ?: return
            if (address == adapter?.address) return
            if (clients.containsKey(address) || subscribers.containsKey(address)) return
            val name = result.scanRecord?.deviceName.orEmpty()
            if (name == BlePackets.nameFor(selfId)) return
            val gatt = device.connectGatt(app, false, clientCallback(address), BluetoothDevice.TRANSPORT_LE)
            clients[address] = Client(gatt)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "Scan failed: $errorCode")
        }
    }

    private fun clientCallback(address: String) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (closed) return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.requestMtu(247)
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val peer = clients[address]?.peerId
                clients.remove(address)?.gatt?.close()
                incoming.remove(address)
                if (peer != null && peers.remove(peer) != null && serverPeers.none { it.value == peer }) publish()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtuValue: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu[address] = mtuValue
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (closed || status != BluetoothGatt.GATT_SUCCESS) return
            val data = gatt.getService(serviceUuid)?.getCharacteristic(dataUuid) ?: return
            clients[address]?.characteristic = data
            gatt.setCharacteristicNotification(data, true)
            val descriptor = data.getDescriptor(cccdUuid) ?: return
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            clients[address]?.ready = true
            helloPackets(address).forEach { clients[address]?.write(it) }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (android.os.Build.VERSION.SDK_INT >= 33) return
            val value = characteristic.value ?: return
            onPacket(address, Role.Central, value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            onPacket(address, Role.Central, value)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            clients[address]?.written()
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS) startRadio()
            else fail("This phone could not share the Bluetooth channel.")
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val address = device.address ?: return
                subscribers.remove(address)
                val peer = serverPeers.remove(address)
                incoming.remove(address)
                if (peer != null && clients.none { it.value.peerId == peer } && peers.remove(peer) != null) publish()
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtuValue: Int) {
            val address = device.address ?: return
            mtu[address] = mtuValue
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            val address = device.address
            if (address != null && offset == 0 && value.isNotEmpty()) {
                subscribers[address] = device
                Log.i(TAG, "Heard ${value.size} bytes from the other phone")
                onPacket(address, Role.Peripheral, value.copyOf())
            }
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            val address = device.address
            if (address != null) {
                subscribers[address] = device
                val data = characteristic
                if (data != null) {
                    helloPackets(address).forEach { packet ->
                        data.value = packet
                        server?.notifyCharacteristicChanged(device, data, false)
                    }
                }
            }
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) = Unit
    }

    private inner class Client(val gatt: BluetoothGatt) {
        var characteristic: BluetoothGattCharacteristic? = null
        var peerId: String? = null
        var ready = false
        private val queue = ArrayDeque<ByteArray>()
        private var writing = false

        fun write(packet: ByteArray) {
            queue.add(packet)
            drain()
        }

        fun written() {
            writing = false
            drain()
        }

        private fun drain() {
            if (writing) return
            val next = queue.removeFirstOrNull() ?: return
            val data = characteristic ?: return
            writing = true
            data.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            data.value = next
            if (!gatt.writeCharacteristic(data)) writing = false
        }
    }

    private enum class Role { Central, Peripheral }

    private companion object {
        const val TAG = "AirvoxBle"
    }
}
