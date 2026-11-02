package com.airvox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.graphics.toColorInt
import com.airvox.bluetooth.AndroidBluetoothEarphoneScanner
import com.airvox.intercom.AndroidIntercomSession
import com.airvox.location.AndroidPlaceReporter
import com.airvox.map.AndroidRiderMap
import com.airvox.playback.AndroidPlaybackController
import com.airvox.vox.AndroidVox

class MainActivity : ComponentActivity() {
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
        places.dispose()
        vox.dispose()
        intercomSession.dispose()
        bluetoothScanner.dispose()
        playbackController.dispose()
        super.onDestroy()
    }
}
