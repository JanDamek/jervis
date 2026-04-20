import AppKit
import UserNotifications

/// macOS AppDelegate — registers APNs and relays tokens + incoming
/// notification payloads to the Compose Desktop JVM child over a Unix
/// socket.
///
/// The Swift host is the main process (`NSApplication`). The JVM is
/// spawned by `ComposeLauncher` shortly after `applicationDidFinishLaunching`
/// so that it can pick the APNs token up on startup through the socket.
class AppDelegate: NSObject, NSApplicationDelegate {
    private let socketPath = "/tmp/jervis-macapp-apns.sock"
    private let bridge = SocketBridge(path: "/tmp/jervis-macapp-apns.sock")
    private let composeLauncher = ComposeLauncher()

    func applicationDidFinishLaunching(_ notification: Notification) {
        UNUserNotificationCenter.current().delegate = self
        bridge.start()

        UNUserNotificationCenter.current().requestAuthorization(
            options: [.alert, .badge, .sound]
        ) { granted, error in
            if let error = error {
                print("[Jervis/macApp] Notification permission error: \(error)")
                return
            }
            if granted {
                DispatchQueue.main.async {
                    NSApplication.shared.registerForRemoteNotifications()
                }
                print("[Jervis/macApp] Notification permission granted")
            } else {
                print("[Jervis/macApp] Notification permission denied")
            }
        }

        composeLauncher.launch(socketPath: socketPath)
    }

    func applicationWillTerminate(_ notification: Notification) {
        composeLauncher.terminate()
        bridge.stop()
    }

    func application(
        _ application: NSApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        let hexToken = deviceToken.map { String(format: "%02x", $0) }.joined()
        // Stable device identifier on macOS — hardware UUID via IOKit.
        let deviceId = SocketBridge.hardwareUuid() ?? "unknown-mac"
        print("[Jervis/macApp] APNs token: \(hexToken.prefix(8))... device: \(deviceId)")
        bridge.sendToken(hexToken: hexToken, deviceId: deviceId)
    }

    func application(
        _ application: NSApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        print("[Jervis/macApp] Failed to register for remote notifications: \(error.localizedDescription)")
    }

    func application(
        _ application: NSApplication,
        didReceiveRemoteNotification userInfo: [String: Any]
    ) {
        // Display a local notification and forward the payload to the JVM
        // so the Compose UI can act on it while the window is visible.
        bridge.sendPayload(userInfo: userInfo)

        let content = UNMutableNotificationContent()
        content.title = (userInfo["title"] as? String) ?? "Jervis"
        content.body = (userInfo["body"] as? String) ?? ""
        content.sound = .default
        let request = UNNotificationRequest(
            identifier: UUID().uuidString,
            content: content,
            trigger: nil,
        )
        UNUserNotificationCenter.current().add(request)
    }
}

extension AppDelegate: UNUserNotificationCenterDelegate {
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        // Always show banners while the Compose window has focus.
        completionHandler([.banner, .sound, .badge])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        bridge.sendPayload(userInfo: response.notification.request.content.userInfo as? [String: Any] ?? [:])
        completionHandler()
    }
}
