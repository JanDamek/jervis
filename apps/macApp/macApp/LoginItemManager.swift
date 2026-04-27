import Foundation
import ServiceManagement
import os.log

private let log = OSLog(subsystem: "com.jervis.macApp", category: "loginitem")

private func appLog(_ msg: String) {
    os_log("%{public}s", log: log, type: .default, msg)
}

/// Wraps `SMAppService.mainApp` (macOS 13+) so the helper can register
/// itself as a Login Item. The helper is the persistent component:
/// once registered it boots after every login, opens the parent
/// Jervis.app, and stays online so APNs registration + interactive
/// notification actions keep working without the user opening Jervis
/// manually first.
enum LoginItemManager {
    static func register() {
        if #available(macOS 13.0, *) {
            do {
                try SMAppService.mainApp.register()
                appLog("[Jervis/macApp] Login item registered")
            } catch {
                appLog("[Jervis/macApp] Login item register failed: \(error.localizedDescription)")
            }
        } else {
            appLog("[Jervis/macApp] Login items require macOS 13+")
        }
    }

    static func unregister() {
        if #available(macOS 13.0, *) {
            do {
                try SMAppService.mainApp.unregister()
                appLog("[Jervis/macApp] Login item unregistered")
            } catch {
                appLog("[Jervis/macApp] Login item unregister failed: \(error.localizedDescription)")
            }
        }
    }

    /// Returns true when the helper is enabled to launch on login.
    static func isEnabled() -> Bool {
        if #available(macOS 13.0, *) {
            return SMAppService.mainApp.status == .enabled
        }
        return false
    }
}
