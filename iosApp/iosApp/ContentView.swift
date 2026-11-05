import SwiftUI
import Shared

struct ComposeView: UIViewControllerRepresentable {
    let voice: AirvoxWebRtc

    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(voice: voice)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    let voice: AirvoxWebRtc

    var body: some View {
        ComposeView(voice: voice)
            .ignoresSafeArea(.container, edges: .all)
    }
}
