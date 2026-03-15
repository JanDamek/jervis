import SwiftUI

/// Wraps .symbolEffect(.pulse) with availability check for watchOS 10+
struct PulseEffectModifier: ViewModifier {
    let isActive: Bool

    func body(content: Content) -> some View {
        if #available(watchOS 10.0, *) {
            content.symbolEffect(.pulse, isActive: isActive)
        } else {
            content.opacity(isActive ? 0.6 : 1.0)
        }
    }
}

struct ChatView: View {
    @ObservedObject var chatManager: WatchChatManager
    @ObservedObject var connectivity: WatchConnectivityManager
    var autoStart: Bool = false
    var onDismiss: () -> Void

    @State private var isListening = false
    @State private var responseText: String? = nil
    @State private var isProcessing = false
    @State private var statusText = "Cekam na odpoved od JERVISe..."

    var body: some View {
        VStack(spacing: 12) {
            if let response = responseText {
                // Response — compact layout, max space for text
                HStack(spacing: 6) {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 14))
                        .foregroundColor(.green)
                    Text("Jervis")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                    Spacer()
                    Button(action: { responseText = nil }) {
                        Image(systemName: "mic.fill")
                            .font(.caption)
                            .foregroundColor(.blue)
                    }
                    .buttonStyle(.plain)
                    Button(action: onDismiss) {
                        Image(systemName: "xmark")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    .buttonStyle(.plain)
                }

                ScrollView {
                    Text(response)
                        .font(.body)
                        .foregroundColor(.primary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 2)
                }
            } else if isProcessing {
                Spacer()

                ProgressView()
                    .scaleEffect(1.5)
                Text(statusText)
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)

                Spacer()
            } else {
                Spacer()

                Text(isListening ? "Posloucham..." : "Klepni a mluv")
                    .font(.caption)
                    .foregroundColor(.secondary)

                Button(action: toggleListening) {
                    Image(systemName: isListening ? "waveform.circle.fill" : "mic.circle.fill")
                        .font(.system(size: 56))
                        .foregroundColor(isListening ? .green : .blue)
                        .modifier(PulseEffectModifier(isActive: isListening))
                }
                .buttonStyle(.plain)

                Spacer()

                Button("Zpet", action: onDismiss)
                    .font(.caption)
            }
        }
        .navigationTitle("Chat")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            if autoStart && !isListening && !isProcessing {
                isListening = true
                chatManager.startListening()
            }
        }
    }

    private func toggleListening() {
        if isListening {
            isListening = false
            isProcessing = true

            if let audioData = chatManager.stopListening() {
                Task {
                    let info = ProcessInfo.processInfo
                    info.performExpiringActivity(withReason: "Sending voice to Jervis") { expired in
                        if expired { return }
                    }

                    // SSE status updates → live UI feedback
                    WatchJervisApiClient.shared.onStatusUpdate = { status in
                        statusText = status
                    }

                    let result = await WatchJervisApiClient.shared.sendVoiceCommand(audioData)
                    await MainActor.run {
                        isProcessing = false
                        responseText = result.text

                        if let ttsData = result.ttsAudioData {
                            print("[Chat] TTS audio: \(ttsData.count) bytes")
                            WatchJervisApiClient.shared.playTtsAudio(ttsData)
                        }
                    }
                }
            } else {
                isProcessing = false
                responseText = "Zadna nahravka"
            }
        } else {
            isListening = true
            chatManager.startListening()
        }
    }
}
