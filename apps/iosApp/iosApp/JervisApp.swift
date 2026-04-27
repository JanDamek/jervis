import SwiftUI
import UserNotifications
import AVFoundation
import JervisShared

@main
struct JervisApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    var body: some Scene {
        WindowGroup {
            ContentView()
                .onReceive(NotificationCenter.default.publisher(for: .jervisStartRecording)) { _ in
                    // Siri triggered recording — handled by Compose UI via deep link or state
                    print("[Jervis] Siri requested recording start")
                }
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

        // Notification action categories are registered by KMP PlatformNotificationManager.initialize()
        // (APPROVAL, MFA_CODE, MFA_CONFIRM) — do NOT duplicate here, setNotificationCategories replaces all.

        // Activate WatchConnectivity for watch app communication
        WatchSessionManager.shared.activate()

        // Wire up watch recording callbacks → relay to server via REST
        WatchSessionManager.shared.onWatchRecordingStarted = {
            print("[Jervis] Watch recording started — creating meeting")
            WatchRecordingRelay.shared.startRecording()
        }
        WatchSessionManager.shared.onAudioChunkReceived = { data, chunkIndex, isLast in
            print("[Jervis] Watch audio chunk \(chunkIndex) received (\(data.count) bytes, isLast=\(isLast))")
            WatchRecordingRelay.shared.addChunk(data: data, chunkIndex: chunkIndex, isLast: isLast)
        }
        WatchSessionManager.shared.onWatchRecordingStopped = {
            print("[Jervis] Watch recording stopped — finalizing meeting")
            WatchRecordingRelay.shared.stopRecording()
        }

        return true
    }

    func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        let hexToken = deviceToken.map { String(format: "%02x", $0) }.joined()
        let deviceId = UIDevice.current.identifierForVendor?.uuidString ?? "unknown"

        print("APNs token: \(hexToken.prefix(8))... device: \(deviceId)")

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
