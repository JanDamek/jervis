import SwiftUI

struct ChatView: View {
    @ObservedObject var connectivity: WatchConnectivityManager
    @ObservedObject private var voiceSession = WatchVoiceSession.shared
    var autoStart: Bool = false
    var onDismiss: () -> Void

    var body: some View {
        VStack(spacing: 8) {
            switch voiceSession.state {
            case .idle:
                // Idle — tap to start continuous session
                Spacer()

                Text("Klepni a mluv")
                    .font(.caption)
                    .foregroundColor(.secondary)

                Button(action: { voiceSession.start() }) {
                    Image(systemName: "mic.circle.fill")
                        .font(.system(size: 56))
                        .foregroundColor(.blue)
                }
                .buttonStyle(.plain)

                Spacer()

                Button("Zpět", action: onDismiss)
                    .font(.caption)

            case .connecting:
                Spacer()
                ProgressView()
                Text("Připojuji...")
                    .font(.caption)
                    .foregroundColor(.secondary)
                Spacer()

            case .listening:
                // Listening — VAD waiting for speech
                Spacer()

                Image(systemName: "waveform.circle.fill")
                    .font(.system(size: 44))
                    .foregroundColor(.green)
                    .opacity(0.6)

                Text("Poslouchám...")
                    .font(.caption)
                    .foregroundColor(.secondary)

                // Show last response if available
                if !voiceSession.responseText.isEmpty {
                    ScrollView {
                        Text(voiceSession.responseText)
                            .font(.caption2)
                            .foregroundColor(.primary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .frame(maxHeight: 60)
                }

                Button(action: { voiceSession.stop() }) {
                    Image(systemName: "stop.circle.fill")
                        .font(.system(size: 30))
                        .foregroundColor(.red)
                }
                .buttonStyle(.plain)

                Spacer()

            case .recording:
                // Speech detected
                Spacer()

                Image(systemName: "waveform.circle.fill")
                    .font(.system(size: 44))
                    .foregroundColor(.green)

                Text(voiceSession.transcript.isEmpty ? "Mluvíte..." : String(voiceSession.transcript.suffix(40)))
                    .font(.caption)
                    .foregroundColor(.primary)
                    .lineLimit(2)

                Button(action: { voiceSession.stop() }) {
                    Image(systemName: "stop.circle.fill")
                        .font(.system(size: 30))
                        .foregroundColor(.red)
                }
                .buttonStyle(.plain)

                Spacer()

            case .processing:
                Spacer()
                ProgressView()
                    .scaleEffect(1.5)
                Text(voiceSession.statusText.isEmpty ? "Zpracovávám..." : voiceSession.statusText)
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)

                if !voiceSession.transcript.isEmpty {
                    Text(voiceSession.transcript)
                        .font(.caption2)
                        .foregroundColor(.secondary)
                        .lineLimit(2)
                }
                Spacer()

            case .playingTts:
                // TTS playing — show response
                Spacer()

                Image(systemName: "speaker.wave.2.circle.fill")
                    .font(.system(size: 44))
                    .foregroundColor(.blue)

                ScrollView {
                    Text(voiceSession.responseText.isEmpty ? "Odpovídám..." : voiceSession.responseText)
                        .font(.body)
                        .foregroundColor(.primary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }

                Spacer()

            case .error:
                Spacer()
                Image(systemName: "exclamationmark.triangle")
                    .font(.system(size: 30))
                    .foregroundColor(.orange)
                Text(voiceSession.statusText)
                    .font(.caption)
                    .foregroundColor(.secondary)

                Button("Zkusit znovu") { voiceSession.start() }
                    .font(.caption)

                Spacer()

                Button("Zpět", action: onDismiss)
                    .font(.caption)
            }
        }
        .navigationTitle("Chat")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            if autoStart && voiceSession.state == .idle {
                voiceSession.start()
            }
        }
        .onDisappear {
            if voiceSession.state != .idle {
                voiceSession.stop()
            }
        }
    }
}
