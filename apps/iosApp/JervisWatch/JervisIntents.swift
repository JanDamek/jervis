import AppIntents
import Foundation

// MARK: - Ask Jervis Intent (text query via Siri on Watch)

@available(watchOS 9.0, *)
struct AskJervisIntent: AppIntent {
    static var title: LocalizedStringResource = "Zeptej se Jervise"
    static var description = IntentDescription("Ask Jervis AI assistant a question via voice or text.")
    static var openAppWhenRun: Bool = false

    @Parameter(title: "Dotaz", requestValueDialog: IntentDialog("Na co se chcete zeptat?"))
    var query: String

    func perform() async throws -> some IntentResult & ProvidesDialog {
        let response = await WatchJervisApiClient.shared.sendChatQuery(query)
        return .result(dialog: IntentDialog(stringLiteral: response))
    }
}

// MARK: - Start Chat Intent (opens chat view on Watch)

@available(watchOS 9.0, *)
struct StartWatchChatIntent: AppIntent {
    static var title: LocalizedStringResource = "Jervis Chat"
    static var description = IntentDescription("Open Jervis chat on Apple Watch.")
    static var openAppWhenRun: Bool = true

    func perform() async throws -> some IntentResult {
        NotificationCenter.default.post(name: .jervisOpenChat, object: nil)
        return .result()
    }
}

// MARK: - Start Recording Intent (opens recording on Watch)

@available(watchOS 9.0, *)
struct StartWatchRecordingIntent: AppIntent {
    static var title: LocalizedStringResource = "Jervis Nahravani"
    static var description = IntentDescription("Start ad-hoc recording on Apple Watch.")
    static var openAppWhenRun: Bool = true

    func perform() async throws -> some IntentResult {
        NotificationCenter.default.post(name: .jervisStartRecording, object: nil)
        return .result()
    }
}

// MARK: - App Shortcuts Provider

@available(watchOS 9.0, *)
struct JervisShortcutsProvider: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: AskJervisIntent(),
            phrases: [
                "Zeptej se \(.applicationName)",
                "Hey \(.applicationName)",
                "Dotaz na \(.applicationName)",
            ],
            shortTitle: "Zeptej se Jervise",
            systemImageName: "bubble.left.fill"
        )
        AppShortcut(
            intent: StartWatchChatIntent(),
            phrases: [
                "Otevri \(.applicationName) chat",
                "\(.applicationName) chat",
            ],
            shortTitle: "Chat",
            systemImageName: "bubble.left.circle.fill"
        )
        AppShortcut(
            intent: StartWatchRecordingIntent(),
            phrases: [
                "Nahravej s \(.applicationName)",
                "\(.applicationName) nahravej",
            ],
            shortTitle: "Nahravej",
            systemImageName: "mic.fill"
        )
    }
}

// MARK: - Notification Names

extension Notification.Name {
    static let jervisStartRecording = Notification.Name("jervisStartRecording")
    static let jervisOpenChat = Notification.Name("jervisOpenChat")
}
