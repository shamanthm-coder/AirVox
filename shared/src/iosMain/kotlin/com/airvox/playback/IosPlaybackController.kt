package com.airvox.playback

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

class IosPlaybackController(
    private val system: IosSystemPlayback,
) : PlaybackController {
    private val nowPlayingState = mutableStateOf(NowPlaying())
    override val nowPlaying: State<NowPlaying> = nowPlayingState

    private var voiceHold = false
    private var resumeAfterVoice = false

    init {
        system.start(
            object : IosNowPlayingListener {
                override fun onNowPlaying(title: String, artist: String, appName: String, playing: Boolean) {
                    val named = title.isNotBlank() || artist.isNotBlank()
                    nowPlayingState.value = NowPlaying(
                        title = title.ifBlank { null },
                        artist = artist.ifBlank { null },
                        appName = appName.ifBlank { null },
                        paused = !playing,
                        hasSession = named,
                        canReadPlayback = true,
                        detail = if (named) {
                            null
                        } else {
                            "Nothing is playing. Start a song in any player, then it shows here."
                        },
                    )
                }
            },
        )
    }

    override fun refresh() = system.refresh()

    override fun play() = system.play()

    override fun pause() = system.pause()

    override fun togglePlay() {
        if (nowPlayingState.value.paused) play() else pause()
    }

    override fun skipNext() = system.next()

    override fun skipPrevious() = system.previous()

    override fun openAccessSettings() = Unit

    override fun pauseForVoice() {
        if (voiceHold) return
        voiceHold = true
        val playing = nowPlayingState.value.hasSession && !nowPlayingState.value.paused
        resumeAfterVoice = playing
        if (playing) system.pause()
    }

    override fun resumeAfterVoice() {
        if (!voiceHold) return
        val resume = resumeAfterVoice
        voiceHold = false
        resumeAfterVoice = false
        if (resume) system.play()
    }

    override fun keepPlaying() {
        voiceHold = false
        resumeAfterVoice = false
        if (nowPlayingState.value.paused) system.play()
    }
}
