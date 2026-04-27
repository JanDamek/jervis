import AppKit
import UserNotifications
import os.log

private let log = OSLog(subsystem: "com.jervis.macApp", category: "push")

private func appLog(_ msg: String) {
    os_log("%{public}s", log: log, type: .default, msg)
}

class AppDelegate: NSObject, NSApplicationDelegate {
    private let socketPath = "/tmp/jervis-macapp-apns.sock"
    private let bridge = SocketBridge(path: "/tmp/jervis-macapp-apns.sock")

    static let CATEGORY_APPROVAL = "APPROVAL"
    static let CATEGORY_MFA_CODE = "MFA_CODE"
    static let CATEGORY_MFA_CONFIRM = "MFA_CONFIRM"
    static let ACTION_APPROVE = "APPROVE"
    static let ACTION_DENY = "DENY"
    static let ACTION_MFA_REPLY = "MFA_REPLY"
    static let ACTION_MFA_CONFIRM = "MFA_CONFIRM"

    func applicationDidFinishLaunching(_ notification: Notification) {
        appLog("[Jervis/macApp] applicationDidFinishLaunching")
        UNUserNotificationCenter.current().delegate = self
        bridge.delegate = self
        bridge.start()
        registerNotificationCategories()

        // .timeSensitive REQUIRED for `aps.interruption-level: "time-sensitive"`
        // to bypass Focus / Do Not Disturb on macOS. Without it Apple silently
        // degrades to active level and the alert is filtered.
        UNUserNotificationCenter.current().requestAuthorization(
            options: [.alert, .badge, .sound, .timeSensitive]
        ) { granted, error in
            if let error = error {
                appLog("[Jervis/macApp] Notification permission error: \(error)")
                return
            }
            if granted {
                DispatchQueue.main.async {
                    appLog("[Jervis/macApp] Calling registerForRemoteNotifications()")
                    NSApplication.shared.registerForRemoteNotifications()
                }
                appLog("[Jervis/macApp] Notification permission granted")
            } else {
                appLog("[Jervis/macApp] Notification permission denied")
            }
        }

        // If we were launched at login (or after wake-on-push) without
        // Jervis.app being open, bring up the parent bundle so the JVM
        // can connect to the socket and pick up tokens / pending actions.
        openHostJervisAppIfNeeded()
    }

    func applicationWillTerminate(_ notification: Notification) {
        bridge.stop()
    }

    func application(
        _ application: NSApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        let hexToken = deviceToken.map { String(format: "%02x", $0) }.joined()
        let deviceId = SocketBridge.hardwareUuid() ?? "unknown-mac"
        appLog("[Jervis/macApp] APNs token: \(hexToken.prefix(8))... device: \(deviceId)")
        bridge.sendToken(hexToken: hexToken, deviceId: deviceId)
    }

    func application(
        _ application: NSApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        appLog("[Jervis/macApp] Failed to register for remote notifications: \(error.localizedDescription)")
    }

    func application(
        _ application: NSApplication,
        didReceiveRemoteNotification userInfo: [String: Any]
    ) {
        bridge.sendPayload(userInfo: userInfo)
    }

    /// Locate the parent Jervis.app — the helper bundle lives at
    ///   `<...>/Jervis.app/Contents/Resources/JervisAPNs.app`
    /// in the production install. Walk three levels up; verify the
    /// candidate ends with `.app` and is not the helper itself.
    /// Fall back to the standard install locations.
    private func locateHostJervisApp() -> URL? {
        let helperURL = Bundle.main.bundleURL
        let candidate = helperURL
            .deletingLastPathComponent()  // Resources/
            .deletingLastPathComponent()  // Contents/
            .deletingLastPathComponent()  // Jervis.app
        if candidate.pathExtension == "app", candidate.lastPathComponent != helperURL.lastPathComponent {
            return candidate
        }

        let fallbacks = [
            URL(fileURLWithPath: "/Applications/Jervis.app"),
            FileManager.default.homeDirectoryForCurrentUser
                .appendingPathComponent("Applications/Jervis.app"),
        ]
        return fallbacks.first { FileManager.default.fileExists(atPath: $0.path) }
    }

    private func openHostJervisAppIfNeeded() {
        guard let host = locateHostJervisApp() else {
            appLog("[Jervis/macApp] Host Jervis.app not found — running in dev mode (JVM is expected to be already running)")
            return
        }
        if isJervisAppRunning(bundleURL: host) {
            appLog("[Jervis/macApp] Host Jervis.app already running")
            return
        }
        appLog("[Jervis/macApp] Opening host Jervis.app at \(host.path)")
        let config = NSWorkspace.OpenConfiguration()
        config.activates = false  // background launch — don't steal focus.
        NSWorkspace.shared.openApplication(at: host, configuration: config) { _, error in
            if let error = error {
                appLog("[Jervis/macApp] Failed to open Jervis.app: \(error.localizedDescription)")
            }
        }
    }

