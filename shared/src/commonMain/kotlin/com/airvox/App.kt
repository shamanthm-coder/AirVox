package com.airvox

import androidx.compose.runtime.Composable
import com.airvox.auth.AuthClient
import com.airvox.auth.UnavailableAuthClient
import com.airvox.credits.CreditWallet
import com.airvox.bluetooth.BluetoothEarphoneScanner
import com.airvox.bluetooth.UnavailableBluetoothEarphoneScanner
import com.airvox.intercom.IntercomSession
import com.airvox.intercom.UnavailableIntercomSession
import com.airvox.location.PlaceReporter
import com.airvox.location.UnavailablePlaceReporter
import com.airvox.map.RiderMap
import com.airvox.map.UnavailableRiderMap
import com.airvox.playback.PlaybackController
import com.airvox.playback.UnavailablePlaybackController
import com.airvox.theme.AirvoxTheme
import com.airvox.ui.AirvoxApp
import com.airvox.vox.UnavailableVox
import com.airvox.vox.Vox

@Composable
fun App(
    session: IntercomSession = UnavailableIntercomSession(),
    playback: PlaybackController = UnavailablePlaybackController(),
    bluetoothScanner: BluetoothEarphoneScanner = UnavailableBluetoothEarphoneScanner(),
    vox: Vox = UnavailableVox(),
    places: PlaceReporter = UnavailablePlaceReporter(),
    map: RiderMap = UnavailableRiderMap(),
    auth: AuthClient = UnavailableAuthClient(),
    wallet: CreditWallet = CreditWallet(),
    bindWallet: (String) -> Unit = {},
) {
    AirvoxTheme {
        AirvoxApp(
            session = session,
            playback = playback,
            bluetoothScanner = bluetoothScanner,
            vox = vox,
            places = places,
            map = map,
            auth = auth,
            wallet = wallet,
            bindWallet = bindWallet,
        )
    }
}
