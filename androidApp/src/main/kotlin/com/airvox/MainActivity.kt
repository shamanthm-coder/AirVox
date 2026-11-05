package com.airvox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.graphics.toColorInt
import android.content.Intent
import com.airvox.auth.AndroidAuthClient
import com.airvox.credits.CreditBalance
import com.airvox.credits.CreditWallet
import com.airvox.credits.bindUserCredits
import com.airvox.credits.markOffsetRemoved
import com.airvox.credits.offsetAlreadyRemoved
import com.airvox.auth.UnavailableAuthClient
import com.airvox.bluetooth.AndroidBluetoothEarphoneScanner
import com.airvox.intercom.AndroidIntercomSession
import com.airvox.location.AndroidPlaceReporter
import com.airvox.map.AndroidRiderMap
import com.airvox.playback.AndroidPlaybackController
import com.airvox.vox.AndroidVox

class MainActivity : ComponentActivity() {
    private var androidAuth: AndroidAuthClient? = null
    private lateinit var bluetoothScanner: AndroidBluetoothEarphoneScanner
    private lateinit var playbackController: AndroidPlaybackController
    private lateinit var intercomSession: AndroidIntercomSession
    private lateinit var vox: AndroidVox
    private lateinit var places: AndroidPlaceReporter
    private lateinit var riderMap: AndroidRiderMap

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playbackController = AndroidPlaybackController(this)
        intercomSession = AndroidIntercomSession(
            activity = this,
            playback = playbackController,
        )
        vox = AndroidVox(
            activity = this,
            session = intercomSession,
            playback = playbackController,
        )
        places = AndroidPlaceReporter(
            activity = this,
            session = intercomSession,
        )
        riderMap = AndroidRiderMap(this)
        bluetoothScanner = AndroidBluetoothEarphoneScanner(this)
        val credits = CreditWallet(CreditBalance(credits = 0))
        val skipAuth = BuildConfig.DEBUG && intent.getBooleanExtra(SKIP_AUTH, false)
        val auth = if (skipAuth) {
            UnavailableAuthClient()
        } else {
            AndroidAuthClient(
                activity = this,
                apiUrl = BuildConfig.AIRVOX_API_URL,
                webClientId = BuildConfig.FIREBASE_WEB_CLIENT_ID,
            ).also { androidAuth = it }
        }
        val background = "#070708".toColorInt()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(background),
            navigationBarStyle = SystemBarStyle.dark(background),
        )
        setContent {
            App(
                session = intercomSession,
                playback = playbackController,
                bluetoothScanner = bluetoothScanner,
                vox = vox,
                places = places,
                map = riderMap,
                auth = auth,
                wallet = credits,
                bindWallet = { userId -> bindUserCredits(this@MainActivity, credits, userId) },
                offsetAlreadyRemoved = { userId, offset ->
                    offsetAlreadyRemoved(this@MainActivity, userId, offset)
                },
                markOffsetRemoved = { userId, offset ->
                    markOffsetRemoved(this@MainActivity, userId, offset)
                },
            )
        }
        bluetoothScanner.refreshAudioOutput()
        playbackController.refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::playbackController.isInitialized) playbackController.refresh()
        if (::places.isInitialized) places.refresh()
    }

    override fun onDestroy() {
        androidAuth?.dispose()
        places.dispose()
        vox.dispose()
        intercomSession.dispose()
        bluetoothScanner.dispose()
        playbackController.dispose()
        super.onDestroy()
    }

    companion object {
        const val SKIP_AUTH = "airvox.skipAuth"

        fun bypassAuthIntent(): Intent {
            return Intent().setClassName("com.airvox", "com.airvox.MainActivity")
                .putExtra(SKIP_AUTH, true)
        }
    }
}
