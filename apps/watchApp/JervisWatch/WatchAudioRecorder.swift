import AVFoundation

class WatchAudioRecorder: ObservableObject {
    private var recorder: AVAudioRecorder?
    private var recordingURL: URL?
    private var lastReadPosition: Int = 0

    @Published var isRecording = false

    func startRecording() {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.record, mode: .default)
            try session.setActive(true)
        } catch {
            print("[WatchRecorder] Audio session error: \(error)")
            return
        }

        let url = FileManager.default.temporaryDirectory.appendingPathComponent("watch_recording.wav")
        recordingURL = url
        lastReadPosition = 0

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
            isRecording = true
        } catch {
            print("[WatchRecorder] Failed to start: \(error)")
        }
    }

    func stopRecording() -> Data? {
        recorder?.stop()
        isRecording = false
        try? AVAudioSession.sharedInstance().setActive(false)

        guard let url = recordingURL else { return nil }
        defer {
            try? FileManager.default.removeItem(at: url)
            recordingURL = nil
        }
        return try? Data(contentsOf: url)
    }

    func getAndClearBuffer() -> Data? {
        guard let url = recordingURL, isRecording else { return nil }
        guard let data = try? Data(contentsOf: url) else { return nil }

        let headerSize = 44 // WAV header
        let startPos = max(headerSize, lastReadPosition)
        guard data.count > startPos else { return nil }

        lastReadPosition = data.count
        return data.subdata(in: startPos..<data.count)
    }
}
