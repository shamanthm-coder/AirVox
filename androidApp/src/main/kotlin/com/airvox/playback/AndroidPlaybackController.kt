package com.airvox.playback

import android.content.ComponentName
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

class AndroidPlaybackController(
    private val activity: ComponentActivity,
) : PlaybackController {
    private val nowPlayingState = mutableStateOf(NowPlaying())
    override val nowPlaying: State<NowPlaying> = nowPlayingState

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = activity.getSystemService(AudioManager::class.java)
    private val sessionManager = activity.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(activity, AirvoxNotificationListener::class.java)

    private var controller: MediaController? = null
    private var sessionsListenerRegistered = false

    @Volatile private var voiceHold = false
    @Volatile private var resumeAfterVoice = false

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
        attach(sessions)
    }

    private val playbackCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            publish()
        }

        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
            publish()
        }

        override fun onSessionDestroyed() {
            mainHandler.post { refresh() }
        }
    }

    override fun refresh() {
        if (!canReadSessions()) {
            detach()
            show(
                NowPlaying(
                    canReadPlayback = false,
                    accessRequired = true,
                    detail = "Notification access is required before Airvox can show Pause and control playback.",
                ),
            )
            return
        }
        ensureSessionListener()
        val sessions = runCatching {
            sessionManager.getActiveSessions(listenerComponent)
        }.getOrDefault(emptyList())
        attach(sessions)
    }

    override fun togglePlay() {
        val controls = controller?.transportControls
        if (controls != null) {
            if (isPlaying(controller)) controls.pause() else controls.play()
        } else {
            sendMediaKey(
                if (nowPlayingState.value.paused) {
                    KeyEvent.KEYCODE_MEDIA_PLAY
                } else {
                    KeyEvent.KEYCODE_MEDIA_PAUSE
                },
            )
        }
        mainHandler.postDelayed(::refresh, 250)
    }

    override fun skipNext() {
        controller?.transportControls?.skipToNext() ?: sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        mainHandler.postDelayed(::refresh, 250)
    }

    override fun skipPrevious() {
        controller?.transportControls?.skipToPrevious() ?: sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        mainHandler.postDelayed(::refresh, 250)
    }

    override fun openAccessSettings() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    listenerComponent.flattenToString(),
                )
            if (runCatching { activity.startActivity(detail) }.isSuccess) return
        }
        activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    override fun pauseForVoice() {
        if (voiceHold) return
        refresh()
        voiceHold = true
        val playing = isPlaying(controller)
        resumeAfterVoice = playing
        if (playing) {
            controller?.transportControls?.pause()
        } else if (controller == null) {
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
        }
    }

    override fun resumeAfterVoice() {
        if (!voiceHold) return
        val shouldResume = resumeAfterVoice
        voiceHold = false
        resumeAfterVoice = false
        if (shouldResume) {
            controller?.transportControls?.play() ?: sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
        }
    }

    override fun keepPlaying() {
        voiceHold = false
        resumeAfterVoice = false
        refresh()
        val current = controller ?: return
        if (!isPlaying(current)) current.transportControls.play()
    }

    fun dispose() {
        if (sessionsListenerRegistered) {
            runCatching { sessionManager.removeOnActiveSessionsChangedListener(sessionListener) }
            sessionsListenerRegistered = false
        }
        detach()
    }

    private fun ensureSessionListener() {
        if (sessionsListenerRegistered) return
        runCatching {
            sessionManager.addOnActiveSessionsChangedListener(sessionListener, listenerComponent)
            sessionsListenerRegistered = true
        }
    }

    private fun attach(sessions: List<MediaController>?) {
        val next = sessions.orEmpty().firstOrNull { isPlaying(it) } ?: sessions?.firstOrNull()
        if (next?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(playbackCallback)
            controller = next
            next?.registerCallback(playbackCallback, mainHandler)
        }
        publish()
    }

    private fun detach() {
        controller?.unregisterCallback(playbackCallback)
        controller = null
    }

    private fun publish() {
        val current = controller
        val metadata = current?.metadata
        val title = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
        val playing = isPlaying(current)
        val appName = current?.packageName?.let(::appLabel)
        val allowed = canReadSessions()
        show(
            NowPlaying(
                title = title?.takeIf { it.isNotBlank() },
                artist = artist?.takeIf { it.isNotBlank() },
                appName = appName,
                paused = !playing,
                hasSession = current != null,
                canReadPlayback = allowed,
                accessRequired = !allowed,
                detail = when {
                    current == null && canReadSessions() ->
                        "Nothing is playing. Start music in any player, then use the buttons here."
                    current == null ->
                        "Play, pause, and skip control the phone's current player. No account login."
                    else -> null
                },
            ),
        )
    }

    private fun appLabel(packageName: String): String? {
        return runCatching {
            val info = activity.packageManager.getApplicationInfo(packageName, 0)
            activity.packageManager.getApplicationLabel(info).toString()
        }.getOrNull()
    }

    private fun canReadSessions(): Boolean {
        val enabled = Settings.Secure.getString(
            activity.contentResolver,
            "enabled_notification_listeners",
        ).orEmpty()
        return enabled.contains(listenerComponent.flattenToString())
    }

    private fun isPlaying(mediaController: MediaController?): Boolean {
        return mediaController?.playbackState?.state == PlaybackState.STATE_PLAYING
    }

    private fun sendMediaKey(keyCode: Int) {
        val time = SystemClock.uptimeMillis()
        audioManager.dispatchMediaKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, keyCode, 0))
        audioManager.dispatchMediaKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun show(nowPlaying: NowPlaying) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            nowPlayingState.value = nowPlaying
        } else {
            mainHandler.post { nowPlayingState.value = nowPlaying }
        }
    }
}
