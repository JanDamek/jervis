import SwiftUI

struct ChatView: View {
    @ObservedObject var chatManager: WatchChatManager
    @ObservedObject var connectivity: WatchConnectivityManager
    var autoStart: Bool = false
    var onDismiss: () -> Void

    @State private var isListening = false
    @State private var responseText: String? = nil
    @State private var isProcessing = false
    @State private var statusText = "Čekám na odpověď..."

    var body: some View {
        VStack(spacing: 8) {
            if let response = responseText {
                // Response view
                HStack(spacing: 6) {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 14))
                        .foregroundColor(.green)
                    Text("Jervis")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                    Spacer()
                    Button(action: { responseText = nil; isListening = false }) {
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
            } else if isListening {
                // Recording mode — stop + cancel
                Spacer()

                Image(systemName: "waveform.circle.fill")
                    .font(.system(size: 44))
                    .foregroundColor(.green)

                Text("Poslouchám...")
                    .font(.caption)
                    .foregroundColor(.secondary)

                HStack(spacing: 20) {
                    // Send (stop + send)
                    Button(action: stopAndSend) {
                        Image(systemName: "arrow.up.circle.fill")
                            .font(.system(size: 36))
                            .foregroundColor(.blue)
                    }
                    .buttonStyle(.plain)

                    // Cancel
                    Button(action: cancelRecording) {
                        Image(systemName: "xmark.circle")
                            .font(.system(size: 30))
                            .foregroundColor(.secondary)
                    }
                    .buttonStyle(.plain)
                }

                Spacer()
            } else if isProcessing {
                // Processing
                Spacer()
                ProgressView()
                    .scaleEffect(1.5)
                Text(statusText)
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                Spacer()
            } else {
                // Idle — tap to record
                Spacer()

                Text("Klepni a mluv")
                    .font(.caption)
                    .foregroundColor(.secondary)

                Button(action: startRecording) {
                    Image(systemName: "mic.circle.fill")
                        .font(.system(size: 56))
                        .foregroundColor(.blue)
                }
                .buttonStyle(.plain)

                Spacer()

                Button("Zpět", action: onDismiss)
                    .font(.caption)
            }
        }
        .navigationTitle("Chat")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            if autoStart && !isListening && !isProcessing {
                startRecording()
            }
        }
    }

    private func startRecording() {
        isListening = true
        chatManager.startListening()
    }

    private func cancelRecording() {
        chatManager.stopListening()
        isListening = false
    }

    private func stopAndSend() {
        isListening = false
        isProcessing = true
        statusText = "Odesílám..."

        guard let audioData = chatManager.stopListening() else {
            isProcessing = false
            responseText = "Žádná nahrávka"
            return
        }

        Task {
            let info = ProcessInfo.processInfo
            info.performExpiringActivity(withReason: "Voice to Jervis") { expired in
                if expired { return }
            }

            WatchJervisApiClient.shared.onStatusUpdate = { status in
                DispatchQueue.main.async { statusText = status }
            }

            let result = await WatchJervisApiClient.shared.sendVoiceCommand(audioData)
            await MainActor.run {
                isProcessing = false
                responseText = result.text

                // TTS is played inline by SSE handler (first chunk)
                // Play remaining if SSE didn't play
                if let ttsData = result.ttsAudioData, ttsData.count > 0 {
                    WatchJervisApiClient.shared.playTtsAudio(ttsData)
                }
            }
        }
    }
}
