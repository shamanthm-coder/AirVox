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
import android.bluetooth.BluetoothStatusCodes
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
    private val serverPeers = HashMap<String, String>()
    private val serverAtt = HashMap<String, Int>()
    private val notifyQueue = ArrayDeque<Notice>()
    private val notifyGate = Any()
    private val helloLoops = HashSet<String>()
    private val helloTries = HashMap<String, Int>()
    private val acked = HashSet<String>()
    private var notifyBusy = false
    private var notifyTicket = 0
    private var savedAdapterName: String? = null
    private var advertiseRetried = false
    private var lastNotify: ByteArray? = null
    private var lastNotifyAddress = ""
    private var lastNotifyAt = 0L
    private var lastAudioLog = 0L
    private var lastSendLog = 0L

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
        if (closed || frame.isEmpty()) return
        val now = android.os.SystemClock.elapsedRealtime()
        val report = now - lastSendLog > 1_000
        if (report) lastSendLog = now
        if (peers.isEmpty()) {
            if (report) Log.w(TAG, "Voice not sent: no phone has said hello on this channel")
            return
        }
        if (report) {
            val notify = subscribers.keys.joinToString { "$it/${audioRoom(it)}" }
            val write = clients.entries.joinToString { "${it.key}/${audioRoom(it.key)}/ready=${it.value.ready}" }
            Log.i(TAG, "Voice out ${frame.size} bytes. notify=[$notify] write=[$write]")
        }
        deliver(BlePackets.AUDIO, frame)
    }

    /** A link that closed starts from nothing when it opens again, so it says hello again. */
    private fun forgetHello(address: String, role: Role) {
        val key = "$address:$role"
        helloLoops.remove(key)
        helloTries.remove(key)
        acked.remove(key)
    }

    fun close() {
        closed = true
        main.removeCallbacksAndMessages(null)
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
        serverPeers.clear()
        serverAtt.clear()
        synchronized(notifyGate) { notifyQueue.clear() }
        helloLoops.clear()
        helloTries.clear()
        acked.clear()
        val saved = savedAdapterName
        savedAdapterName = null
        if (saved != null) runCatching { adapter?.name = saved }
    }

    private fun startRadio() {
        if (closed) return
        val radio = adapter ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        // The service id and the phone name do not fit in one 31-byte advert.
        // The id goes in the advert. The short name goes in the scan response.
        if (savedAdapterName == null) savedAdapterName = radio.name
        runCatching { radio.name = BlePackets.nameFor(selfId) }
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(serviceUuid))
            .build()
        val response = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        advertiser = radio.bluetoothLeAdvertiser
        advertiser?.startAdvertising(settings, data, response, advertiseCallback)
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build()
        val scan = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { radio.bluetoothLeScanner?.startScan(listOf(filter), scan, scanCallback) }
        main.post { listener.onJoined() }
        publish()
    }

    private fun deliver(type: Byte, payload: ByteArray) {
        val reliable = type == BlePackets.TEXT
        val targets = LinkedHashSet<String>()
        targets += subscribers.keys
        targets += clients.keys
        targets.forEach { address ->
            val size = if (reliable) 20 else audioRoom(address)
            val packets = if (reliable) {
                BlePackets.pack(type, payload, size)
            } else {
                BlePackets.packSolo(type, payload, size)
            }
            packets.forEach { packet ->
                val device = subscribers[address]
                if (device != null) enqueueNotify(device, packet, reliable)
                val client = clients[address]
                if (client?.ready == true) client.write(packet, reliable)
            }
        }
    }

    private fun audioRoom(address: String): Int {
        val local = localRoom(address)
        val peer = clients[address]?.peerAtt ?: serverAtt[address] ?: local
        return minOf(local, peer)
    }

    private fun localRoom(address: String): Int = ((mtu[address] ?: 23) - 3).coerceIn(20, 180)

    private fun accept(address: String, role: Role, peerId: String, name: String, peerRoom: String) {
        if ((peerRoom.isNotBlank() && peerRoom != room) || peerId == selfId) {
            drop(address, role)
            return
        }
        if (role == Role.Central) clients[address]?.peerId = peerId else serverPeers[address] = peerId
        val central = clients.entries.firstOrNull { it.value.peerId == peerId }?.key
        val peripheral = serverPeers.entries.firstOrNull { it.value == peerId }?.key
        // Same rule as the iPhone: the higher id stays central, so both phones keep one link.
        if (central != null && peripheral != null) {
            if (selfId > peerId) drop(peripheral, Role.Peripheral) else drop(central, Role.Central)
        }
        val kept = clients.any { it.value.peerId == peerId } || serverPeers.containsValue(peerId)
        if (!kept) return
        peers[peerId] = name.ifBlank { "Phone" }
        Log.i(TAG, "Channel now includes $name")
        publish()
        val alive = if (role == Role.Central) clients.containsKey(address) else subscribers.containsKey(address)
        if (alive) startHello(address, role)
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
            serverAtt.remove(address)
            subscribers.remove(address)?.let { device -> runCatching { server?.cancelConnection(device) } }
        }
        Log.i(TAG, "Closed the spare $role link to $address")
        val key = "$address:$role"
        forgetHello(address, role)
        incoming.remove(key)
        if (!clients.containsKey(address) && !subscribers.containsKey(address)) mtu.remove(address)
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
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastAudioLog > 1_000) {
                lastAudioLog = now
                Log.w(TAG, "Voice arrived from the other phone, ${payload.size} bytes")
            }
            main.post { listener.onAudio(peer, payload) }
            return
        }
        val text = runCatching { String(payload, Charsets.UTF_8) }.getOrNull() ?: return
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        val from = json.optString("from")
        if (from.isNotBlank() && json.optString("kind") == "hello") {
            val att = json.optInt("att", 0)
            if (att in 20..180) {
                if (role == Role.Central) clients[address]?.peerAtt = att else serverAtt[address] = att
            }
            val key = "$address:$role"
            val heardUs = json.optString("ack") == selfId
            if (heardUs) acked.add(key)
            val saidHello = (helloTries[key] ?: 0) > 0
            Log.i(TAG, "Hello from $from on $role link $address, size $att, heard us: $heardUs")
            accept(address, role, from, json.optString("name"), json.optString("room"))
            // The other phone is still asking who is here. This happens after it leaves and
            // joins again on a link this phone thought was settled. Answer it every time.
            if (!heardUs && saidHello) pushHello(address, role)
        }
        main.post { listener.onSignal(json) }
    }

    private fun serverPeerId(address: String): String? = serverPeers[address]

    private fun startHello(address: String, role: Role) {
        val key = "$address:$role"
        if (!helloLoops.add(key)) return
        pumpHello(address, role, key)
    }

    private fun pumpHello(address: String, role: Role, key: String) {
        if (closed || key in acked) {
            helloLoops.remove(key)
            return
        }
        val attempt = (helloTries[key] ?: 0) + 1
        helloTries[key] = attempt
        if (attempt > 8) {
            helloLoops.remove(key)
            return
        }
        pushHello(address, role)
        main.postDelayed({
            if (!closed && key !in acked && key in helloLoops) pumpHello(address, role, key)
        }, 1_200)
    }

    private fun pushHello(address: String, role: Role) {
        if (closed) return
        val ack = if (role == Role.Central) clients[address]?.peerId.orEmpty() else serverPeers[address].orEmpty()
        val hello = JSONObject()
            .put("kind", "hello")
            .put("from", selfId)
            .put("name", selfName)
            .put("room", room)
            .put("ack", ack)
            // This phone's own limit. Echoing the smaller of the two sizes back made an early
            // small value from the iPhone stick on both phones, and voice went out in scraps.
            .put("att", localRoom(address))
            .put("os", "android")
            .toString()
            .toByteArray(Charsets.UTF_8)
        val packets = BlePackets.pack(BlePackets.TEXT, hello, 20)
        if (role == Role.Central) {
            val client = clients[address]
            if (client?.ready == true) packets.forEach { client.write(it, reliable = true) }
        } else {
            val device = subscribers[address]
            if (device != null) packets.forEach { enqueueNotify(device, it, paced = true) }
        }
    }

    private fun enqueueNotify(device: BluetoothDevice, packet: ByteArray, paced: Boolean) {
        synchronized(notifyGate) {
            if (closed) return
            if (!paced) trimAudio(notifyQueue) { !it.paced }
            notifyQueue.add(Notice(device, packet, paced))
        }
        drainNotify()
    }

    private fun drainNotify() {
        val next = synchronized(notifyGate) {
            if (notifyBusy || closed) return
            val queued = notifyQueue.firstOrNull() ?: return
            notifyBusy = true
            queued
        }
        val data = characteristic
        val gattServer = server
        val sent = if (data != null && gattServer != null) {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                gattServer.notifyCharacteristicChanged(next.device, data, false, next.bytes) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                data.value = next.bytes
                gattServer.notifyCharacteristicChanged(next.device, data, false)
            }
        } else {
            false
        }
        if (!sent) {
            holdNotify(15)
            return
        }
        synchronized(notifyGate) {
            if (notifyQueue.firstOrNull() === next) notifyQueue.removeFirst()
        }
        // Keep the gap even if the phone reports the send finished early. A burst drops the voice.
        holdNotify(if (next.paced) 30 else 12)
    }

    private fun holdNotify(delayMs: Long) {
        val ticket = synchronized(notifyGate) {
            notifyBusy = true
            ++notifyTicket
        }
        main.postDelayed({ releaseNotify(ticket) }, delayMs)
    }

    private fun releaseNotify(ticket: Int) {
        val resume = synchronized(notifyGate) {
            if (ticket != notifyTicket || !notifyBusy) {
                false
            } else {
                notifyBusy = false
                true
            }
        }
        if (resume) drainNotify()
    }

    private fun onNotify(address: String, value: ByteArray) {
        val now = android.os.SystemClock.elapsedRealtime()
        val previous = lastNotify
        if (
            previous != null &&
            address == lastNotifyAddress &&
            now - lastNotifyAt < 5 &&
            previous.contentEquals(value)
        ) {
            return
        }
        lastNotify = value.copyOf()
        lastNotifyAddress = address
        lastNotifyAt = now
        onPacket(address, Role.Central, value)
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
            if (closed || advertiseRetried || errorCode != ADVERTISE_FAILED_DATA_TOO_LARGE) return
            advertiseRetried = true
            val radio = adapter ?: return
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .build()
            val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(serviceUuid)).build()
            radio.bluetoothLeAdvertiser?.startAdvertising(settings, data, this)
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
            Log.i(TAG, "Central link to $address state $newState status $status")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                forgetHello(address, Role.Central)
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                gatt.requestMtu(247)
                main.postDelayed({
                    if (!closed && clients[address]?.characteristic == null) {
                        clients[address]?.discovered = true
                        gatt.discoverServices()
                    }
                }, 800)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val peer = clients[address]?.peerId
                clients.remove(address)?.gatt?.close()
                forgetHello(address, Role.Central)
                incoming.remove("$address:${Role.Central}")
                if (!subscribers.containsKey(address)) mtu.remove(address)
                if (peer != null && serverPeers.none { it.value == peer } && peers.remove(peer) != null) publish()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtuValue: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && mtuValue > 23) {
                val previous = mtu[address] ?: 23
                mtu[address] = mtuValue
                // The first hello often leaves before this. Tell the iPhone the real size,
                // or it keeps sending tiny packets that cannot carry speech.
                if (mtuValue > previous) pushHello(address, Role.Central)
            }
            val client = clients[address] ?: return
            if (client.discovered) return
            client.discovered = true
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (closed || status != BluetoothGatt.GATT_SUCCESS) return
            val data = gatt.getService(serviceUuid)?.getCharacteristic(dataUuid) ?: return
            clients[address]?.characteristic = data
            gatt.setCharacteristicNotification(data, true)
            val descriptor = data.getDescriptor(cccdUuid) ?: return
            val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                gatt.writeDescriptor(descriptor, enable)
            } else {
                descriptor.value = enable
                gatt.writeDescriptor(descriptor)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Notify subscribe failed: $status")
            }
            clients[address]?.ready = true
            forgetHello(address, Role.Central)
            startHello(address, Role.Central)
        }

        // The iPhone takes its service away when it leaves and puts it back when it joins again,
        // without closing the link. Find the service again, or its voice never arrives here.
        override fun onServiceChanged(gatt: BluetoothGatt) {
            if (closed) return
            Log.i(TAG, "Service changed on $address, looking again")
            clients[address]?.let { client ->
                client.ready = false
                client.characteristic = null
            }
            gatt.discoverServices()
        }

        @Deprecated("Use the byte-array callback on newer phones")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (android.os.Build.VERSION.SDK_INT >= 33 && clients[address]?.sawNewNotify == true) return
            val value = characteristic.value ?: return
            onNotify(address, value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            clients[address]?.sawNewNotify = true
            onNotify(address, value)
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
            Log.i(TAG, "Peripheral link from ${device.address} state $newState status $status")
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val address = device.address ?: return
                forgetHello(address, Role.Peripheral)
                subscribers.remove(address)
                serverAtt.remove(address)
                val peer = serverPeers.remove(address)
                helloLoops.remove("$address:${Role.Peripheral}")
                incoming.remove("$address:${Role.Peripheral}")
                if (!clients.containsKey(address)) mtu.remove(address)
                if (peer != null && clients.none { it.value.peerId == peer } && peers.remove(peer) != null) publish()
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtuValue: Int) {
            val address = device.address ?: return
            val previous = mtu[address] ?: 23
            if (mtuValue > previous) {
                mtu[address] = mtuValue
                pushHello(address, Role.Peripheral)
            }
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
            val bytes = if (address != null && offset == 0 && value.isNotEmpty()) value.copyOf() else null
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
            if (address != null && bytes != null) {
                subscribers[address] = device
                onPacket(address, Role.Peripheral, bytes)
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
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
            if (address != null) {
                subscribers[address] = device
                forgetHello(address, Role.Peripheral)
                startHello(address, Role.Peripheral)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) = Unit
    }

    private inner class Client(val gatt: BluetoothGatt) {
        var characteristic: BluetoothGattCharacteristic? = null
        var peerId: String? = null
        var ready = false
        var discovered = false
        var sawNewNotify = false
        var peerAtt = 180
        private val queue = ArrayDeque<Outgoing>()
        private val gate = Any()
        private var writing = false
        private var writeTicket = 0
        private var reliableInFlight = false

        fun write(packet: ByteArray, reliable: Boolean) {
            synchronized(gate) {
                if (closed) return
                if (!reliable) trimAudio(queue) { !it.reliable }
                queue.add(Outgoing(packet, reliable))
            }
            drain()
        }

        fun written() {
            val ticket = synchronized(gate) {
                if (!reliableInFlight) return
                writeTicket
            }
            releaseWrite(ticket)
        }

        private fun releaseWrite(ticket: Int) {
            val resume = synchronized(gate) {
                if (ticket != writeTicket || !writing) {
                    false
                } else {
                    writing = false
                    true
                }
            }
            if (resume) drain()
        }

        private fun drain() {
            val next: Outgoing
            val data: BluetoothGattCharacteristic
            synchronized(gate) {
                if (writing || closed) return
                val queued = queue.firstOrNull() ?: return
                val current = characteristic ?: return
                writing = true
                reliableInFlight = queued.reliable
                queue.removeFirst()
                next = queued
                data = current
            }
            val writeType = if (next.reliable) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            val started = if (android.os.Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(data, next.bytes, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                data.writeType = writeType
                data.value = next.bytes
                gatt.writeCharacteristic(data)
            }
            if (!started) {
                synchronized(gate) {
                    writing = false
                    reliableInFlight = false
                    queue.addFirst(next)
                }
                main.postDelayed({ drain() }, 15)
                return
            }
            val ticket = synchronized(gate) { ++writeTicket }
            val gap = if (next.reliable) 400L else 12L
            main.postDelayed({ releaseWrite(ticket) }, gap)
        }
    }

    private fun <T> trimAudio(queue: ArrayDeque<T>, isAudio: (T) -> Boolean) {
        var audio = queue.count(isAudio)
        while (audio > 6) {
            val index = queue.indexOfFirst(isAudio)
            if (index < 0) return
            queue.removeAt(index)
            audio--
        }
    }

    private class Outgoing(val bytes: ByteArray, val reliable: Boolean)

    private class Notice(val device: BluetoothDevice, val bytes: ByteArray, val paced: Boolean)

    private enum class Role { Central, Peripheral }

    private companion object {
        const val TAG = "AirvoxBle"
    }
}
