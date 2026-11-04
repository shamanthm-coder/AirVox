package com.airvox

import androidx.compose.runtime.remember
import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.window.ComposeUIViewController
import com.airvox.auth.IosAuthClient
import com.airvox.credits.CreditWallet
import com.airvox.credits.bindUserCredits
import com.airvox.location.IosPlaceReporter

fun MainViewController() = ComposeUIViewController(
    // The default waits to slide the field above the keyboard before showing it.
    // On iPhone that wait is about 10 seconds. Show the keyboard immediately.
    configure = {
        onFocusBehavior = OnFocusBehavior.DoNothing
    },
) {
    val places = remember { IosPlaceReporter() }
    val auth = remember { IosAuthClient(IosConfig.FIREBASE_API_KEY, IosConfig.AIRVOX_API_URL) }
    val wallet = remember { CreditWallet() }
    App(
        places = places,
        auth = auth,
        wallet = wallet,
        bindWallet = { userId -> bindUserCredits(wallet, userId) },
    )
}
