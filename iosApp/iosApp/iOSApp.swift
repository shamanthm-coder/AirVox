import SwiftUI
import UIKit
import Shared

@main
struct iOSApp: App {
    init() {
        // The keyboard animation resizes the screen on every frame. On this phone
        // that loop holds the tap for about 30 seconds. Show the keyboard at once.
        UIView.setAnimationsEnabled(false)
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
