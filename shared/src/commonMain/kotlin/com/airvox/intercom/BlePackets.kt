package com.airvox.intercom

/**
 * One Bluetooth packet both phones understand.
 * The first four bytes are the type, an end flag, and a sequence. The rest is the piece.
 */
object BlePackets {
    const val SERVICE = "f3b1c2d0-7a44-4e2a-9c11-6d8e5f0a1b23"
    const val DATA = "f3b1c2d0-7a44-4e2a-9c11-6d8e5f0a1b24"
    const val TEXT: Byte = 0x01
    const val AUDIO: Byte = 0xA1.toByte()

    fun nameFor(peerId: String): String {
        val token = peerId.filter { it.isLetterOrDigit() }.take(8).lowercase()
        return "av$token"
    }

    fun pack(type: Byte, payload: ByteArray, maxPacket: Int): List<ByteArray> {
        val room = (maxPacket - 4).coerceAtLeast(8)
        if (payload.isEmpty()) return listOf(frame(type, end = true, seq = 0, chunk = ByteArray(0)))
        val packets = ArrayList<ByteArray>()
        var offset = 0
        var seq = 0
        while (offset < payload.size) {
            val end = (offset + room).coerceAtMost(payload.size)
            packets += frame(type, end = end == payload.size, seq = seq, chunk = payload.copyOfRange(offset, end))
            seq = (seq + 1) and 0xFFFF
            offset = end
        }
        return packets
    }

    /** Each piece stands alone, so a lost piece does not throw away the rest of the voice. */
    fun packSolo(type: Byte, payload: ByteArray, maxPacket: Int): List<ByteArray> {
        val room = (maxPacket - 4).coerceAtLeast(8)
        if (payload.isEmpty()) return emptyList()
        val packets = ArrayList<ByteArray>()
        var offset = 0
        while (offset < payload.size) {
            val end = (offset + room).coerceAtMost(payload.size)
            packets += frame(type, end = true, seq = 0, chunk = payload.copyOfRange(offset, end))
            offset = end
        }
        return packets
    }

    private fun frame(type: Byte, end: Boolean, seq: Int, chunk: ByteArray): ByteArray {
        val packet = ByteArray(4 + chunk.size)
        packet[0] = type
        packet[1] = if (end) 1 else 0
        packet[2] = (seq shr 8).toByte()
        packet[3] = seq.toByte()
        chunk.copyInto(packet, 4)
        return packet
    }

    class Assembler {
        private var type: Byte = 0
        private var body = ByteArray(0)
        private var expect = 0

        fun push(packet: ByteArray): Pair<Byte, ByteArray>? {
            if (packet.size < 4) return null
            val kind = packet[0]
            val end = packet[1].toInt() and 0x01 != 0
            val seq = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
            // A voice piece is whole on its own. It can land between the pieces of a longer
            // message, and must not make the phone forget the message it is collecting.
            if (seq == 0 && end && kind != type && expect > 0) {
                return kind to packet.copyOfRange(4, packet.size)
            }
            if (seq == 0) {
                type = kind
                body = ByteArray(0)
                expect = 0
            }
            if (kind != type || seq != expect) {
                body = ByteArray(0)
                expect = 0
                return null
            }
            body += packet.copyOfRange(4, packet.size)
            expect += 1
            if (!end) return null
            val done = body
            body = ByteArray(0)
            expect = 0
            return kind to done
        }
    }
}
