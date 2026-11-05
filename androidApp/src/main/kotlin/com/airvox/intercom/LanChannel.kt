package com.airvox.intercom

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hotspot voice setup. Both phones are on the same Wi-Fi and there is no internet,
 * so this uses a local broadcast and a direct socket instead of Nearby or Supabase.
 * The same port and beacon are used by the iPhone.
 */
class LanChannel(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onJoined()

        fun onPresence(members: Map<String, String>)

        fun onSignal(message: JSONObject)

        fun onClosed(reason: String)
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val wifi = appContext.getSystemService(WifiManager::class.java)
    private val nsd = appContext.getSystemService(NsdManager::class.java)
    private val pool = Executors.newCachedThreadPool()
    private val running = AtomicBoolean(false)
    private val sockets = ConcurrentHashMap<String, Socket>()
    private val names = ConcurrentHashMap<String, String>()
    private val dialing = ConcurrentHashMap.newKeySet<String>()
    private val resolving = ArrayDeque<NsdServiceInfo>()
    private var resolveBusy = false
    private var lock: WifiManager.MulticastLock? = null
    private var server: ServerSocket? = null
    private var udp: DatagramSocket? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var serviceName = ""
    private var room = ""
    private var selfId = ""
    private var selfName = ""
    private var tcpPort = TCP_PORT

    fun connect(roomCode: String, peerId: String, displayName: String) {
        room = roomCode
        selfId = peerId
        selfName = displayName.ifBlank { "Phone" }.take(40)
        running.set(true)
        try {
            val listening = ServerSocket()
            listening.reuseAddress = true
            listening.soTimeout = 500
            listening.bind(InetSocketAddress(TCP_PORT))
            server = listening
            tcpPort = listening.localPort
        } catch (error: Exception) {
            Log.e(TAG, "Could not open the hotspot channel", error)
            close()
            main.post { listener.onClosed("Could not open the hotspot channel on this Wi-Fi.") }
            return
        }
        runCatching {
            lock = wifi?.createMulticastLock("airvox-hotspot")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            udp = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 500
                bind(InetSocketAddress(UDP_PORT))
            }
        }
        pool.execute { acceptLoop() }
        if (udp != null) {
            pool.execute { udpLoop() }
            pool.execute { beaconLoop() }
        }
        pool.execute { scanLoop() }
        publishService()
        main.post { listener.onJoined() }
    }

    fun rename(name: String) {
        val next = name.trim().take(40)
        if (next.isBlank() || !running.get()) return
        selfName = next
        sockets.forEach { (id, socket) -> writeHello(socket, id) }
        publish()
    }

    fun send(body: JSONObject) {
        if (!running.get()) return
        val to = body.optString("to")
        val targets = if (to.isBlank()) {
            sockets.values.toList()
        } else {
            listOfNotNull(sockets[to])
        }
        val bytes = (body.toString() + "\n").toByteArray(Charsets.UTF_8)
        targets.forEach { socket ->
            runCatching {
                synchronized(socket) {
                    socket.getOutputStream().write(bytes)
                    socket.getOutputStream().flush()
                }
            }
        }
    }

    fun close() {
        if (!running.getAndSet(false) && server == null && udp == null) return
        sockets.values.toList().forEach { runCatching { it.close() } }
        sockets.clear()
        runCatching { server?.close() }
        runCatching { udp?.close() }
        stopService()
        server = null
        udp = null
        runCatching {
            if (lock?.isHeld == true) lock?.release()
        }
        lock = null
        pool.shutdownNow()
    }

    private fun acceptLoop() {
        val listening = server ?: return
        while (running.get()) {
            val socket = try {
                listening.accept()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                break
            }
            socket.tcpNoDelay = true
            socket.keepAlive = true
            pool.execute { readLoop(socket, announced = false) }
        }
    }

    private fun udpLoop() {
        val socket = udp ?: return
        val buffer = ByteArray(1024)
        while (running.get()) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                break
            }
            val text = runCatching {
                String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
            }.getOrNull() ?: continue
            val json = runCatching { JSONObject(text) }.getOrNull() ?: continue
            if (json.optString("kind") != "beacon") continue
            if (json.optString("room") != room) continue
            val from = json.optString("from")
            val port = json.optInt("port")
            if (from.isBlank() || from == selfId || port !in 1..65535) continue
            val heard = json.optString("name").ifBlank { "Phone" }
            if (names[from] != heard) {
                names[from] = heard
                if (sockets.containsKey(from)) publish()
            }
            dial(from, packet.address, port)
        }
    }

    private fun beaconLoop() {
        while (running.get()) {
            val body = JSONObject()
                .put("kind", "beacon")
                .put("room", room)
                .put("from", selfId)
                .put("name", selfName)
                .put("port", tcpPort)
                .toString()
                .toByteArray(Charsets.UTF_8)
            val socket = udp
            if (socket != null) {
                broadcastAddresses().forEach { address ->
                    runCatching {
                        socket.send(DatagramPacket(body, body.size, address, UDP_PORT))
                    }
                }
            }
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun dial(from: String, address: InetAddress, port: Int) {
        if (selfId <= from) return
        if (sockets.containsKey(from) || !dialing.add(from)) return
        pool.execute {
            try {
                if (!running.get() || sockets.containsKey(from)) return@execute
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.connect(InetSocketAddress(address, port), 2000)
                if (!running.get()) {
                    socket.close()
                    return@execute
                }
                sockets[from] = socket
                writeHello(socket, from)
                publish()
                readLoop(socket, announced = true)
            } catch (error: Exception) {
                Log.w(TAG, "Could not reach $from: ${error.message}")
            } finally {
                dialing.remove(from)
            }
        }
    }

    private fun readLoop(socket: Socket, announced: Boolean) {
        val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
        var peer: String? = if (announced) sockets.entries.firstOrNull { it.value === socket }?.key else null
        try {
            while (running.get()) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
                val from = json.optString("from")
                if (from == selfId) break
                val foundRoom = json.optString("room")
                if (foundRoom.isNotBlank() && foundRoom != room) break
                val incomingName = json.optString("name")
                if (from.isNotBlank() && incomingName.isNotBlank() && names[from] != incomingName) {
                    names[from] = incomingName
                    if (peer != null) publish()
                }
                if (from.isNotBlank() && peer == null) {
                    val keep = if (selfId > from) announced else !announced
                    if (!keep) break
                    peer = from
                    names[from] = incomingName.ifBlank { names[from] ?: "Phone" }
                    val existing = sockets.putIfAbsent(from, socket)
                    if (existing != null && existing !== socket) break
                    if (!announced) writeHello(socket, from)
                    runCatching { socket.soTimeout = 0 }
                    publish()
                }
                if (json.optString("kind") == "beacon") continue
                main.post { listener.onSignal(json) }
            }
        } catch (_: Exception) {
            // The other phone left the hotspot.
        } finally {
            runCatching { socket.close() }
            val id = peer
            if (id != null && sockets.remove(id, socket)) publish()
        }
    }

    private fun writeHello(socket: Socket, to: String) {
        write(
            socket,
            JSONObject()
                .put("kind", "hello")
                .put("from", selfId)
                .put("to", to)
                .put("name", selfName)
                .put("room", room),
        )
    }

    private fun write(socket: Socket, body: JSONObject) {
        val bytes = (body.toString() + "\n").toByteArray(Charsets.UTF_8)
        synchronized(socket) {
            socket.getOutputStream().write(bytes)
            socket.getOutputStream().flush()
        }
    }

    private fun publish() {
        val members = linkedMapOf(selfId to selfName)
        sockets.keys.forEach { id -> members[id] = names[id] ?: "Phone" }
        main.post { listener.onPresence(members) }
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val found = linkedSetOf<InetAddress>()
        runCatching { found.add(InetAddress.getByName("255.255.255.255")) }
        runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence().forEach { nif ->
                if (!nif.isUp || nif.isLoopback) return@forEach
                nif.interfaceAddresses.forEach { address ->
                    address.broadcast?.let { found.add(it) }
                }
            }
        }
        return found.toList()
    }

    private companion object {
        const val TAG = "AirvoxLan"
        const val UDP_PORT = 45832
        const val TCP_PORT = 45832
        const val SERVICE_TYPE = "_airvox-lan._tcp."
    }

    private fun scanLoop() {
        while (running.get()) {
            val hosts = candidateHosts()
            hosts.chunked(40).forEach { batch ->
                if (!running.get()) return
                val latch = CountDownLatch(batch.size)
                batch.forEach { host ->
                    pool.execute {
                        try {
                            probe(host)
                        } finally {
                            latch.countDown()
                        }
                    }
                }
                latch.await(3, TimeUnit.SECONDS)
            }
            try {
                Thread.sleep(if (sockets.isEmpty()) 1000 else 4000)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun probe(host: String) {
        if (!running.get() || sockets.values.any { it.inetAddress?.hostAddress == host }) return
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.connect(InetSocketAddress(host, TCP_PORT), 300)
        } catch (_: Exception) {
            runCatching { socket.close() }
            return
        }
        if (!running.get()) {
            runCatching { socket.close() }
            return
        }
        socket.soTimeout = 2000
        runCatching { writeHello(socket, "") }
        readLoop(socket, announced = true)
    }

    private fun candidateHosts(): List<String> {
        val hosts = linkedSetOf<String>()
        runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence().forEach { nif ->
                if (!nif.isUp || nif.isLoopback || !isWifi(nif.name.orEmpty())) return@forEach
                nif.inetAddresses.asSequence().filterIsInstance<Inet4Address>().forEach { addr ->
                    if (!isPrivate(addr)) return@forEach
                    val text = addr.hostAddress ?: return@forEach
                    val prefix = text.substringBeforeLast('.')
                    val self = text.substringAfterLast('.').toIntOrNull() ?: return@forEach
                    for (n in 1..254) {
                        if (n != self) hosts.add("$prefix.$n")
                    }
                }
            }
        }
        return hosts.toList()
    }

    private fun isWifi(name: String): Boolean {
        val lower = name.lowercase()
        if (lower.contains("rmnet") || lower.contains("ccmni") || lower.contains("pdp") || lower.contains("wwan")) {
            return false
        }
        return true
    }

    private fun isPrivate(addr: Inet4Address): Boolean {
        val bytes = addr.address
        val first = bytes[0].toInt() and 255
        val second = bytes[1].toInt() and 255
        return first == 10 || (first == 192 && second == 168) || (first == 172 && second in 16..31)
    }

    private fun publishService() {
        val manager = nsd ?: return
        serviceName = "ax${selfId.replace("-", "").take(10)}"
        val info = NsdServiceInfo().apply {
            serviceName = this@LanChannel.serviceName
            serviceType = SERVICE_TYPE
            port = tcpPort
            setAttribute("room", room)
            setAttribute("from", selfId)
            setAttribute("name", selfName)
        }
        val registered = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                serviceName = serviceInfo.serviceName
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Hotspot announce failed: $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = registered
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registered) }
        val found = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!running.get() || serviceInfo.serviceName == serviceName) return
                synchronized(resolving) { resolving.add(serviceInfo) }
                pumpResolve()
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
        }
        discovery = found
        runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, found) }
    }

    private fun pumpResolve() {
        val manager = nsd ?: return
        val next = synchronized(resolving) {
            if (resolveBusy) return
            val item = resolving.removeFirstOrNull() ?: return
            resolveBusy = true
            item
        }
        runCatching {
            manager.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    resolveBusy = false
                    pumpResolve()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    resolveBusy = false
                    val attributes = serviceInfo.attributes
                    val foundRoom = attributes["room"]?.toString(Charsets.UTF_8).orEmpty()
                    val from = attributes["from"]?.toString(Charsets.UTF_8).orEmpty()
                    val host = serviceInfo.host
                    if (foundRoom == room && from.isNotBlank() && from != selfId && host != null) {
                        names[from] = attributes["name"]?.toString(Charsets.UTF_8) ?: "Phone"
                        dial(from, host, serviceInfo.port)
                    }
                    pumpResolve()
                }
            })
        }.onFailure {
            resolveBusy = false
        }
    }

    private fun stopService() {
        val manager = nsd
        registration?.let { listener -> runCatching { manager?.unregisterService(listener) } }
        discovery?.let { listener -> runCatching { manager?.stopServiceDiscovery(listener) } }
        registration = null
        discovery = null
    }
}
