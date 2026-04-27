import AVFoundation

class WatchChatManager: ObservableObject {
    private var recorder: AVAudioRecorder?
    private var recordingURL: URL?

    @Published var isListening = false

    func startListening() {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.record, mode: .default)
            try session.setActive(true)
        } catch {
            print("[WatchChat] Audio session error: \(error)")
            return
        }

        let url = FileManager.default.temporaryDirectory.appendingPathComponent("watch_chat.wav")
        recordingURL = url

        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatLinearPCM),
            AVSampleRateKey: 16000.0,
            AVNumberOfChannelsKey: 1,
            AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsFloatKey: false,
        ]

        do {
            recorder = try AVAudioRecorder(url: url, settings: settings)
            recorder?.record()
            isListening = true
        } catch {
            print("[WatchChat] Failed to start: \(error)")
        }
    }

    func stopListening() -> Data? {
        recorder?.stop()
        isListening = false
        try? AVAudioSession.sharedInstance().setActive(false)

        guard let url = recordingURL else { return nil }
        defer {
            try? FileManager.default.removeItem(at: url)
            recordingURL = nil
        }
        return try? Data(contentsOf: url)
    }
}
