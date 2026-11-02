package com.airvox

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import com.airvox.location.IosPlaceReporter

fun MainViewController() = ComposeUIViewController {
    val places = remember { IosPlaceReporter() }
    App(places = places)
}
