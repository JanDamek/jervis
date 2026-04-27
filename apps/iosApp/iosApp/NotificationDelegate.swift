import Foundation
import UserNotifications
import JervisShared

/**
 * Handles notification interactions on iOS.
 *
 * When user taps action buttons (Approve/Deny) on approval notifications,
 * forwards the action to KMP via NotificationBridge.
 *
 * Also ensures notifications are displayed even when app is in foreground.
 */
class NotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    /// Handle notification action response (button tap or notification tap)
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let userInfo = response.notification.request.content.userInfo
        let taskId = userInfo["taskId"] as? String

        switch response.actionIdentifier {
        case "APPROVE":
            NotificationBridge.shared.handleAction(taskId: taskId, action: "APPROVE", replyText: nil)
        case "DENY":
            NotificationBridge.shared.handleAction(taskId: taskId, action: "DENY", replyText: nil)
        case "MFA_REPLY":
            // Text input MFA code reply
            if let textResponse = response as? UNTextInputNotificationResponse {
                let code = textResponse.userText.trimmingCharacters(in: .whitespacesAndNewlines)
                if !code.isEmpty {
                    NotificationBridge.shared.handleAction(taskId: taskId, action: "REPLY", replyText: code)
                }
            }
        case "MFA_CONFIRM":
            // Authenticator number / phone call confirmed
            NotificationBridge.shared.handleAction(taskId: taskId, action: "REPLY", replyText: "confirmed")
        case "LOGIN_NOW":
            postLoginConsentAction(userInfo: userInfo, action: "now")
        case "LOGIN_DEFER_15":
            postLoginConsentAction(userInfo: userInfo, action: "defer_15")
        case "LOGIN_DEFER_60":
            postLoginConsentAction(userInfo: userInfo, action: "defer_60")
        case "LOGIN_CANCEL":
            postLoginConsentAction(userInfo: userInfo, action: "cancel")
        case UNNotificationDefaultActionIdentifier:
            // User tapped the notification itself → open app
            NotificationBridge.shared.handleAction(taskId: taskId, action: "OPEN", replyText: nil)
        default:
            break
        }

        completionHandler()
    }

    /// POST the login-consent action directly to the Jervis server. Login
    /// consent doesn't go through KMP NotificationBridge because the
    /// server-side flow keys on `requestId`, not `taskId` — there is no
    /// TaskDocument backing the consent request.
    private func postLoginConsentAction(userInfo: [AnyHashable: Any], action: String) {
        guard let requestId = userInfo["requestId"] as? String, !requestId.isEmpty else {
            print("[Jervis] login-consent action missing requestId")
            return
        }
        let baseUrl = ProcessInfo.processInfo.environment["JERVIS_SERVER_URL"]
            ?? "https://jervis.damek-soft.eu"
        guard let url = URL(string: "\(baseUrl)/api/v1/login-consent/\(requestId)/respond") else { return }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let body: [String: String] = ["action": action]
        req.httpBody = try? JSONSerialization.data(withJSONObject: body)
        URLSession.shared.dataTask(with: req) { _, _, error in
            if let error = error {
                print("[Jervis] login-consent POST failed: \(error)")
            }
        }.resume()
    }

    /// Show notification banner even when app is in foreground
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound, .badge])
    }
}
