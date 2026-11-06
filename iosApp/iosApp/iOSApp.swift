import SwiftUI
import UIKit
import Shared

@main
struct iOSApp: App {
    init() {
        UIView.setAnimationsEnabled(false)
    }

    private let voice = AirvoxWebRtc()
    private let playback = AirvoxPlayback()

    var body: some Scene {
        WindowGroup {
            ContentView(voice: voice, playback: playback)
        }
    }
}
