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

    var body: some View {
        VStack(spacing: 12) {
            if let response = responseText {
                // Response received — show text + play TTS
                Spacer()

                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 30))
                    .foregroundColor(.green)

                ScrollView {
                    Text(response)
                        .font(.caption)
                        .foregroundColor(.primary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 4)
                }

                Spacer()

                HStack(spacing: 16) {
                    // Ask again
                    Button(action: { responseText = nil }) {
                        Image(systemName: "mic.circle.fill")
                            .font(.system(size: 30))
                            .foregroundColor(.blue)
                    }
                    .buttonStyle(.plain)

                    // Close
                    Button(action: onDismiss) {
                        Image(systemName: "xmark.circle")
                            .font(.system(size: 30))
                            .foregroundColor(.secondary)
                    }
                    .buttonStyle(.plain)
                }
            } else if isProcessing {
                Spacer()

                ProgressView()
                    .scaleEffect(1.5)
                Text("Cekam na odpoved od JERVISe...")
                    .font(.caption)
                    .foregroundColor(.secondary)

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
                    let result = await WatchJervisApiClient.shared.sendVoiceCommand(audioData)
                    await MainActor.run {
                        isProcessing = false
                        responseText = result.text

                        // Play TTS audio if available
                        if let ttsData = result.ttsAudioData {
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
