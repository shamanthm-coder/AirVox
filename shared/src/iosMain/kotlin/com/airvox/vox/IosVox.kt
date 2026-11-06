package com.airvox.vox

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.airvox.intercom.ChannelPath
import com.airvox.intercom.IntercomConnection
import com.airvox.intercom.IosIntercomSession

/** Opens and leaves the iPhone channel. Talk is a tap, the same as holding the channel open. */
class IosVox(
    private val session: IosIntercomSession,
) : Vox {
    private val stateHolder = mutableStateOf(
        VoxState(
            enabled = false,
            phase = VoxPhase.Off,
            line = "Vox is off. Turn it on to open a channel.",
        ),
    )
    override val state: State<VoxState> = stateHolder

    private var enabled = false
    private var heldOff = false
    private var pendingRoom = ""
    private var pendingPassword = ""
    private var pendingPath = ChannelPath.Hotspot

    override fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) {
            if (session.state.value.localTalking) session.setTalking(false)
        }
        sync()
    }

    override fun noteRoom(roomCode: String, path: ChannelPath, password: String) {
        val changed = roomCode != pendingRoom || path != pendingPath || password != pendingPassword
        pendingRoom = roomCode
        pendingPassword = password
        pendingPath = path
        if (changed) heldOff = false
    }

    override fun connect(roomCode: String, path: ChannelPath, password: String) {
        pendingRoom = roomCode
        pendingPassword = password
        pendingPath = path
        heldOff = false
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
        session.join(room, path, secret)
        sync()
    }

    override fun leave() {
        heldOff = true
        session.leave()
        sync()
    }

    override fun toggleTalk() {
        val talking = session.state.value.localTalking
        session.setTalking(!talking)
        sync()
    }

    override fun setEarPaused(paused: Boolean) = Unit

    override fun dispose() {
        enabled = false
    }

    private fun sync() {
        val intercom = session.state.value
        val phase = when {
            !enabled -> VoxPhase.Off
            intercom.localTalking -> VoxPhase.Speaking
            intercom.connection == IntercomConnection.Connected -> VoxPhase.Listening
            intercom.connection == IntercomConnection.Connecting -> VoxPhase.Connecting
            else -> VoxPhase.Ready
        }
        val line = when (phase) {
            VoxPhase.Off -> "Vox is off. Turn it on to open a channel."
            VoxPhase.Connecting -> intercom.detail ?: "Opening the channel…"
            VoxPhase.Listening -> intercom.detail ?: "Channel is open. Tap Talk to speak."
            VoxPhase.Speaking -> "Talking. Tap Stop when you are done."
            VoxPhase.Hearing -> "Someone is talking."
            VoxPhase.Ready -> "Enter a room code and password, then join the channel."
        }
        publish(phase, line)
    }

    private fun publish(phase: VoxPhase, line: String) {
        stateHolder.value = VoxState(enabled = enabled, phase = phase, line = line)
    }

    private fun normalize(value: String) = value.trim().lowercase().filter { it.isLetterOrDigit() }.take(16)
}
