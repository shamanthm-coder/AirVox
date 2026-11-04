package com.airvox.playback

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

class UnavailablePlaybackController : PlaybackController {
    override val nowPlaying: State<NowPlaying> = mutableStateOf(
        NowPlaying(detail = "Playback controls are available on Android."),
    )

    override fun refresh() = Unit

    override fun togglePlay() = Unit

    override fun skipNext() = Unit

    override fun skipPrevious() = Unit

    override fun openAccessSettings() = Unit

    override fun pauseForVoice() = Unit

    override fun resumeAfterVoice() = Unit
}
