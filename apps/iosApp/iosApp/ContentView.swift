import SwiftUI
import JervisMobile

struct ContentView: View {
    var body: some View {
        // Jervis Compose UI - shared across Android, iOS, Desktop
        // Use localhost for iOS simulator (localhost = host machine)
        ComposeView(serverUrl: "https://home.damek-soft.eu:5500/")
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
