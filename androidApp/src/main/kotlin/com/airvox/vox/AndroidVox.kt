package com.airvox.vox

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.airvox.intercom.AndroidIntercomSession
import com.airvox.intercom.ChannelPath
import com.airvox.intercom.IntercomConnection
import com.airvox.playback.PlaybackController
import kotlin.math.max
import kotlin.math.sqrt

/**
 * On-device listener. Vox pauses music for real speech and opens the mic
 * only once the other phone is on the channel.
 */
class AndroidVox(
    private val activity: ComponentActivity,
    private val session: AndroidIntercomSession,
    private val playback: PlaybackController,
) : Vox {
    private val stateHolder = mutableStateOf(VoxState())
    override val state: State<VoxState> = stateHolder

    private val mainHandler = Handler(Looper.getMainLooper())
    private val gateLock = Any()
    private var enabled = true
    private var heldOff = false
    private var manual = false
    private var disposed = false
    private var pendingRoom = ""
    private var pendingPassword = ""
    private var joinedPassword = ""
    private var pendingPath = ChannelPath.Hotspot

    @Volatile private var hearingSpeech = false
    @Volatile private var earRunning = false
    private var earThread: Thread? = null
    private var earRecorder: AudioRecord? = null

    private var speaking = false
    private var noise = 250.0
    private var speechSince = 0L
    private var quietSince = 0L
    private var rearmAt = 0L

    private val autoConnect = Runnable {
        val room = normalize(pendingRoom)
        val secret = pendingPassword.trim().take(32)
        if (disposed || !enabled || heldOff || room.length < 3 || secret.length < 4) return@Runnable
        joinedPassword = secret
        session.join(room, pendingPath, secret)
        sync()
    }

    private val watch = object : Runnable {
        override fun run() {
            if (disposed) return
            sync()
            mainHandler.postDelayed(this, 400)
        }
    }

    init {
        session.onMicLevel = { rms -> if (!earRunning) onLevel(rms) }
        mainHandler.post(watch)
    }

    override fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) {
            mainHandler.removeCallbacks(autoConnect)
            manual = false
            hearingSpeech = false
            resetGate()
            if (session.state.value.localTalking) session.setTalking(false)
            stopEar()
            resumeMusicIfQuiet()
        } else {
            schedule()
        }
        sync()
    }

    override fun noteRoom(roomCode: String, path: ChannelPath, password: String) {
        val changed = roomCode != pendingRoom || path != pendingPath || password != pendingPassword
        pendingRoom = roomCode
        pendingPassword = password
        pendingPath = path
        if (changed) heldOff = false
        schedule()
    }

    override fun connect(roomCode: String, path: ChannelPath, password: String) {
        pendingRoom = roomCode
        pendingPassword = password
        pendingPath = path
        heldOff = false
        mainHandler.removeCallbacks(autoConnect)
        val room = normalize(roomCode)
        val secret = password.trim().take(32)
        if (room.length < 3) {
            publish(VoxPhase.Ready, "Vox needs a room code with at least 3 letters or numbers.")
            return
        }
        if (secret.length < 4) {
            publish(VoxPhase.Ready, "Vox needs a password with at least 4 characters. Both phones use the same one.")
            return
        }
        joinedPassword = secret
        session.join(room, path, secret)
        sync()
    }

    override fun leave() {
        heldOff = true
        manual = false
        hearingSpeech = false
        resetGate()
        mainHandler.removeCallbacks(autoConnect)
        stopEar()
        session.leave()
        sync()
    }

    override fun toggleTalk() {
        if (!enabled) {
            val talking = session.state.value.localTalking
            session.setTalking(!talking)
            return
        }
        if (session.state.value.localTalking) {
            manual = false
            hearingSpeech = false
            resetGate()
            session.setTalking(false)
            resumeMusicIfQuiet()
            sync()
            return
        }
        manual = true
        stopEar()
        playback.pauseForVoice()
        session.setTalking(true)
        if (!session.state.value.localTalking) {
            manual = false
            resumeMusicIfQuiet()
        }
        sync()
    }

    override fun dispose() {
        disposed = true
        enabled = false
        mainHandler.removeCallbacksAndMessages(null)
        stopEar()
        session.onMicLevel = null
    }

    private fun schedule() {
        mainHandler.removeCallbacks(autoConnect)
        if (!enabled || heldOff) return
        val room = normalize(pendingRoom)
        val secret = pendingPassword.trim().take(32)
        if (room.length < 3 || secret.length < 4) return
        val intercom = session.state.value
        val sameRoom = intercom.roomCode == room &&
            intercom.path == pendingPath &&
            joinedPassword == secret
        val alreadyThere = intercom.connection == IntercomConnection.Connecting ||
            intercom.connection == IntercomConnection.Connected
        if (alreadyThere && sameRoom) return
        mainHandler.postDelayed(autoConnect, 900)
    }

    private fun onLevel(rms: Int) {
        if (!enabled || manual || disposed) return
        val nowSpeaking = synchronized(gateLock) { gate(rms, SystemClock.elapsedRealtime()) }
        if (nowSpeaking == hearingSpeech) return
        hearingSpeech = nowSpeaking
        mainHandler.post {
            if (!disposed) sync()
        }
    }

    private fun gate(rms: Int, now: Long): Boolean {
        val floor = max(noise, 200.0)
        val loud = rms > floor * 2.6 && rms > floor + 400
        if (!speaking) {
            noise = noise * 0.9 + rms * 0.1
            if (now < rearmAt) return false
            if (loud) {
                if (speechSince == 0L) speechSince = now
                if (now - speechSince >= 180) {
                    speaking = true
                    quietSince = 0L
                }
            } else {
                speechSince = 0L
            }
        } else if (!loud) {
            if (quietSince == 0L) quietSince = now
            if (now - quietSince >= 700) {
                speaking = false
                speechSince = 0L
                quietSince = 0L
                rearmAt = now + 450
            }
        } else {
            quietSince = 0L
        }
        return speaking
    }

    private fun resetGate() {
        synchronized(gateLock) {
            speaking = false
            noise = 250.0
            speechSince = 0L
            quietSince = 0L
            rearmAt = 0L
        }
    }

    private fun sync() {
        if (disposed) return
        val intercom = session.state.value
        if (!enabled) {
            stopEar()
            publish(VoxPhase.Off, "Vox is off. Tap Talk to speak. Music pauses while someone is talking.")
            return
        }
        if (manual && intercom.localTalking) {
            stopEar()
            publish(VoxPhase.Speaking, "Vox paused the music. Tap Stop when you are done.")
            return
        }
        if (intercom.remoteTalking && !hearingSpeech && !intercom.localTalking) {
            stopEar()
            publish(VoxPhase.Hearing, "Vox paused the music so you can hear the other phone.")
            return
        }
        if (hearingSpeech && !intercom.remoteTalking) {
            playback.pauseForVoice()
            if (intercom.connection == IntercomConnection.Connected && !intercom.localTalking) {
                stopEar()
                session.setTalking(true)
            }
            val live = session.state.value
            val line = if (live.localTalking || live.connection == IntercomConnection.Connected) {
                "Vox paused the music and connected your voice."
            } else {
                "Vox paused the music. Your voice connects when the other phone joins."
            }
            publish(VoxPhase.Speaking, line)
            return
        }
        if (intercom.localTalking && !manual) {
            session.setTalking(false)
        }
        resumeMusicIfQuiet()
        val onChannel = intercom.connection == IntercomConnection.Connecting ||
            intercom.connection == IntercomConnection.Connected
        if (onChannel && !session.state.value.localTalking) startEar() else stopEar()
        val line = when {
            heldOff -> "Vox left the channel. Tap Join and Vox will connect again."
            intercom.connection == IntercomConnection.Connected ->
                "Vox has the other phone. Music plays until someone talks."
            intercom.connection == IntercomConnection.Connecting ->
                "Vox is connecting to the other phone. Music stays on."
            else -> "Vox will connect this room, and pause the music when someone talks."
        }
        val phase = when (intercom.connection) {
            IntercomConnection.Connected -> VoxPhase.Listening
            IntercomConnection.Connecting -> VoxPhase.Connecting
            else -> VoxPhase.Ready
        }
        publish(phase, line)
    }

    private fun resumeMusicIfQuiet() {
        val intercom = session.state.value
        if (!intercom.localTalking && !intercom.remoteTalking) {
            playback.resumeAfterVoice()
        }
    }

    private fun startEar() {
        if (earRunning && earThread?.isAlive == true) return
        if (earRecorder != null || earThread != null) stopEar()
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val mic = openMic() ?: return
        earRunning = true
        earRecorder = mic.recorder
        try {
            mic.recorder.startRecording()
        } catch (_: IllegalStateException) {
            earRunning = false
            earRecorder = null
            mic.recorder.release()
            return
        }
        val frame = mic.inputRate / 50
        earThread = Thread({
            val pcm = ShortArray(frame)
            while (earRunning) {
                val read = try {
                    mic.recorder.read(pcm, 0, pcm.size)
                } catch (_: IllegalStateException) {
                    break
                }
                if (read < 0) break
                if (!earRunning || read == 0) continue
                onLevel(rms(pcm, read))
            }
            earRunning = false
        }, "VoxEar").also { it.start() }
    }

    private fun stopEar() {
        earRunning = false
        val thread = earThread
        val recorder = earRecorder
        earThread = null
        earRecorder = null
        if (recorder != null) {
            runCatching {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            }
        }
        thread?.join(250)
        recorder?.let { runCatching { it.release() } }
    }

    private fun openMic(): Mic? {
        for (rate in intArrayOf(8000, 16000)) {
            val min = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (min <= 0) continue
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                min * 2,
            )
            if (recorder.state == AudioRecord.STATE_INITIALIZED) return Mic(recorder, rate)
            recorder.release()
        }
        return null
    }

    private fun rms(pcm: ShortArray, count: Int): Int {
        if (count <= 0) return 0
        var sum = 0.0
        for (index in 0 until count) {
            val value = pcm[index].toInt()
            sum += value * value.toDouble()
        }
        return sqrt(sum / count).toInt()
    }

    private fun publish(phase: VoxPhase, line: String) {
        val next = VoxState(enabled = enabled, phase = phase, line = line)
        if (next != stateHolder.value) stateHolder.value = next
    }

    private fun normalize(room: String): String {
        return room.trim().lowercase().filter { it.isLetterOrDigit() }.take(16)
    }

    private class Mic(val recorder: AudioRecord, val inputRate: Int)
}
