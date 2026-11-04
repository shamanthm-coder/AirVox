package com.airvox.playback

import androidx.compose.runtime.State

data class NowPlaying(
    val title: String? = null,
    val artist: String? = null,
    val appName: String? = null,
    val paused: Boolean = true,
    val hasSession: Boolean = false,
    val canReadPlayback: Boolean = false,
    val accessRequired: Boolean = false,
    val detail: String? = null,
)

/**
 * Controls whatever music app is already playing on the phone.
 * Airvox does not sign in to that app.
 */
interface PlaybackController {
    val nowPlaying: State<NowPlaying>

    fun refresh()

    fun togglePlay()

    fun skipNext()

    fun skipPrevious()

    fun openAccessSettings()

    /** Pauses the current player for an intercom transmission. */
    fun pauseForVoice()

    /** Resumes only when Airvox paused a song that was already playing. */
    fun resumeAfterVoice()
}
