package com.airvox.intercom

/**
 * The iPhone side of the same WebRTC call Android already makes.
 * Swift owns the peer connection, microphone, and speaker.
 * Kotlin sends the offer, answer, and ICE candidates.
 */
interface IosVoiceCall {
    fun attach(listener: IosVoiceListener)

    fun prepare(internet: Boolean)

    fun stop()

    fun openPeer(peerId: String)

    fun closePeer(peerId: String)

    fun createOffer(peerId: String)

    fun acceptOffer(peerId: String, sdp: String)

    fun acceptAnswer(peerId: String, sdp: String)

    fun addIce(peerId: String, sdpMid: String, sdpMLineIndex: Int, candidate: String)

    fun setMicEnabled(enabled: Boolean)

    fun setRemoteTalking(peerId: String, talking: Boolean)
}

interface IosVoiceListener {
    fun onLocalOffer(peerId: String, sdp: String)

    fun onLocalAnswer(peerId: String, sdp: String)

    fun onIceCandidate(peerId: String, sdpMid: String, sdpMLineIndex: Int, candidate: String)

    fun onConnected(peerId: String)

    fun onDropped(peerId: String)

    fun onUnreachable(peerId: String)

    fun onError(message: String)
}
