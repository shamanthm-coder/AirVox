package com.airvox.vox

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.airvox.intercom.ChannelPath
import com.airvox.intercom.IntercomConnection
import com.airvox.intercom.IosIntercomSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Opens and leaves the iPhone channel. Talk is a tap, the same as holding the channel open. */
class IosVox(
    private val session: IosIntercomSession,
) : Vox {
    private val stateHolder = mutableStateOf(VoxState())
    override val state: State<VoxState> = stateHolder

    private val scope = MainScope()
    private var enabled = true
    private var heldOff = false
    private var connectJob: Job? = null
    private var pendingRoom = ""
    private var pendingPassword = ""
    private var joinedPassword = ""
    private var pendingPath = ChannelPath.Hotspot

    override fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) {
            connectJob?.cancel()
            if (session.state.value.localTalking) session.setTalking(false)
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
        connectJob?.cancel()
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
        connectJob?.cancel()
        session.leave()
        sync()
    }

    override fun toggleTalk() {
        val talking = session.state.value.localTalking
        session.setTalking(!talking)
        sync()
    }

    override fun dispose() {
        enabled = false
        connectJob?.cancel()
    }

    private fun schedule() {
        connectJob?.cancel()
        if (!enabled || heldOff) return
        val room = normalize(pendingRoom)
        val secret = pendingPassword.trim().take(32)
        if (room.length < 3 || secret.length < 4) return
        val intercom = session.state.value
        val sameRoom = intercom.roomCode == room && intercom.path == pendingPath && joinedPassword == secret
        val alreadyThere = intercom.connection == IntercomConnection.Connecting ||
            intercom.connection == IntercomConnection.Connected
        if (alreadyThere && sameRoom) return
        connectJob = scope.launch {
            delay(900)
            joinedPassword = secret
            session.join(room, pendingPath, secret)
            sync()
        }
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
