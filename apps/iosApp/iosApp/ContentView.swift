import SwiftUI
import JervisMobile

struct ContentView: View {
    var body: some View {
        // Jervis Compose UI - shared across Android, iOS, Desktop
        // Use localhost for iOS simulator (localhost = host machine)
        ComposeView(serverUrl: "https://jervis.damek-soft.eu/")
            .ignoresSafeArea()
    }
}

struct ComposeView: UIViewControllerRepresentable {
    let serverUrl: String

    func makeUIViewController(context: Context) -> UIViewController {
        return MainKt.MainViewController(serverBaseUrl: serverUrl)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
    }
}

#Preview {
    ContentView()
}
