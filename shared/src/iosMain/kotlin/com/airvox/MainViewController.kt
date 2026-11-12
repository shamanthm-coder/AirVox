package com.airvox

import androidx.compose.runtime.remember
import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.window.ComposeUIViewController
import com.airvox.auth.IosAuthClient
import com.airvox.credits.CreditWallet
import com.airvox.credits.bindUserCredits
import com.airvox.intercom.IosIntercomSession
import com.airvox.intercom.IosVoiceCall
import com.airvox.location.IosPlaceReporter
import com.airvox.map.IosRiderMap
import com.airvox.playback.IosPlaybackController
import com.airvox.playback.IosSystemPlayback
import com.airvox.vox.IosVox

fun MainViewController(voice: IosVoiceCall, playback: IosSystemPlayback) = ComposeUIViewController(
    // The default waits to slide the field above the keyboard before showing it.
    // On iPhone that wait is about 10 seconds. Show the keyboard immediately.
    configure = {
        onFocusBehavior = OnFocusBehavior.DoNothing
    },
) {
    val auth = remember { IosAuthClient(IosConfig.FIREBASE_API_KEY, IosConfig.AIRVOX_API_URL) }
    val wallet = remember { CreditWallet() }
    val session = remember { IosIntercomSession(voice) }
    val places = remember { IosPlaceReporter(session) }
    val map = remember { IosRiderMap() }
    val vox = remember { IosVox(session) }
    val nowPlaying = remember(playback) { IosPlaybackController(playback) }
    App(
        session = session,
        playback = nowPlaying,
        vox = vox,
        places = places,
        map = map,
        auth = auth,
        wallet = wallet,
        bindWallet = { userId -> bindUserCredits(wallet, userId) },
    )
}
