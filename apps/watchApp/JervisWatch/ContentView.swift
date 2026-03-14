import SwiftUI
import AVFoundation

struct ContentView: View {
    @EnvironmentObject var connectivity: WatchConnectivityManager
    @StateObject private var recorder = WatchAudioRecorder()
    @StateObject private var chatManager = WatchChatManager()
    @State private var activeMode: ActiveMode? = nil
    @State private var autoStart = false

    enum ActiveMode {
        case recording, chat
    }

    var body: some View {
        NavigationStack {
            if let mode = activeMode {
                switch mode {
                case .recording:
                    RecordingView(recorder: recorder, connectivity: connectivity, autoStart: autoStart) {
                        activeMode = nil
                        autoStart = false
                    }
                case .chat:
                    ChatView(chatManager: chatManager, connectivity: connectivity, autoStart: autoStart) {
                        activeMode = nil
                        autoStart = false
                    }
                }
            } else {
                VStack(spacing: 12) {
                    Text("Jervis")
                        .font(.title3)
                        .fontWeight(.semibold)
                        .foregroundStyle(.blue)

                    Button(action: { activeMode = .recording }) {
                        HStack(spacing: 10) {
                            Image(systemName: "mic.fill")
                                .font(.title3)
                                .foregroundStyle(.white)
                            Text("Nahravani")
                                .font(.body)
                                .foregroundStyle(.white)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                    }
                    .background(Color.red.opacity(0.85))
                    .clipShape(RoundedRectangle(cornerRadius: 12))

                    Button(action: { activeMode = .chat }) {
                        HStack(spacing: 10) {
                            Image(systemName: "bubble.left.fill")
                                .font(.title3)
                                .foregroundStyle(.white)
                            Text("Chat")
                                .font(.body)
                                .foregroundStyle(.white)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                    }
                    .background(Color.blue.opacity(0.85))
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                }
                .padding(.horizontal, 8)
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .jervisStartRecording)) { _ in
            activeMode = .recording
        }
        .onReceive(NotificationCenter.default.publisher(for: .jervisOpenChat)) { _ in
            activeMode = .chat
        }
        .onOpenURL { url in
            autoStart = true
            switch url.host {
            case "recording":
                activeMode = .recording
            case "chat":
                activeMode = .chat
            default:
                autoStart = false
            }
        }
    }
}
