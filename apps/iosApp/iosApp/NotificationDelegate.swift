import Foundation
import UserNotifications
import JervisMobile

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
        case UNNotificationDefaultActionIdentifier:
            // User tapped the notification itself → open app
            NotificationBridge.shared.handleAction(taskId: taskId, action: "OPEN", replyText: nil)
        default:
            break
        }

        completionHandler()
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
