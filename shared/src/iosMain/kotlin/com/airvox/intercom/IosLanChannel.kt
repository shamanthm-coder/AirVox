package com.airvox.intercom

import kotlin.concurrent.Volatile
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import platform.Foundation.NSLock
import platform.Foundation.NSNetService
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_time
import platform.darwin.dispatch_after
import platform.darwin.DISPATCH_TIME_NOW
import platform.posix.AF_INET
import platform.posix.IFF_LOOPBACK
import platform.posix.IFF_UP
import platform.posix.INADDR_ANY
import platform.posix.IPPROTO_TCP
import platform.posix.SOCK_DGRAM
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_BROADCAST
import platform.posix.SO_NOSIGPIPE
import platform.posix.SO_RCVTIMEO
import platform.posix.SO_REUSEADDR
import platform.posix.SO_REUSEPORT
import platform.posix.TCP_NODELAY
import platform.posix.F_SETFL
import platform.posix.O_NONBLOCK
import platform.posix.POLLOUT
import platform.posix.fcntl
import platform.posix.getsockopt
import platform.posix.poll
import platform.posix.bind
import platform.posix.close
import platform.posix.pollfd
import platform.posix.sleep
import platform.posix.close
import platform.posix.connect
import platform.darwin.freeifaddrs
import platform.darwin.getifaddrs
import platform.posix.getpeername
import platform.posix.getsockname
import platform.darwin.ifaddrs
import platform.posix.listen
import platform.posix.memset
import platform.posix.recv
import platform.posix.recvfrom
import platform.posix.send
import platform.posix.sendto
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.timeval

