package com.airvox

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import com.airvox.auth.IosAuthClient
import com.airvox.location.IosPlaceReporter

fun MainViewController() = ComposeUIViewController {
    val places = remember { IosPlaceReporter() }
    val auth = remember { IosAuthClient(IosConfig.FIREBASE_API_KEY, IosConfig.AIRVOX_API_URL) }
    App(places = places, auth = auth)
}
