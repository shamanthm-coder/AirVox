package com.airvox.vox

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.airvox.intercom.ChannelPath

enum class VoxPhase {
    Off,
    Ready,
    Connecting,
    Listening,
    Speaking,
    Hearing,
}

data class VoxState(
    val enabled: Boolean = true,
    val phase: VoxPhase = VoxPhase.Ready,
    val line: String = "Vox will connect the other phone, and pause the music when someone talks.",
)

/**
 * Vox decides when music pauses and when this phone opens a voice link.
 * The channel screen still picks Bluetooth, hotspot, or internet.
 */
interface Vox {
    val state: State<VoxState>

    fun setEnabled(enabled: Boolean)

    fun noteRoom(roomCode: String, path: ChannelPath, password: String)

    fun connect(roomCode: String, path: ChannelPath, password: String)

    fun leave()

    fun toggleTalk()

    fun dispose()
}

class UnavailableVox : Vox {
    override val state: State<VoxState> = mutableStateOf(
        VoxState(
            enabled = false,
            phase = VoxPhase.Off,
            line = "Vox is available on Android.",
        ),
    )

    override fun setEnabled(enabled: Boolean) = Unit

    override fun noteRoom(roomCode: String, path: ChannelPath, password: String) = Unit

    override fun connect(roomCode: String, path: ChannelPath, password: String) = Unit

    override fun leave() = Unit

    override fun toggleTalk() = Unit

    override fun dispose() = Unit
}
