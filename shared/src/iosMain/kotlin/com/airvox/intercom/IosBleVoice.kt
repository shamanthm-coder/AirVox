package com.airvox.intercom

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPlayerNode
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionAllowBluetooth
import platform.AVFAudio.AVAudioSessionCategoryOptionAllowBluetoothA2DP
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.AVAudioSessionPortAirPlay
import platform.AVFAudio.AVAudioSessionPortBluetoothA2DP
import platform.AVFAudio.AVAudioSessionPortBluetoothHFP
import platform.AVFAudio.AVAudioSessionPortBluetoothLE
import platform.AVFAudio.AVAudioSessionPortCarAudio
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.AVAudioSessionPortHeadphones
import platform.AVFAudio.AVAudioSessionPortOverrideNone
import platform.AVFAudio.AVAudioSessionPortOverrideSpeaker
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionRouteChangeReasonCategoryChange
import platform.AVFAudio.AVAudioSessionRouteChangeReasonKey
import platform.AVFAudio.AVAudioSessionRouteChangeReasonOverride
import platform.AVFAudio.currentRoute
import platform.AVFAudio.setActive
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time

/**
 * 8 kHz voice for the Bluetooth channel. The frames match the Android phone.
 */
@OptIn(ExperimentalForeignApi::class)
class IosBleVoice(
    private val onFrame: (ByteArray) -> Unit,
) {
    private val engine = AVAudioEngine()
    private val player = AVAudioPlayerNode()
    private val voiceFormat = AVAudioFormat(standardFormatWithSampleRate = 8000.0, channels = 1u)
    private var capturing = false
    private var playRemote = true
    private var started = false
    private var wired = false
    private var carry = ByteArray(0)
    private var played = 0
    private var queued = 0
    private var playRound = 0
    private var holdTicket = 0
    private val held = ArrayList<AVAudioPCMBuffer>()
    private var routeObserver: Any? = null

    fun mutePlayback(muted: Boolean) {
        playRemote = !muted
    }

    fun enqueue(frame: ByteArray) {
        if (!playRemote || frame.isEmpty()) return
        ensureRunning()
        played += 1
        if (played % 50 == 1) println("AirvoxBle: playing voice, engine running: ${engine.running}, player playing: ${player.playing}")
        val buffer = AVAudioPCMBuffer(pCMFormat = voiceFormat, frameCapacity = frame.size.toUInt()) ?: return
        buffer.frameLength = frame.size.toUInt()
        val channel = buffer.floatChannelData?.get(0) ?: return
        for (index in frame.indices) {
            channel[index] = decodeMulaw(frame[index]) / 32768f
        }
        // Bluetooth hands voice over in uneven clumps. Played the moment it lands, the speaker
        // runs dry between clumps and crackles. When nothing is waiting, hold a few frames first.
        if (queued == 0 && held.size + 1 < PREFILL_FRAMES) {
            held.add(buffer)
            if (held.size == 1) {
                val ticket = ++holdTicket
                dispatch_after(dispatch_time(DISPATCH_TIME_NOW, PREFILL_WAIT_NS), dispatch_get_main_queue()) {
                    if (ticket == holdTicket) flushHeld()
                }
            }
            return
        }
        flushHeld()
        schedule(buffer)
    }

    private fun flushHeld() {
        holdTicket += 1
        if (held.isEmpty()) return
        val waiting = held.toList()
        held.clear()
        waiting.forEach { schedule(it) }
    }

    private fun schedule(buffer: AVAudioPCMBuffer) {
        val round = playRound
        queued += 1
        player.scheduleBuffer(buffer) {
            dispatch_async(dispatch_get_main_queue()) {
                if (round == playRound && queued > 0) queued -= 1
            }
        }
    }

    /** The player dropped what it was holding. Start counting again. */
    private fun resetQueue() {
        playRound += 1
        queued = 0
        held.clear()
        holdTicket += 1
    }

    fun startCapture() {
        if (capturing) return
        ensureRunning()
        val input = engine.inputNode
        val format = input.outputFormatForBus(0u)
        if (format.sampleRate <= 0.0) return
        capturing = true
        carry = ByteArray(0)
        // A tap added after the engine started for playback gets no microphone data.
        // Restart the engine with the tap in place.
        // Stop the player too. It keeps saying it is playing after the engine stops, so it was
        // never started again, and nothing from the other phone was heard after the first talk.
        runCatching { player.stop() }
        resetQueue()
        runCatching { engine.stop() }
        runCatching { input.removeTapOnBus(0u) }
        input.installTapOnBus(0u, 1024u, format) { buffer, _ ->
            if (!capturing || buffer == null) return@installTapOnBus
            val channel = buffer.floatChannelData?.get(0) ?: return@installTapOnBus
            val count = buffer.frameLength.toInt()
            if (count <= 0) return@installTapOnBus
            // The exact ratio, not a whole number. A 44.1 kHz microphone is 5.5 samples per
            // voice sample, and rounding that to 5 sent more sound than the link can carry.
            val ratio = (format.sampleRate / 8000.0).coerceAtLeast(1.0)
            val samples = (count / ratio).toInt()
            if (samples <= 0) return@installTapOnBus
            val mulaw = ByteArray(samples)
            for (output in 0 until samples) {
                val from = (output * ratio).toInt().coerceAtMost(count - 1)
                val until = ((output + 1) * ratio).toInt().coerceIn(from + 1, count)
                var sum = 0f
                for (index in from until until) sum += channel[index]
                val pcm = ((sum / (until - from)) * 32767f).toInt().coerceIn(-32768, 32767)
                mulaw[output] = encodeMulaw(pcm)
            }
            emit(mulaw)
        }
        ensureRunning()
    }

    /**
     * The iPhone hands over a tenth of a second at once. Android sends 20 ms at a time.
     * Cut to the same 160 bytes, so each frame is one Bluetooth packet on both phones.
     */
    private fun emit(mulaw: ByteArray) {
        val all = if (carry.isEmpty()) mulaw else carry + mulaw
        var offset = 0
        while (all.size - offset >= FRAME_BYTES) {
            onFrame(all.copyOfRange(offset, offset + FRAME_BYTES))
            offset += FRAME_BYTES
        }
        carry = all.copyOfRange(offset, all.size)
    }

    fun stopCapture() {
        if (!capturing) return
        capturing = false
        runCatching { engine.inputNode.removeTapOnBus(0u) }
        ensureRunning()
    }

    fun stop() {
        stopCapture()
        if (!started) return
        started = false
        runCatching { player.stop() }
        resetQueue()
        runCatching { engine.stop() }
        routeObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        routeObserver = null
        runCatching { AVAudioSession.sharedInstance().setActive(false, null) }
    }

    /** Earbuds were switched on or off during the channel. Move the voice and the microphone with them. */
    private fun watchRoute() {
        if (routeObserver != null) return
        routeObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVAudioSessionRouteChangeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { note ->
            val reason = (note?.userInfo?.get(AVAudioSessionRouteChangeReasonKey) as? NSNumber)?.unsignedLongValue
            // These two are this class changing the route itself.
            if (reason == AVAudioSessionRouteChangeReasonOverride ||
                reason == AVAudioSessionRouteChangeReasonCategoryChange
            ) {
                return@addObserverForName
            }
            if (!started) return@addObserverForName
            runCatching { routeOutput() }
            if (capturing) {
                // The microphone changed, and its tap is tied to the old one's sample rate.
                capturing = false
                runCatching { engine.inputNode.removeTapOnBus(0u) }
                startCapture()
            } else {
                ensureRunning()
            }
        }
    }

    private fun ensureRunning() {
        if (!started) {
            val session = AVAudioSession.sharedInstance()
            session.setCategory(
                AVAudioSessionCategoryPlayAndRecord,
                AVAudioSessionModeDefault,
                // Without the Bluetooth options iOS never sends this channel's voice to earbuds.
                AVAudioSessionCategoryOptionDefaultToSpeaker or
                    AVAudioSessionCategoryOptionAllowBluetooth or
                    AVAudioSessionCategoryOptionAllowBluetoothA2DP,
                null,
            )
            session.setActive(true, null)
            routeOutput()
            watchRoute()
            // The microphone has to be part of the engine before it starts, or talking sends nothing.
            engine.inputNode
            // Both people can talk at once, so the speaker's sound reaches the microphone.
            // The iPhone's voice processing takes that echo out. It must be set before the start.
            if (ECHO_CANCEL) {
                val ok = runCatching { engine.inputNode.setVoiceProcessingEnabled(true, null) }.getOrDefault(false)
                println("AirvoxBle: echo cancelling on: $ok")
            }
            if (!wired) {
                engine.attachNode(player)
                engine.connect(player, engine.mainMixerNode, voiceFormat)
                wired = true
            }
            started = true
        }
        // Turning the microphone on can change the audio route, and iOS then stops the engine
        // without telling us. Start it again so voice from the other phone still plays.
        if (!engine.running) {
            AVAudioSession.sharedInstance().setActive(true, null)
            routeOutput()
            // The player's own "playing" flag survives an engine stop. Reset it, then start
            // it again once the engine runs, or scheduled voice is never played.
            runCatching { player.stop() }
            resetQueue()
            engine.prepare()
            val ok = engine.startAndReturnError(null)
            println("AirvoxBle: audio engine started: $ok")
            if (engine.running) player.play()
        }
        if (engine.running && !player.playing) player.play()
    }

    /**
     * Earbuds, a headset or the car when one is connected. Otherwise the loud speaker,
     * because a voice session on its own plays through the earpiece.
     */
    private fun routeOutput() {
        val session = AVAudioSession.sharedInstance()
        val external = session.currentRoute.outputs.any { port ->
            (port as? AVAudioSessionPortDescription)?.portType in EXTERNAL_OUTPUTS
        }
        session.overrideOutputAudioPort(
            if (external) AVAudioSessionPortOverrideNone else AVAudioSessionPortOverrideSpeaker,
            null,
        )
    }

    private fun encodeMulaw(sample: Int): Byte {
        val sign = if (sample < 0) 0x80 else 0
        var pcm = if (sample < 0) -sample else sample
        if (pcm > 32635) pcm = 32635
        pcm += 0x84
        var exponent = 7
        var mask = 0x4000
        while (exponent > 0 && pcm and mask == 0) {
            exponent--
            mask = mask shr 1
        }
        val mantissa = (pcm shr (exponent + 3)) and 0x0F
        return ((sign or (exponent shl 4) or mantissa).inv() and 0xFF).toByte()
    }

    private fun decodeMulaw(mulaw: Byte): Short {
        val value = mulaw.toInt().inv() and 0xFF
        val sign = value and 0x80
        val exponent = (value shr 4) and 0x07
        val mantissa = value and 0x0F
        var sample = (((mantissa shl 3) + 0x84) shl exponent) - 0x84
        if (sign != 0) sample = -sample
        return sample.toShort()
    }

    private companion object {
        const val FRAME_BYTES = 160
        const val PREFILL_FRAMES = 4
        const val PREFILL_WAIT_NS = 120_000_000L

        /** Set to false if the iPhone speaker becomes too quiet with echo cancelling on. */
        const val ECHO_CANCEL = true

        val EXTERNAL_OUTPUTS = setOf(
            AVAudioSessionPortBluetoothA2DP,
            AVAudioSessionPortBluetoothHFP,
            AVAudioSessionPortBluetoothLE,
            AVAudioSessionPortHeadphones,
            AVAudioSessionPortCarAudio,
            AVAudioSessionPortAirPlay,
        )
    }
}
