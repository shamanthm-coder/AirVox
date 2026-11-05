package com.airvox.intercom

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

data class Earphone(
    val id: String,
    val name: String,
)

data class IntercomMember(
    val id: String,
    val name: String,
    val talking: Boolean = false,
)

data class ChannelPlace(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val self: Boolean = false,
)

enum class IntercomConnection {
    Disconnected,
    Connecting,
    Connected,
    Unavailable,
}

enum class ChannelPath(val label: String) {
    Bluetooth("Bluetooth"),
    Hotspot("Hotspot"),
    Internet("Internet"),
}

data class IntercomState(
    val roomCode: String = "",
    val connection: IntercomConnection = IntercomConnection.Disconnected,
    val path: ChannelPath = ChannelPath.Hotspot,
    val members: List<IntercomMember> = emptyList(),
    val localTalking: Boolean = false,
    val remoteTalking: Boolean = false,
    val places: List<ChannelPlace> = emptyList(),
    val detail: String? = null,
)

/**
 * A voice channel between phones.
 * The channel screen picks Bluetooth, a Wi-Fi hotspot, or the internet.
 * Tap Talk to send microphone audio. Tap again to stop. Music on each phone pauses only while someone is talking.
 */
interface IntercomSession {
    val state: State<IntercomState>

    fun join(roomCode: String, path: ChannelPath = ChannelPath.Hotspot, password: String = "")

    /** The signed-in rider's name. Channels and the map use this instead of the phone name. */
    fun setRiderName(name: String) = Unit

    fun leave()

    fun setTalking(talking: Boolean)
}

class UnavailableIntercomSession : IntercomSession {
    override val state: State<IntercomState> = mutableStateOf(
        IntercomState(
            connection = IntercomConnection.Unavailable,
            detail = "Voice channels are available on Android.",
        ),
    )

    override fun join(roomCode: String, path: ChannelPath, password: String) = Unit

    override fun leave() = Unit

    override fun setTalking(talking: Boolean) = Unit
}
