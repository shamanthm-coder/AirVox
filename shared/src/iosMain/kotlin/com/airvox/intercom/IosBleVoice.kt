package com.airvox.intercom

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPlayerNode
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.AVAudioSessionPortOverrideSpeaker
import platform.AVFAudio.setActive

/**
 * 8 kHz voice for the Bluetooth channel. The frames match the Android phone.
 */
@OptIn(ExperimentalForeignApi::class)
class IosBleVoice(
    private val onFrame: (ByteArray) -> Unit,
) {
    private val engine = AVAudioEngine()
    private val player = AVAudioPlayerNode()
    private var capturing = false
    private var playRemote = true
    private var started = false

    fun mutePlayback(muted: Boolean) {
        playRemote = !muted
    }

    fun enqueue(frame: ByteArray) {
        if (!playRemote || frame.isEmpty()) return
        ensureRunning()
        val format = player.outputFormatForBus(0u)
        if (format.sampleRate <= 0.0) return
        val buffer = AVAudioPCMBuffer(pCMFormat = format, frameCapacity = frame.size.toUInt()) ?: return
        buffer.frameLength = frame.size.toUInt()
        val channel = buffer.floatChannelData?.get(0) ?: return
        for (index in frame.indices) {
            channel[index] = decodeMulaw(frame[index]) / 32768f
        }
        player.scheduleBuffer(buffer, completionHandler = null)
    }

    fun startCapture() {
        if (capturing) return
        ensureRunning()
        val input = engine.inputNode
        val format = input.outputFormatForBus(0u)
        if (format.sampleRate <= 0.0) return
        capturing = true
        input.installTapOnBus(0u, 4096u, format) { buffer, _ ->
            if (!capturing || buffer == null) return@installTapOnBus
            val channel = buffer.floatChannelData?.get(0) ?: return@installTapOnBus
            val count = buffer.frameLength.toInt()
            if (count <= 0) return@installTapOnBus
            val step = (format.sampleRate / 8000.0).toInt().coerceAtLeast(1)
            val samples = count / step
            if (samples <= 0) return@installTapOnBus
            val mulaw = ByteArray(samples)
            var inputIndex = 0
            for (output in 0 until samples) {
                var sum = 0f
                var taken = 0
                while (taken < step && inputIndex < count) {
                    sum += channel[inputIndex]
                    inputIndex += 1
                    taken += 1
                }
                val pcm = ((sum / taken) * 32767f).toInt().coerceIn(-32768, 32767)
                mulaw[output] = encodeMulaw(pcm)
            }
            onFrame(mulaw)
        }
    }

    fun stopCapture() {
        if (!capturing) return
        capturing = false
        runCatching { engine.inputNode.removeTapOnBus(0u) }
    }

    fun stop() {
        stopCapture()
        if (!started) return
        started = false
        runCatching { player.stop() }
        runCatching { engine.stop() }
        runCatching { AVAudioSession.sharedInstance().setActive(false, null) }
    }

    private fun ensureRunning() {
        if (started) return
        val session = AVAudioSession.sharedInstance()
        session.setCategory(
            AVAudioSessionCategoryPlayAndRecord,
            AVAudioSessionModeDefault,
            AVAudioSessionCategoryOptionDefaultToSpeaker,
            null,
        )
        session.setActive(true, null)
        // Voice-call mode uses the earpiece. This channel should use the loud speaker.
        session.overrideOutputAudioPort(AVAudioSessionPortOverrideSpeaker, null)
        engine.attachNode(player)
        val format = engine.inputNode.outputFormatForBus(0u)
        engine.connect(player, engine.mainMixerNode, format)
        engine.prepare()
        engine.startAndReturnError(null)
        player.play()
        started = true
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
}
