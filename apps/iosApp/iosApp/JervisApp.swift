import SwiftUI
import UserNotifications
import AVFoundation
import JervisMobile

@main
struct JervisApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

class AppDelegate: NSObject, UIApplicationDelegate {
    let notificationDelegate = NotificationDelegate()

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        UNUserNotificationCenter.current().delegate = notificationDelegate

        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, options: [.defaultToSpeaker, .allowBluetooth])
            try session.setActive(true)
        } catch {
            print("Failed to configure audio session: \(error)")
        }

        // Request notification permission and register for remote notifications
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound]) { granted, error in
            if let error = error {
                print("Notification permission error: \(error)")
                return
            }
            if granted {
                DispatchQueue.main.async {
                    application.registerForRemoteNotifications()
                }
                print("Notification permission granted")
            } else {
                print("Notification permission denied")
            }
        }

        // Register notification action categories (Approve/Deny for approval notifications)
        let approveAction = UNNotificationAction(identifier: "APPROVE", title: "Schválit", options: [.authenticationRequired])
        let denyAction = UNNotificationAction(identifier: "DENY", title: "Zamítnout", options: [.authenticationRequired, .destructive])
        let approvalCategory = UNNotificationCategory(
            identifier: "APPROVAL",
            actions: [approveAction, denyAction],
            intentIdentifiers: [],
            options: []
        )
        UNUserNotificationCenter.current().setNotificationCategories([approvalCategory])

        return true
    }

    func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        let hexToken = deviceToken.map { String(format: "%02x", $0) }.joined()
        let deviceId = UIDevice.current.identifierForVendor?.uuidString ?? "unknown"

        print("APNs token received: \(hexToken.prefix(16))..., device: \(deviceId)")

        // Pass token to KMP via IosTokenHolder
        IosTokenHolder.shared.setToken(hexToken: hexToken, deviceId: deviceId)
    }

    func application(
        _ application: UIApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        print("Failed to register for remote notifications: \(error.localizedDescription)")
    }
}
