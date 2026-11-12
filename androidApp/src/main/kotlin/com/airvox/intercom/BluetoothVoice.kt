package com.airvox.intercom

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Phone-quality voice frames for the Bluetooth channel.
 * 8 kHz G.711 is small enough to ride a Bluetooth link.
 */
class BluetoothVoice(
    private val context: Context,
    private val onFrame: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
) {
    @Volatile var onLevel: ((Int) -> Unit)? = null
    @Volatile private var playRemote = true
    @Volatile private var capturing = false
    @Volatile private var playbackRunning = false

    private val lock = Any()
    private val incoming = ArrayBlockingQueue<ByteArray>(12)
    private var recorder: AudioRecord? = null
    private var echo: AcousticEchoCanceler? = null
    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null
    @Volatile private var playbackGeneration = 0

    fun mutePlayback(muted: Boolean) {
        playRemote = !muted
        if (muted) incoming.clear()
    }

    fun enqueue(frame: ByteArray) {
        if (!playRemote || frame.isEmpty()) return
        ensurePlayback()
        if (!incoming.offer(frame)) {
            incoming.poll()
            incoming.offer(frame)
        }
    }

    fun startCapture() {
        synchronized(lock) {
            if (capturing) return
            val mic = openMic()
            if (mic == null) {
                onError("Could not open the microphone.")
                return
            }
            capturing = true
            recorder = mic.recorder
            // With both people talking, the speaker's sound reaches the microphone.
            // Ask the phone to take it out, where the phone can.
            echo = runCatching {
                if (AcousticEchoCanceler.isAvailable()) {
                    AcousticEchoCanceler.create(mic.recorder.audioSessionId)?.also { it.enabled = true }
                } else {
                    null
                }
            }.getOrNull()
            try {
                mic.recorder.startRecording()
            } catch (error: IllegalStateException) {
                capturing = false
                recorder = null
                mic.recorder.release()
                onError(error.message ?: "Could not open the microphone.")
                return
            }
            captureThread = Thread({ captureLoop(mic) }, "AirvoxBtMic").also { it.start() }
        }
    }

    fun stopCapture() {
        val thread: Thread?
        val rec: AudioRecord?
        synchronized(lock) {
            capturing = false
            thread = captureThread
            captureThread = null
            rec = recorder
            recorder = null
            runCatching { echo?.release() }
            echo = null
        }
        if (rec != null) {
            runCatching {
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop()
            }
        }
        thread?.join(300)
        rec?.let { runCatching { it.release() } }
    }

    fun stopPlayback() {
        val thread = synchronized(lock) {
            playbackRunning = false
            playbackGeneration++
            incoming.clear()
            playbackThread
        }
        thread?.interrupt()
        thread?.join(300)
        synchronized(lock) {
            if (playbackThread === thread) playbackThread = null
        }
    }

    private fun captureLoop(mic: Mic) {
        val samples = mic.inputRate * FRAME_MS / 1000
        val pcm = ShortArray(samples)
        while (capturing) {
            val read = try {
                mic.recorder.read(pcm, 0, pcm.size)
            } catch (_: IllegalStateException) {
                break
            }
            if (!capturing) break
            if (read < 0) {
                onError("Microphone stopped.")
                break
            }
            if (read == 0) continue
            val voice = if (mic.inputRate == 16000) downsample(pcm, read) else pcm.copyOf(read)
            onLevel?.invoke(levelOf(voice))
            val mulaw = ByteArray(voice.size)
            for (index in voice.indices) {
                mulaw[index] = encodeMulaw(voice[index].toInt())
            }
            onFrame(mulaw)
        }
    }

    private fun ensurePlayback() {
        synchronized(lock) {
            if (playbackRunning && playbackThread?.isAlive == true) return
            playbackRunning = true
            val generation = ++playbackGeneration
            playbackThread = Thread({ playLoop(generation) }, "AirvoxBtPlay").also { it.start() }
        }
    }

    private fun playLoop(generation: Int) {
        val track = openPlayer()
        if (track == null) {
            synchronized(lock) {
                if (generation == playbackGeneration) playbackRunning = false
            }
            onError("Could not play voice.")
            return
        }
        try {
            track.play()
            var primed = false
            while (playbackRunning && generation == playbackGeneration) {
                val frame = try {
                    incoming.poll(200, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    break
                }
                if (frame == null) {
                    primed = false
                    continue
                }
                if (!primed) {
                    // Start of someone talking. Hold a few frames first, so voice that
                    // arrives in uneven bursts, as it does from an iPhone, is not chopped.
                    primed = true
                    val deadline = System.currentTimeMillis() + PREFILL_WAIT_MS
                    try {
                        while (incoming.size < PREFILL_FRAMES && System.currentTimeMillis() < deadline) {
                            Thread.sleep(10)
                        }
                    } catch (_: InterruptedException) {
                        break
                    }
                }
                val pcm = ShortArray(frame.size)
                for (index in frame.indices) {
                    pcm[index] = decodeMulaw(frame[index])
                }
                track.write(pcm, 0, pcm.size)
            }
        } finally {
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.release() }
            synchronized(lock) {
                if (generation == playbackGeneration) playbackRunning = false
            }
        }
    }

    private fun openMic(): Mic? {
        // The call source is silenced by the speakerphone processor on this phone.
        // The plain microphone still hears the person talking.
        val sources = intArrayOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        )
        val input = builtinMic()
        for (source in sources) {
            for (rate in intArrayOf(8000, 16000)) {
                val min = AudioRecord.getMinBufferSize(
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                if (min <= 0) continue
                val recorder = AudioRecord(
                    source,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    min * 2,
                )
                if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                    input?.let { recorder.setPreferredDevice(it) }
                    return Mic(recorder, rate)
                }
                recorder.release()
            }
        }
        return null
    }

    private fun builtinMic(): AudioDeviceInfo? {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
    }

    private fun openPlayer(): AudioTrack? {
        val min = AudioTrack.getMinBufferSize(
            8000,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (min <= 0) return null
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(8000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(min, 3200))
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            return null
        }
        // By default the speaker stays silent until the whole buffer is full. That was half
        // a second of voice, so a short or broken-up sentence was never heard at all.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { track.setStartThresholdInFrames(START_FRAMES.coerceAtMost(track.bufferSizeInFrames)) }
        }
        val speaker = speakerDevice()
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        manager.mode = AudioManager.MODE_NORMAL
        manager.isSpeakerphoneOn = true
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (max > 0 && current < max / 2) {
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, (max / 2).coerceAtLeast(1), 0)
        }
        speaker?.let { track.setPreferredDevice(it) }
        track.setVolume(1f)
        return track
    }

    private fun speakerDevice(): AudioDeviceInfo? {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    }

    private fun levelOf(pcm: ShortArray): Int {
        if (pcm.isEmpty()) return 0
        var sum = 0.0
        for (sample in pcm) {
            val value = sample.toInt()
            sum += value * value.toDouble()
        }
        return kotlin.math.sqrt(sum / pcm.size).toInt()
    }

    private fun downsample(pcm: ShortArray, length: Int): ShortArray {
        val out = ShortArray(length / 2)
        var input = 0
        var output = 0
        while (input + 1 < length && output < out.size) {
            out[output] = ((pcm[input].toInt() + pcm[input + 1].toInt()) / 2).toShort()
            output++
            input += 2
        }
        return if (output == out.size) out else out.copyOf(output)
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

    private class Mic(val recorder: AudioRecord, val inputRate: Int)

    private companion object {
        const val FRAME_MS = 20
        const val PREFILL_FRAMES = 3
        const val PREFILL_WAIT_MS = 100L
        const val START_FRAMES = 320
    }
}
