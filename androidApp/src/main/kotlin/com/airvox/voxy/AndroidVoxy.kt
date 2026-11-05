package com.airvox.voxy

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.airvox.intercom.IntercomSession
import com.airvox.playback.PlaybackController
import com.airvox.vox.Vox
import java.util.Locale

/**
 * Listens for "Voxy" and answers out loud with the rider's name.
 * The channel microphone wins while someone is talking.
 */
class AndroidVoxy(
    private val activity: ComponentActivity,
    private val session: IntercomSession,
    private val playback: PlaybackController,
    private val vox: Vox,
) : Voxy {
    override val canBackground: Boolean = true

    private val appContext = activity.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val stateHolder = mutableStateOf(VoxyState())
    override val state: State<VoxyState> = stateHolder

    private val mainHandler = Handler(Looper.getMainLooper())
    private var displayName = ""
    private var started = false
    private var disposed = false
    private var inForeground = false
    private var speaking = false
    private var askedMic = false
    private var pausedMusic = false
    private var failures = 0
    private var listeningNow = false
    private var listenQueued = false
    private var recognitionUnavailable = false
    private var sessionOpen = false
    private var epoch = 0
    private var useLanguage = true
    private var backgroundEnabled = false
    private var wantBackground = false
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingLine: String? = null

    private val permissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (disposed || !started) return@registerForActivityResult
        if (granted) scheduleListen(200) else publish(false, MIC_LINE)
    }

    private val notificationLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (disposed) return@registerForActivityResult
        if (granted && wantBackground) {
            wantBackground = false
            turnBackgroundOn(askBattery = true)
        } else {
            wantBackground = false
            publish(false, LOCK_PERMISSION_LINE)
        }
    }

    private val foreground = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            inForeground = true
            if (started && !speaking) scheduleListen(200)
        }

        override fun onStop(owner: LifecycleOwner) {
            inForeground = false
            if (backgroundEnabled) {
                listenQueued = false
                mainHandler.removeCallbacks(listenAgain)
                stopRecognizer()
                if (started && !speaking) scheduleListen(400)
                return
            }
            mainHandler.removeCallbacks(listenAgain)
            releaseEar()
            stopRecognizer()
        }
    }

    private val listenAgain = Runnable {
        listenQueued = false
        if (disposed || !started || speaking) return@Runnable
        beginListen()
    }

    private val watch = object : Runnable {
        override fun run() {
            if (disposed) return
            if (started && channelHasMic()) {
                if (listeningNow || listenQueued || stateHolder.value.listening) {
                    listenQueued = false
                    mainHandler.removeCallbacks(listenAgain)
                    releaseEar()
                    stopRecognizer()
                    publish(false, "Voxy will listen again when the channel is quiet.")
                }
            } else if (canListen() && !listeningNow && !listenQueued) {
                scheduleListen(250)
            }
            mainHandler.postDelayed(this, 400)
        }
    }

    private fun listener(token: Int) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            mainHandler.post {
                if (token != epoch || !sessionOpen || speaking) return@post
                publish(true, promptLine())
            }
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            mainHandler.post {
                if (token != epoch) return@post
                handleError(error)
            }
        }

        override fun onResults(results: Bundle?) {
            val texts = textsFrom(results)
            mainHandler.post {
                if (token != epoch) return@post
                handleTranscripts(texts)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val texts = textsFrom(partialResults)
            mainHandler.post {
                if (token != epoch || !sessionOpen || speaking) return@post
                Log.i(TAG, "partial ${texts.joinToString()}")
                if (texts.any(::heardVoxy)) greet()
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    init {
        inForeground = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        activity.lifecycle.addObserver(foreground)
        mainHandler.post(watch)
        tts = TextToSpeech(appContext) { status ->
            val engine = tts
            if (status != TextToSpeech.SUCCESS || engine == null) {
                ttsReady = false
                if (speaking) mainHandler.post { finishSpeaking() }
                return@TextToSpeech
            }
            engine.language = Locale.US
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == UTTERANCE) mainHandler.post { finishSpeaking() }
                }

                @Deprecated("Required by UtteranceProgressListener")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == UTTERANCE) mainHandler.post { finishSpeaking() }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId == UTTERANCE) mainHandler.post { finishSpeaking() }
                }
            })
            ttsReady = true
            val waiting = pendingLine
            if (waiting != null) {
                pendingLine = null
                mainHandler.post { speak(waiting) }
            }
        }
    }

    override fun start(displayName: String) {
        if (disposed) return
        this.displayName = displayName.trim()
        if (!started) {
            started = true
            publish(false, "Say Voxy.")
        }
        if (prefs.getBoolean(PREF_BACKGROUND, false) && !needsNotificationPermission()) {
            if (backgroundEnabled) startListenService() else turnBackgroundOn(askBattery = false)
        }
        if (!speaking) scheduleListen(150)
    }

    override fun setBackground(enabled: Boolean) {
        if (disposed) return
        if (!enabled) {
            wantBackground = false
            backgroundEnabled = false
            prefs.edit().putBoolean(PREF_BACKGROUND, false).apply()
            stopListenService()
            publish(stateHolder.value.listening && inForeground, stateHolder.value.line)
            if (!inForeground) {
                stopRecognizer()
                releaseEar()
                publish(false, "Say Voxy.")
            }
            return
        }
        if (needsNotificationPermission()) {
            wantBackground = true
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        turnBackgroundOn(askBattery = true)
    }

    override fun stop() {
        started = false
        speaking = false
        listeningNow = false
        listenQueued = false
        pendingLine = null
        mainHandler.removeCallbacks(listenAgain)
        releaseEar()
        stopRecognizer()
        tts?.stop()
        stopListenService()
        if (pausedMusic) {
            playback.resumeAfterVoice()
            pausedMusic = false
        }
        publish(false, "Say Voxy.")
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        stop()
        activity.lifecycle.removeObserver(foreground)
        mainHandler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    private fun beginListen() {
        if (!canListen()) return
        if (!hasMic()) {
            releaseEar()
            if (!askedMic) {
                askedMic = true
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            publish(false, MIC_LINE)
            return
        }
        startPhoneSession()
    }

    private fun startPhoneSession() {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            recognitionUnavailable = true
            releaseEar()
            publish(false, "Voxy needs speech recognition on this phone.")
            return
        }
        val ear = ensureRecognizer()
        if (ear == null) {
            recognitionUnavailable = true
            releaseEar()
            publish(false, "Voxy needs speech recognition on this phone.")
            return
        }
        vox.setEarPaused(true)
        try {
            ear.startListening(listenIntent())
            listeningNow = true
            sessionOpen = true
            publish(true, promptLine())
            val token = epoch
            mainHandler.postDelayed({
                if (token == epoch && sessionOpen && !speaking) {
                    Log.i(TAG, "listen timed out")
                    handleError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                }
            }, 12_000L)
        } catch (_: SecurityException) {
            listeningNow = false
            sessionOpen = false
            releaseEar()
            publish(false, MIC_LINE)
        }
    }

    private fun handleTranscripts(texts: List<String>) {
        if (disposed || !started || speaking || !sessionOpen) return
        sessionOpen = false
        listeningNow = false
        Log.i(TAG, "heard ${texts.joinToString()}")
        retireRecognizer()
        if (texts.any(::heardVoxy)) {
            greet()
        } else {
            if (texts.isNotEmpty()) publish(false, "Heard \"${texts.first()}\". Say Voxy.")
            scheduleListen(if (texts.isEmpty()) 200L else 1_200L)
        }
    }

    private fun handleError(error: Int) {
        if (disposed || !started || speaking || !sessionOpen) return
        sessionOpen = false
        listeningNow = false
        Log.i(TAG, "listen error $error")
        retireRecognizer()
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            releaseEar()
            publish(false, MIC_LINE)
            if (!askedMic) {
                askedMic = true
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            return
        }
        if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
            error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
        ) {
            useLanguage = false
        }
        val routine = error == SpeechRecognizer.ERROR_NO_MATCH ||
            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
        if (!routine) failures += 1
        val delay = when (error) {
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 700L
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> 900L
            else -> 300L
        }
        if (!routine && failures >= 3) {
            publish(false, "Voxy can't use speech recognition on this phone right now. Say Voxy again.")
        }
        scheduleListen(delay + (failures.coerceAtMost(4) * 200L))
    }

    private fun greet() {
        if (speaking || disposed || !started) return
        speaking = true
        failures = 0
        listenQueued = false
        sessionOpen = false
        mainHandler.removeCallbacks(listenAgain)
        stopRecognizer()
        val line = voxyGreeting(displayName)
        publish(false, line)
        mainHandler.postDelayed({ finishSpeaking() }, 8_000L)
        if (!channelHasMic()) {
            playback.pauseForVoice()
            pausedMusic = true
        }
        if (backgroundEnabled) requestGreetingFocus()
        speak(line)
    }

    private fun speak(line: String) {
        val engine = tts
        if (engine == null || !ttsReady) {
            pendingLine = line
            return
        }
        val queued = engine.speak(line, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE)
        if (queued == TextToSpeech.ERROR) finishSpeaking()
    }

    private fun finishSpeaking() {
        if (!speaking) return
        speaking = false
        val holdForChannel = channelHasMic()
        if (pausedMusic && !holdForChannel) playback.resumeAfterVoice()
        pausedMusic = false
        if (started && !disposed) scheduleListen(600)
    }

    private fun canListen(): Boolean {
        return started && !disposed && (inForeground || backgroundEnabled) &&
            !speaking && !channelHasMic() && !recognitionUnavailable
    }

    private fun channelHasMic(): Boolean {
        val intercom = session.state.value
        return intercom.localTalking || intercom.remoteTalking
    }

    private fun hasMic(): Boolean {
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun scheduleListen(delayMs: Long) {
        if (!canListen()) {
            listenQueued = false
            mainHandler.removeCallbacks(listenAgain)
            if (channelHasMic() && (listeningNow || stateHolder.value.listening)) {
                releaseEar()
                stopRecognizer()
                publish(false, "Voxy will listen again when the channel is quiet.")
            }
            return
        }
        if (listenQueued || listeningNow) return
        listenQueued = true
        mainHandler.postDelayed(listenAgain, delayMs.coerceAtMost(3_000L))
    }

    private fun ensureRecognizer(): SpeechRecognizer? {
        recognizer?.let { return it }
        val created = runCatching { SpeechRecognizer.createSpeechRecognizer(appContext) }.getOrNull() ?: return null
        epoch += 1
        created.setRecognitionListener(listener(epoch))
        recognizer = created
        return created
    }

    private fun stopRecognizer() {
        listeningNow = false
        sessionOpen = false
        retireRecognizer()
    }

    private fun retireRecognizer() {
        val ear = recognizer ?: return
        recognizer = null
        epoch += 1
        runCatching { ear.cancel() }
        runCatching { ear.destroy() }
    }

    private fun textsFrom(bundle: Bundle?): List<String> {
        if (bundle == null) return emptyList()
        val stable = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        val unstableList = bundle.getStringArrayList(UNSTABLE_TEXT).orEmpty()
        val unstable = bundle.getString(UNSTABLE_TEXT)
        return (stable + unstableList + listOfNotNull(unstable)).filter { it.isNotBlank() }
    }

    private fun releaseEar() {
        vox.setEarPaused(false)
    }

    private fun listenIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            if (useLanguage) {
                val language = Locale.getDefault().toLanguageTag()
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            }
            putStringArrayListExtra(BIASING_STRINGS, arrayListOf("Voxy", "hey Voxy"))
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
        }
    }

    private fun promptLine(): String {
        val line = stateHolder.value.line
        if (line.startsWith("Hi ") || line.startsWith("Heard ")) return line
        return LISTENING_LINE
    }

    private fun publish(listening: Boolean, line: String) {
        val next = VoxyState(listening = listening, line = line, background = backgroundEnabled)
        if (next != stateHolder.value) stateHolder.value = next
    }

    private fun needsNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
    }

    private fun turnBackgroundOn(askBattery: Boolean) {
        backgroundEnabled = true
        prefs.edit().putBoolean(PREF_BACKGROUND, true).apply()
        startListenService()
        publish(stateHolder.value.listening, stateHolder.value.line.ifBlank { "Listening. Say Voxy." })
        if (!speaking) scheduleListen(200)
        if (askBattery) askBatteryExemption()
    }

    private fun startListenService() {
        val intent = Intent(appContext, VoxyListenService::class.java)
        runCatching { appContext.startForegroundService(intent) }
            .onFailure { error ->
                Log.w(TAG, "Could not keep Voxy listening in the background", error)
                backgroundEnabled = false
                prefs.edit().putBoolean(PREF_BACKGROUND, false).apply()
                publish(stateHolder.value.listening, "Voxy could not stay on while the phone is locked.")
            }
    }

    private fun stopListenService() {
        val intent = Intent(appContext, VoxyListenService::class.java).setAction(VoxyListenService.ACTION_STOP)
        runCatching { appContext.startService(intent) }
    }

    private fun askBatteryExemption() {
        val power = appContext.getSystemService(PowerManager::class.java) ?: return
        if (power.isIgnoringBatteryOptimizations(appContext.packageName)) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${appContext.packageName}")
        }
        runCatching { activity.startActivity(intent) }
    }

    private fun requestGreetingFocus() {
        val manager = appContext.getSystemService(AudioManager::class.java) ?: return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()
        manager.requestAudioFocus(request)
    }

    private companion object {
        const val TAG = "AirvoxVoxy"
        const val PREFS = "voxy"
        const val PREF_BACKGROUND = "background"
        const val UTTERANCE = "voxy-hi"
        const val LISTENING_LINE = "Listening. Say Voxy."
        const val MIC_LINE = "Voxy needs the microphone. Allow it, then say Voxy."
        const val LOCK_PERMISSION_LINE = "Allow notifications so Voxy can listen while the phone is locked."
        const val UNSTABLE_TEXT = "android.speech.extra.UNSTABLE_TEXT"
        const val BIASING_STRINGS = "android.speech.extra.BIASING_STRINGS"
    }
}
