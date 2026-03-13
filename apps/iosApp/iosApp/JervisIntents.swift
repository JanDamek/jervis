import AppIntents
import Foundation

// MARK: - Ask Jervis Intent (text query via Siri)

@available(iOS 16.0, watchOS 9.0, *)
struct AskJervisIntent: AppIntent {
    static var title: LocalizedStringResource = "Zeptej se Jervise"
    static var description = IntentDescription("Ask Jervis AI assistant a question via voice or text.")
    static var openAppWhenRun: Bool = false

    @Parameter(title: "Dotaz", requestValueDialog: IntentDialog("Na co se chcete zeptat?"))
    var query: String

    func perform() async throws -> some IntentResult & ProvidesDialog {
        let response = await JervisApiClient.shared.sendChatQuery(query)
        return .result(dialog: IntentDialog(stringLiteral: response))
    }
}

// MARK: - Start Recording Intent

@available(iOS 16.0, watchOS 9.0, *)
struct StartRecordingIntent: AppIntent {
    static var title: LocalizedStringResource = "Zacni nahravat"
    static var description = IntentDescription("Start ad-hoc audio recording with Jervis.")
    static var openAppWhenRun: Bool = true

    func perform() async throws -> some IntentResult {
        NotificationCenter.default.post(name: .jervisStartRecording, object: nil)
        return .result()
    }
}

// MARK: - App Shortcuts Provider

@available(iOS 16.0, watchOS 9.0, *)
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
            intent: StartRecordingIntent(),
            phrases: [
                "Nahravej s \(.applicationName)",
                "\(.applicationName) nahravej",
                "Zacni nahravat s \(.applicationName)",
            ],
            shortTitle: "Nahravej",
            systemImageName: "mic.fill"
        )
    }
}

// MARK: - Notification Names

extension Notification.Name {
    static let jervisStartRecording = Notification.Name("jervisStartRecording")
}
