import SwiftUI
import UIKit
import Shared

@main
struct iOSApp: App {
    init() {
        UIView.setAnimationsEnabled(false)
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
