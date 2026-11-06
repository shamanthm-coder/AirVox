import SwiftUI
import Shared

struct ComposeView: UIViewControllerRepresentable {
    let voice: AirvoxWebRtc
    let playback: AirvoxPlayback

    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(voice: voice, playback: playback)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    let voice: AirvoxWebRtc
    let playback: AirvoxPlayback

    var body: some View {
        ComposeView(voice: voice, playback: playback)
            .ignoresSafeArea(.container, edges: .all)
    }
}
