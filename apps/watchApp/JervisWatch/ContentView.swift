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
                    ChatView(connectivity: connectivity, autoStart: autoStart) {
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
        .overlay(alignment: .bottom) {
            // Live assist hint card — compact, auto-dismiss 10s, tap to expand
            if let hint = connectivity.activeHint {
                HintCardView(hint: hint) {
                    connectivity.activeHint = nil
                }
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .animation(.easeInOut(duration: 0.3), value: connectivity.activeHint != nil)
            }
        }
    }
}

/// Compact hint card for live assist — shown over any screen
struct HintCardView: View {
    let hint: WatchConnectivityManager.HintInfo
    let onDismiss: () -> Void
    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Image(systemName: "lightbulb.fill")
                    .font(.caption2)
                    .foregroundStyle(.yellow)
                Text("Hint")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                Spacer()
                Button(action: onDismiss) {
                    Image(systemName: "xmark")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
            }
            Text(hint.text)
                .font(.caption)
                .lineLimit(expanded ? nil : 2)
                .onTapGesture { expanded.toggle() }
        }
        .padding(8)
        .background(
            RoundedRectangle(cornerRadius: 10)
                .fill(Color.black.opacity(0.6))
        )
        .padding(.horizontal, 4)
        .padding(.bottom, 2)
    }
}
