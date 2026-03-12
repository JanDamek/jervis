import SwiftUI
import AVFoundation

struct ContentView: View {
    @EnvironmentObject var connectivity: WatchConnectivityManager
    @StateObject private var recorder = WatchAudioRecorder()
    @StateObject private var chatManager = WatchChatManager()
    @State private var activeMode: ActiveMode? = nil

    enum ActiveMode {
        case recording, chat
    }

    var body: some View {
        NavigationStack {
            if let mode = activeMode {
                switch mode {
                case .recording:
                    RecordingView(recorder: recorder, connectivity: connectivity) {
                        activeMode = nil
                    }
                case .chat:
                    ChatView(chatManager: chatManager, connectivity: connectivity) {
                        activeMode = nil
                    }
                }
            } else {
                VStack(spacing: 16) {
                    Text("Jervis")
                        .font(.headline)
                        .foregroundColor(.blue)

                    Button(action: { activeMode = .recording }) {
                        VStack(spacing: 4) {
                            Image(systemName: "mic.circle.fill")
                                .font(.system(size: 36))
                            Text("Ad-hoc")
                                .font(.caption)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.red)

                    Button(action: { activeMode = .chat }) {
                        VStack(spacing: 4) {
                            Image(systemName: "bubble.left.circle.fill")
                                .font(.system(size: 36))
                            Text("Chat")
                                .font(.caption)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.blue)
                }
                .padding()
            }
        }
    }
}
