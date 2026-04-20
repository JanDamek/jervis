import SwiftUI

@main
struct macAppApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    var body: some Scene {
        // Hidden menu-bar style window — Compose Desktop JVM child paints
        // the actual UI. This shell just hosts the AppDelegate for APNs.
        Settings { EmptyView() }
    }
}