/**
 * Finds the other phone on the same Wi-Fi hotspot. No internet is required.
 * Android listens on the same UDP port and speaks the same beacon.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosLanChannel(
    private val onSignal: (String) -> Unit,
    private val onLost: (String) -> Unit,
    private val onFailed: (String) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val gate = NSLock()
    private val links = linkedMapOf<String, Int>()
    private val dialing = linkedSetOf<String>()

    @Volatile
    private var running = false
    private var room = ""
    private var selfId = ""
    private var selfName = ""
    private var tcpPort = 0
    private var tcpFd = -1
    private var udpFd = -1
    private var publisher: NSNetService? = null

    fun start(roomCode: String, peerId: String, displayName: String) {
        room = roomCode
        selfId = peerId
        selfName = displayName.ifBlank { "iPhone" }.take(40)
        val tcp = openTcp()
        if (tcp < 0) {
            onFailed("Could not open the hotspot channel on this Wi-Fi.")
            return
        }
        tcpFd = tcp
        tcpPort = TCP_PORT
        udpFd = openUdp()
        running = true
        publishService()
        background { acceptLoop() }
        if (udpFd >= 0) {
            background { udpLoop() }
            background { beaconLoop() }
        }
        background { scanLoop() }
    }

    fun rename(name: String) {
        val next = name.trim().take(40)
        if (next.isBlank() || !running) return
        selfName = next
    }

    fun send(text: String) {
        if (!running) return
        val to = runCatching { json.parseToJsonElement(text).jsonObject.string("to") }.getOrNull().orEmpty()
        val line = text.encodeToByteArray() + "\n".encodeToByteArray()
        gate.lock()
        val targets = if (to.isBlank()) links.values.toList() else listOfNotNull(links[to])
        gate.unlock()
        targets.forEach { fd -> writeAll(fd, line) }
    }

    fun close() {
        running = false
        gate.lock()
        val open = links.values.toList()
        links.clear()
        val tcp = tcpFd
        val udp = udpFd
        tcpFd = -1
        udpFd = -1
        gate.unlock()
        open.forEach { close(it) }
        if (tcp >= 0) close(tcp)
        if (udp >= 0) close(udp)
        publisher?.stop()
        publisher = null
    }

    private fun acceptLoop() {
        val listening = tcpFd
        while (running) {
            val fd = platform.posix.accept(listening, null, null)
            if (fd < 0) continue
            prepareStream(fd)
            // An accepted socket copies the listener's one-second wait. Left on, the link to the
            // other phone is dropped whenever nobody sends anything for a second.
            receiveTimeout(fd, seconds = 0)
            background { readLoop(fd, known = null, outbound = false) }
        }
    }

    private fun udpLoop() {
        val fd = udpFd
        val buffer = ByteArray(1024)
        while (running) {
            memScoped {
                val sender = alloc<sockaddr_in>()
                val length = alloc<UIntVar>()
                length.value = sizeOf<sockaddr_in>().convert()
                val count = buffer.usePinned { pinned ->
                    recvfrom(
                        fd,
                        pinned.addressOf(0),
                        buffer.size.convert(),
                        0,
                        sender.ptr.reinterpret(),
                        length.ptr,
                    )
                }
                if (count <= 0) return@memScoped
                val text = buffer.decodeToString(0, count.toInt())
                val message = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return@memScoped
                if (message.string("kind") != "beacon") return@memScoped
                if (message.string("room") != room) return@memScoped
                val from = message.string("from").orEmpty()
                val port = message.int("port")
                if (from.isBlank() || from == selfId || port !in 1..65535) return@memScoped
                dial(from, sender.sin_addr.s_addr, port)
            }
        }
    }

    private fun beaconLoop() {
        if (!running) return
        sendBeacon()
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 1_000_000_000), backgroundQueue()) {
            beaconLoop()
        }
    }

    private fun sendBeacon() {
        val fd = udpFd
        if (fd < 0) return
        val text = buildJsonObject {
            put("kind", "beacon")
            put("room", room)
            put("from", selfId)
            put("name", selfName)
            put("port", tcpPort)
        }.toString()
        val bytes = text.encodeToByteArray()
        memScoped {
            val dest = alloc<sockaddr_in>()
            memset(dest.ptr, 0, sizeOf<sockaddr_in>().convert())
            dest.sin_len = sizeOf<sockaddr_in>().toUByte()
            dest.sin_family = AF_INET.convert()
            dest.sin_port = htons(UDP_PORT)
            dest.sin_addr.s_addr = htonl(0xFFFFFFFFu)
            bytes.usePinned { pinned ->
                sendto(
                    fd,
                    pinned.addressOf(0),
                    bytes.size.convert(),
                    0,
                    dest.ptr.reinterpret(),
                    sizeOf<sockaddr_in>().convert(),
                )
            }
        }
    }

    private fun dial(from: String, address: UInt, port: Int) {
        if (selfId <= from) return
        gate.lock()
        val busy = links.containsKey(from) || !dialing.add(from)
        gate.unlock()
        if (busy) return
        background {
            val fd = socket(AF_INET, SOCK_STREAM, 0)
            var opened = false
            if (fd >= 0) {
                prepareStream(fd)
                memScoped {
                    val dest = alloc<sockaddr_in>()
                    memset(dest.ptr, 0, sizeOf<sockaddr_in>().convert())
                    dest.sin_len = sizeOf<sockaddr_in>().toUByte()
                    dest.sin_family = AF_INET.convert()
                    dest.sin_port = htons(port)
                    dest.sin_addr.s_addr = address
                    opened = connect(fd, dest.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0
                }
            }
            gate.lock()
            val keep = opened && running && !links.containsKey(from)
            gate.unlock()
            if (!keep) {
                gate.lock()
                dialing.remove(from)
                gate.unlock()
                if (fd >= 0) close(fd)
                return@background
            }
            // The link counts only once the other phone answers. Two attempts can be open at
            // the same moment, and the other phone keeps one and closes the other.
            receiveTimeout(fd, seconds = 3)
            writeHello(fd, from)
            readLoop(fd, known = null, outbound = true)
            gate.lock()
            dialing.remove(from)
            gate.unlock()
        }
    }

    private fun readLoop(fd: Int, known: String?, outbound: Boolean) {
        var peer = known
        val buffer = ByteArray(4096)
        val pending = StringBuilder()
        while (running) {
            val count = buffer.usePinned { pinned ->
                recv(fd, pinned.addressOf(0), buffer.size.convert(), 0)
            }
            if (count <= 0) break
            pending.append(buffer.decodeToString(0, count.toInt()))
            while (true) {
                val breakAt = pending.indexOf('\n')
                if (breakAt < 0) break
                val line = pending.substring(0, breakAt).trim()
                val rest = pending.substring(breakAt + 1)
                pending.setLength(0)
                pending.append(rest)
                if (line.isEmpty()) continue
                val message = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                val from = message.string("from").orEmpty()
                if (from == selfId) break
                val foundRoom = message.string("room").orEmpty()
                if (foundRoom.isNotBlank() && foundRoom != room) break
                if (from.isNotBlank() && peer == null) {
                    val keepSocket = if (selfId > from) outbound else !outbound
                    if (!keepSocket) {
                        // That phone found this one first, but this side has to open the link. Call it back.
                        if (!outbound) peerAddress(fd)?.let { dial(from, it, TCP_PORT) }
                        break
                    }
                    peer = from
                    gate.lock()
                    val existing = links[from]
                    if (existing == null && running) links[from] = fd
                    val stored = links[from] == fd
                    gate.unlock()
                    if (!stored) {
                        peer = null
                        break
                    }
                    receiveTimeout(fd, seconds = 0)
                    if (!outbound) writeHello(fd, from)
                }
                if (message.string("kind") == "beacon") continue
                onSignal(line)
            }
            if (pending.length > 65_536) break
        }
        close(fd)
        val id = peer ?: return
        gate.lock()
        val current = links[id]
        if (current == fd) links.remove(id)
        gate.unlock()
        if (current == fd) onLost(id)
    }

    private fun peerAddress(fd: Int): UInt? = memScoped {
        val remote = alloc<sockaddr_in>()
        val length = alloc<UIntVar>()
        length.value = sizeOf<sockaddr_in>().convert()
        if (getpeername(fd, remote.ptr.reinterpret(), length.ptr) != 0) return null
        remote.sin_addr.s_addr.takeIf { it != 0u }
    }

    private fun writeHello(fd: Int, to: String) {
        val text = buildJsonObject {
            put("kind", "hello")
            put("from", selfId)
            put("to", to)
            put("name", selfName)
            put("room", room)
        }.toString()
        writeAll(fd, (text + "\n").encodeToByteArray())
    }

    private fun writeAll(fd: Int, bytes: ByteArray) {
        var sent = 0
        while (sent < bytes.size) {
            val count = bytes.usePinned { pinned ->
                send(fd, pinned.addressOf(sent), (bytes.size - sent).convert(), 0)
            }
            if (count <= 0) return
            sent += count.toInt()
        }
    }

    private fun openTcp(): Int {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        if (fd < 0) return -1
        if (!bindAny(fd, TCP_PORT) || listen(fd, 8) != 0) {
            close(fd)
            return -1
        }
        receiveTimeout(fd)
        return fd
    }

    private fun openUdp(): Int {
        val fd = socket(AF_INET, SOCK_DGRAM, 0)
        if (fd < 0) return -1
        memScoped {
            val yes = alloc<IntVar>()
            yes.value = 1
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, yes.ptr, sizeOf<IntVar>().convert())
            setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, yes.ptr, sizeOf<IntVar>().convert())
            setsockopt(fd, SOL_SOCKET, SO_BROADCAST, yes.ptr, sizeOf<IntVar>().convert())
        }
        if (!bindAny(fd, UDP_PORT)) {
            close(fd)
            return -1
        }
        receiveTimeout(fd)
        return fd
    }

    private fun bindAny(fd: Int, port: Int): Boolean = memScoped {
        val addr = alloc<sockaddr_in>()
        memset(addr.ptr, 0, sizeOf<sockaddr_in>().convert())
        addr.sin_len = sizeOf<sockaddr_in>().toUByte()
        addr.sin_family = AF_INET.convert()
        addr.sin_port = htons(port)
        addr.sin_addr.s_addr = htonl(INADDR_ANY)
        bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0
    }

    private fun localPort(fd: Int): Int = memScoped {
        val addr = alloc<sockaddr_in>()
        val length = alloc<UIntVar>()
        length.value = sizeOf<sockaddr_in>().convert()
        if (getsockname(fd, addr.ptr.reinterpret(), length.ptr) != 0) return 0
        ntohs(addr.sin_port).toInt()
    }

    private fun prepareStream(fd: Int) {
        memScoped {
            val yes = alloc<IntVar>()
            yes.value = 1
            setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, yes.ptr, sizeOf<IntVar>().convert())
            setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, yes.ptr, sizeOf<IntVar>().convert())
        }
    }

    /** 0 waits for as long as it takes. */
    private fun receiveTimeout(fd: Int, seconds: Int = 1) {
        memScoped {
            val wait = alloc<timeval>()
            wait.tv_sec = seconds.convert()
            wait.tv_usec = 0
            setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, wait.ptr, sizeOf<timeval>().convert())
        }
    }

    private fun background(block: () -> Unit) {
        dispatch_async(backgroundQueue(), block)
    }

    private fun backgroundQueue() = dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.convert(), 0u)

    private fun htons(value: Int): UShort {
        val port = value and 0xFFFF
        return (((port and 0xFF) shl 8) or ((port shr 8) and 0xFF)).toUShort()
    }

    private fun htonl(value: UInt): UInt {
        val word = value.toLong() and 0xFFFFFFFF
        return (
            ((word and 0xFF) shl 24) or
                ((word and 0xFF00) shl 8) or
                ((word and 0xFF0000) shr 8) or
                ((word shr 24) and 0xFF)
            ).toUInt()
    }

    private fun ntohs(value: UShort): Int {
        val port = value.toInt() and 0xFFFF
        return ((port and 0xFF) shl 8) or ((port shr 8) and 0xFF)
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(key: String): Int = this[key]?.jsonPrimitive?.intOrNull ?: 0

    private fun publishService() {
        val service = NSNetService(
            domain = "local.",
            type = "_airvox-lan._tcp.",
            name = "airvox-${selfId.take(8)}",
            port = TCP_PORT,
        )
        publisher = service
        service.publish()
    }

    private fun scanLoop() {
        while (running) {
            localSubnets().forEach { subnet ->
                val hosts = (1..254).filter { it != subnet.self }.map { host ->
                    packAddress(subnet.first, subnet.second, subnet.third, host)
                }
                // A few at a time. A burst of probes to empty addresses floods the hotspot with
                // broadcasts, and the phone hosting it then drops its replies to this iPhone.
                hosts.chunked(16).forEach { batch ->
                    if (!running) return
                    probeBatch(batch)
                }
            }
            platform.posix.sleep(if (links.isEmpty()) 2u else 15u)
        }
    }

    private fun probeBatch(addresses: List<UInt>) {
        val fds = IntArray(addresses.size) { index ->
            val fd = socket(AF_INET, SOCK_STREAM, 0)
            if (fd >= 0) {
                prepareStream(fd)
                fcntl(fd, F_SETFL, O_NONBLOCK)
                memScoped {
                    val dest = alloc<sockaddr_in>()
                    memset(dest.ptr, 0, sizeOf<sockaddr_in>().convert())
                    dest.sin_len = sizeOf<sockaddr_in>().toUByte()
                    dest.sin_family = AF_INET.convert()
                    dest.sin_port = htons(TCP_PORT)
                    dest.sin_addr.s_addr = addresses[index]
                    connect(fd, dest.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
                }
            }
            fd
        }
        memScoped {
            val watched = allocArray<pollfd>(fds.size)
            for (index in fds.indices) {
                watched[index].fd = fds[index]
                watched[index].events = POLLOUT.convert()
            }
            poll(watched, fds.size.convert(), 300)
        }
        fds.forEach { fd ->
            if (fd < 0) return@forEach
            if (running && socketReady(fd)) {
                fcntl(fd, F_SETFL, 0)
                receiveTimeout(fd, seconds = 3)
                writeHello(fd, "")
                background { readLoop(fd, known = null, outbound = true) }
            } else {
                close(fd)
            }
        }
    }

    private fun socketReady(fd: Int): Boolean = memScoped {
        val error = alloc<IntVar>()
        val length = alloc<UIntVar>()
        length.value = sizeOf<IntVar>().convert()
        getsockopt(fd, SOL_SOCKET, SOCKET_ERROR, error.ptr, length.ptr) == 0 && error.value == 0
    }

    private fun localSubnets(): List<Subnet> {
        val found = linkedMapOf<String, Subnet>()
        // The Wi-Fi address itself. With no internet on the hotspot the iPhone keeps mobile data
        // as its main route, so asking "which address reaches the internet" misses the hotspot.
        interfaceSubnets().forEach { subnet ->
            found["${subnet.first}.${subnet.second}.${subnet.third}.${subnet.self}"] = subnet
        }
        if (found.isNotEmpty()) return found.values.toList()
        listOf(
            packAddress(8, 8, 8, 8),
            packAddress(192, 168, 43, 1),
            packAddress(192, 168, 44, 1),
            packAddress(192, 168, 0, 1),
            packAddress(192, 168, 1, 1),
            packAddress(172, 20, 10, 1),
            packAddress(10, 0, 0, 1),
        ).forEach { destination ->
            val subnet = addressToward(destination) ?: return@forEach
            found["${subnet.first}.${subnet.second}.${subnet.third}.${subnet.self}"] = subnet
        }
        return found.values.toList()
    }

    private fun addressToward(destination: UInt): Subnet? = memScoped {
        val fd = socket(AF_INET, SOCK_DGRAM, 0)
        if (fd < 0) return null
        val remote = alloc<sockaddr_in>()
        memset(remote.ptr, 0, sizeOf<sockaddr_in>().convert())
        remote.sin_len = sizeOf<sockaddr_in>().toUByte()
        remote.sin_family = AF_INET.convert()
        remote.sin_port = htons(9)
        remote.sin_addr.s_addr = destination
        val connected = connect(fd, remote.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0
        val local = alloc<sockaddr_in>()
        val length = alloc<UIntVar>()
        length.value = sizeOf<sockaddr_in>().convert()
        val named = connected && getsockname(fd, local.ptr.reinterpret(), length.ptr) == 0
        val raw = if (named) local.sin_addr.s_addr else 0u
        close(fd)
        if (!named || raw == 0u) return null
        subnetOf(raw)
    }

    private fun interfaceSubnets(): List<Subnet> = memScoped {
        val found = ArrayList<Subnet>()
        val list = alloc<CPointerVar<ifaddrs>>()
        if (getifaddrs(list.ptr) != 0) return found
        var cursor = list.value
        while (cursor != null) {
            val entry = cursor.pointed
            val flags = entry.ifa_flags.toInt()
            val address = entry.ifa_addr
            val name = entry.ifa_name?.toKString().orEmpty()
            // pdp_ip is mobile data. Other phones are never on it.
            if (address != null &&
                flags and IFF_UP != 0 &&
                flags and IFF_LOOPBACK == 0 &&
                !name.startsWith("pdp_ip") &&
                address.pointed.sa_family.toInt() == AF_INET
            ) {
                subnetOf(address.reinterpret<sockaddr_in>().pointed.sin_addr.s_addr)?.let { found.add(it) }
            }
            cursor = entry.ifa_next
        }
        freeifaddrs(list.value)
        found
    }

    private fun subnetOf(raw: UInt): Subnet? {
        val first = (raw and 0xFFu).toInt()
        val second = ((raw shr 8) and 0xFFu).toInt()
        val third = ((raw shr 16) and 0xFFu).toInt()
        val fourth = ((raw shr 24) and 0xFFu).toInt()
        if (!isPrivate(first, second) || fourth == 0) return null
        return Subnet(first, second, third, fourth)
    }

    private fun isPrivate(first: Int, second: Int): Boolean {
        return first == 10 || (first == 192 && second == 168) || (first == 172 && second in 16..31)
    }

    private fun packAddress(first: Int, second: Int, third: Int, fourth: Int): UInt {
        return first.toUInt() or
            (second.toUInt() shl 8) or
            (third.toUInt() shl 16) or
            (fourth.toUInt() shl 24)
    }

    private class Subnet(val first: Int, val second: Int, val third: Int, val self: Int)

    private companion object {
        const val UDP_PORT = 45832
        const val TCP_PORT = 45832
        const val SOCKET_ERROR = 0x1007
    }
}
