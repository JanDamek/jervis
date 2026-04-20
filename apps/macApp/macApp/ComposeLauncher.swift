import Foundation

/// Spawns the Compose Desktop JVM child inside the same `.app` bundle.
///
/// The bundled jpackage output lives under
/// `macApp.app/Contents/Resources/JervisDesktop/`. We launch the JRE
/// shipped by jpackage with `-cp` pointing at the app jar so the JVM
/// process inherits the bundle's signing + sandbox — it does NOT get
/// APNs entitlements on its own (only Swift parent holds them), but it
/// can open our Unix socket and pick up the token.
final class ComposeLauncher {
    private var process: Process?

    func launch(socketPath: String) {
        guard let bundle = bundleRuntime() else {
            print("[Jervis/macApp] Compose Desktop runtime not found in bundle — run `./gradlew :apps:desktop:packageDistributionForCurrentOS` and copy the dmg contents into macApp.app/Contents/Resources/JervisDesktop/")
            return
        }

        let javaBinary = bundle.appendingPathComponent("Contents/runtime/Contents/Home/bin/java")
        let appJar = bundle.appendingPathComponent("Contents/app/jervis-desktop.jar")

        guard FileManager.default.fileExists(atPath: javaBinary.path) else {
            print("[Jervis/macApp] Missing JRE at \(javaBinary.path)")
            return
        }

        let proc = Process()
        proc.executableURL = javaBinary
        proc.arguments = [
            "-Djervis.server.url=https://jervis.damek-soft.eu/",
            "-Djervis.macapp.socket=\(socketPath)",
            "-cp",
            appJar.path + ":" + bundle.appendingPathComponent("Contents/app/*").path,
            "com.jervis.desktop.MainKt",
        ]
        proc.environment = (ProcessInfo.processInfo.environment as [String: String]).merging([
            "JERVIS_MACAPP_SOCKET": socketPath,
        ]) { _, new in new }

        do {
            try proc.run()
            process = proc
            print("[Jervis/macApp] Compose Desktop JVM started (pid=\(proc.processIdentifier))")
        } catch {
            print("[Jervis/macApp] Failed to start Compose Desktop JVM: \(error)")
        }
    }

    func terminate() {
        process?.terminate()
        process = nil
    }

    private func bundleRuntime() -> URL? {
        guard let resources = Bundle.main.resourceURL?.appendingPathComponent("JervisDesktop") else { return nil }
        return FileManager.default.fileExists(atPath: resources.path) ? resources : nil
    }
}