    private func isJervisAppRunning(bundleURL: URL) -> Bool {
        let running = NSWorkspace.shared.runningApplications
        return running.contains { app in
            app.bundleURL == bundleURL || app.bundleIdentifier == "com.jervis.desktop"
        }
    }

    private func registerNotificationCategories() {
        let approve = UNNotificationAction(
            identifier: AppDelegate.ACTION_APPROVE,
            title: "Povolit",
            options: .foreground
        )
        let deny = UNNotificationAction(
            identifier: AppDelegate.ACTION_DENY,
            title: "Zamítnout",
            options: .destructive
        )
        let approval = UNNotificationCategory(
            identifier: AppDelegate.CATEGORY_APPROVAL,
            actions: [approve, deny],
            intentIdentifiers: [],
            options: []
        )

        let mfaReply = UNTextInputNotificationAction(
            identifier: AppDelegate.ACTION_MFA_REPLY,
            title: "Odeslat kód",
            options: .foreground,
            textInputButtonTitle: "Odeslat",
            textInputPlaceholder: "Zadejte MFA kód"
        )
        let mfaCode = UNNotificationCategory(
            identifier: AppDelegate.CATEGORY_MFA_CODE,
            actions: [mfaReply],
            intentIdentifiers: [],
            options: []
        )

        let mfaConfirm = UNNotificationAction(
            identifier: AppDelegate.ACTION_MFA_CONFIRM,
            title: "Potvrdit",
            options: .foreground
        )
        let mfaConfirmCategory = UNNotificationCategory(
            identifier: AppDelegate.CATEGORY_MFA_CONFIRM,
            actions: [mfaConfirm],
            intentIdentifiers: [],
            options: []
        )

        UNUserNotificationCenter.current().setNotificationCategories(
            [approval, mfaCode, mfaConfirmCategory]
        )
    }
}

// MARK: - UNUserNotificationCenterDelegate

extension AppDelegate: UNUserNotificationCenterDelegate {
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound, .badge])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let userInfo = response.notification.request.content.userInfo
        let taskId = userInfo["taskId"] as? String

        switch response.actionIdentifier {
        case AppDelegate.ACTION_APPROVE:
            bridge.sendAction(taskId: taskId, action: "APPROVE", replyText: nil)
        case AppDelegate.ACTION_DENY:
            bridge.sendAction(taskId: taskId, action: "DENY", replyText: nil)
        case AppDelegate.ACTION_MFA_REPLY:
            if let textResponse = response as? UNTextInputNotificationResponse {
                let code = textResponse.userText.trimmingCharacters(in: .whitespacesAndNewlines)
                if !code.isEmpty {
                    bridge.sendAction(taskId: taskId, action: "REPLY", replyText: code)
                }
            }
        case AppDelegate.ACTION_MFA_CONFIRM:
            bridge.sendAction(taskId: taskId, action: "REPLY", replyText: "confirmed")
        case UNNotificationDefaultActionIdentifier:
            bridge.sendAction(taskId: taskId, action: "OPEN", replyText: nil)
        default:
            break
        }

        // Action is queued in the SocketBridge pending list when no JVM
        // is connected. Boot Jervis.app so it picks the queued action up
        // on connect — otherwise approve/deny taps are silently lost
        // when the user opens the Mac, replies in the banner, and never
        // launches Jervis manually.
        if !bridge.isClientConnected() {
            openHostJervisAppIfNeeded()
        }

        completionHandler()
    }
}

// MARK: - SocketBridgeDelegate (JVM → Swift)

extension AppDelegate: SocketBridgeDelegate {
    func socketBridgeDidRequestShowNotification(
        taskId: String?,
        title: String,
        body: String,
        category: String?,
        payload: [String: Any]?
    ) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        if let category = category {
            content.categoryIdentifier = category
        }
        var userInfo: [String: Any] = payload ?? [:]
        if let taskId = taskId {
            userInfo["taskId"] = taskId
        }
        content.userInfo = userInfo

        let identifier = taskId ?? UUID().uuidString
        let request = UNNotificationRequest(
            identifier: identifier,
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request) { error in
            if let error = error {
                appLog("[Jervis/macApp] Failed to show notification: \(error)")
            }
        }
    }

    func socketBridgeDidRequestCancelNotification(taskId: String) {
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [taskId])
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [taskId])
    }

    func socketBridgeDidRequestSetLoginItem(enabled: Bool) {
        if enabled {
            LoginItemManager.register()
        } else {
            LoginItemManager.unregister()
        }
        bridge.sendLoginItemStatus(enabled: LoginItemManager.isEnabled())
    }

    func socketBridgeDidRequestQueryLoginItem() {
        bridge.sendLoginItemStatus(enabled: LoginItemManager.isEnabled())
    }

    func socketBridgeDidRequestFocusJervis() {
        guard let host = locateHostJervisApp() else { return }
        let config = NSWorkspace.OpenConfiguration()
        config.activates = true
        NSWorkspace.shared.openApplication(at: host, configuration: config, completionHandler: nil)
    }
}
