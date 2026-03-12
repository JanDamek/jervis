import SwiftUI

struct RecordingView: View {
    @ObservedObject var recorder: WatchAudioRecorder
    @ObservedObject var connectivity: WatchConnectivityManager
    var onDismiss: () -> Void

    @State private var isRecording = false
    @State private var elapsedSeconds: Int = 0
    @State private var timer: Timer? = nil
    @State private var status: String = "Pripraveno"

    var body: some View {
        VStack(spacing: 12) {
            Text(status)
                .font(.caption)
                .foregroundColor(.secondary)

            Text(formatDuration(elapsedSeconds))
                .font(.system(size: 32, weight: .bold, design: .monospaced))
                .foregroundColor(isRecording ? .red : .primary)

            // Waveform indicator
            if isRecording {
                HStack(spacing: 2) {
                    ForEach(0..<7, id: \.self) { _ in
                        RoundedRectangle(cornerRadius: 2)
                            .fill(Color.red)
                            .frame(width: 4, height: CGFloat.random(in: 8...28))
                    }
                }
                .frame(height: 30)
                .animation(.easeInOut(duration: 0.3).repeatForever(), value: isRecording)
            }

            if isRecording {
                Button(action: stopRecording) {
                    Image(systemName: "stop.circle.fill")
                        .font(.system(size: 44))
                        .foregroundColor(.red)
                }
                .buttonStyle(.plain)
            } else {
                HStack(spacing: 16) {
                    Button(action: startRecording) {
                        Image(systemName: "record.circle")
                            .font(.system(size: 44))
                            .foregroundColor(.red)
                    }
                    .buttonStyle(.plain)

                    Button(action: onDismiss) {
                        Image(systemName: "xmark.circle")
                            .font(.system(size: 30))
                            .foregroundColor(.secondary)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .navigationTitle("Nahravani")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func startRecording() {
        recorder.startRecording()
        isRecording = true
        status = "Nahravam..."
        elapsedSeconds = 0
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { _ in
            elapsedSeconds += 1
            // Send chunk every 10 seconds
            if elapsedSeconds % 10 == 0 {
                if let chunk = recorder.getAndClearBuffer() {
                    connectivity.sendAudioChunk(chunk, chunkIndex: elapsedSeconds / 10)
                }
            }
        }

        // Notify phone that recording started
        connectivity.sendCommand(.startRecording)
    }

    private func stopRecording() {
        timer?.invalidate()
        timer = nil
        isRecording = false
        status = "Odesilam..."

        // Get final audio chunk
        if let finalChunk = recorder.stopRecording() {
            connectivity.sendAudioChunk(finalChunk, chunkIndex: -1, isLast: true)
        }

        connectivity.sendCommand(.stopRecording)

        DispatchQueue.main.asyncAfter(deadline: .now() + 1) {
            status = "Hotovo"
            DispatchQueue.main.asyncAfter(deadline: .now() + 1) {
                onDismiss()
            }
        }
    }

    private func formatDuration(_ seconds: Int) -> String {
        let m = seconds / 60
        let s = seconds % 60
        return String(format: "%02d:%02d", m, s)
    }
}
