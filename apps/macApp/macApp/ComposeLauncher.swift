import Foundation

/// Stub — the Compose Desktop JVM runs as its own jpackage-produced
/// `Jervis.app` (see `apps/desktop`). macApp stays focused on APNs +
/// background notification relay: it hosts the entitlement, the socket
/// bridge, and the menubar helper. The JVM client dials
/// `/tmp/jervis-macapp-apns.sock` on its own startup if it finds the
/// `JERVIS_MACAPP_SOCKET` env variable (or the hard-coded default).
///
/// Kept as a named type so future embedding (say, via JNI from within
/// the macApp process) has a clear landing spot — but no spawning for
/// now, jpackage launchers can't be nested inside another `.app`.
final class ComposeLauncher {
    func launch(socketPath: String) {
        print("[Jervis/macApp] APNs bridge ready at \(socketPath) — run the Compose Desktop Jervis.app separately to connect.")
    }

    func terminate() {}
}
