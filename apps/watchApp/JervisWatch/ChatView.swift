import SwiftUI

struct ChatView: View {
    @ObservedObject var chatManager: WatchChatManager
    @ObservedObject var connectivity: WatchConnectivityManager
    var onDismiss: () -> Void

    @State private var isListening = false
    @State private var responseText: String? = nil
    @State private var isProcessing = false

    var body: some View {
        VStack(spacing: 12) {
            if let response = responseText {
                ScrollView {
                    Text(response)
                        .font(.body)
                        .padding(.horizontal, 4)
                }
                .frame(maxHeight: .infinity)

                HStack(spacing: 16) {
                    Button(action: { responseText = nil }) {
                        Image(systemName: "mic.circle.fill")
                            .font(.system(size: 30))
                    }
                    .buttonStyle(.plain)
                    .tint(.blue)

                    Button(action: onDismiss) {
                        Image(systemName: "xmark.circle")
                            .font(.system(size: 30))
                            .foregroundColor(.secondary)
                    }
                    .buttonStyle(.plain)
                }
            } else if isProcessing {
                ProgressView()
                    .scaleEffect(1.5)
                Text("Zpracovavam...")
                    .font(.caption)
                    .foregroundColor(.secondary)
            } else {
                Spacer()

                Text(isListening ? "Posloucham..." : "Klepni a mluv")
                    .font(.caption)
                    .foregroundColor(.secondary)

                Button(action: toggleListening) {
                    Image(systemName: isListening ? "waveform.circle.fill" : "mic.circle.fill")
                        .font(.system(size: 56))
                        .foregroundColor(isListening ? .green : .blue)
                        .symbolEffect(.pulse, isActive: isListening)
                }
                .buttonStyle(.plain)

                Spacer()

                Button("Zpet", action: onDismiss)
                    .font(.caption)
            }
        }
        .navigationTitle("Chat")
        .navigationBarTitleDisplayMode(.inline)
        .onReceive(connectivity.$lastChatResponse) { response in
            if let response = response {
                isProcessing = false
                responseText = response
            }
        }
    }

    private func toggleListening() {
        if isListening {
            // Stop listening, send audio for processing
            isListening = false
            isProcessing = true

            if let audioData = chatManager.stopListening() {
                connectivity.sendVoiceCommand(audioData)
            }
        } else {
            isListening = true
            chatManager.startListening()
        }
    }
}
