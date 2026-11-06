package com.airvox.playback

/**
 * The iPhone side of the same now-playing card Android already shows.
 * Swift reads the song the phone is already playing and sends play, pause, and skip.
 */
interface IosSystemPlayback {
    fun start(listener: IosNowPlayingListener)

    fun refresh()

    fun play()

    fun pause()

    fun next()

    fun previous()
}

interface IosNowPlayingListener {
    fun onNowPlaying(title: String, artist: String, appName: String, playing: Boolean)
}
