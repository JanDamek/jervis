import AVFoundation
import Foundation

/// Continuous WebSocket voice session for watchOS.
///
/// Manages mic recording + WebSocket transport + TTS playback.
/// Protocol: binary PCM chunks (100ms) + JSON control messages.
/// Anti-echo: sends tts_playing/tts_finished when TTS is active.
class WatchVoiceSession: NSObject, ObservableObject, AVAudioPlayerDelegate {
    static let shared = WatchVoiceSession()

    private let baseURL = "wss://jervis.damek-soft.eu"

    // Audio recording
    private var recorder: AVAudioRecorder?
    private var recordingURL: URL?
    private var lastReadPosition: Int = 0
    private var chunkTimer: Timer?

    // WebSocket
    private var wsTask: URLSessionWebSocketTask?
    private var urlSession: URLSession?

    // TTS playback
    private var audioPlayer: AVAudioPlayer?
    private var isTtsPlaying = false

    // Observable state
    @Published var state: SessionState = .idle
    @Published var transcript = ""
    @Published var responseText = ""
    @Published var statusText = ""

    enum SessionState {
        case idle, connecting, listening, recording, processing, playingTts, error
    }

    private override init() {
        super.init()
    }

    // MARK: - Public API

    func start() {
        guard state == .idle else { return }
        state = .connecting
        statusText = "Připojuji..."
        transcript = ""
        responseText = ""

        // Setup audio session for playAndRecord (full-duplex)
        let audioSession = AVAudioSession.sharedInstance()
        do {
            try audioSession.setCategory(.playAndRecord, mode: .voiceChat, options: [.defaultToSpeaker])
            try audioSession.setActive(true)
        } catch {
            print("[WatchVoice] Audio session error: \(error)")
            state = .error
            statusText = "Mikrofon nepřístupný"
            return
        }

        // Connect WebSocket
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 300 // 5 min
        urlSession = URLSession(configuration: config)
        guard let url = URL(string: "\(baseURL)/api/v1/voice/ws") else {
            state = .error
            statusText = "Chyba URL"
            return
        }
        wsTask = urlSession?.webSocketTask(with: url)
        wsTask?.resume()

        // Send start message
        let startMsg = """
        {"type":"start","source":"watch","client_id":"","project_id":"","tts":true}
        """
        wsTask?.send(.string(startMsg.trimmingCharacters(in: .whitespacesAndNewlines))) { [weak self] error in
            if let error = error {
                print("[WatchVoice] Start send error: \(error)")
                DispatchQueue.main.async { self?.state = .error }
                return
            }
        }

        // Start receiving messages
        receiveLoop()

        // Start mic recording
        startMicRecording()

        // Start 100ms chunk sending timer
        chunkTimer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            self?.sendAudioChunk()
        }

        state = .listening
        statusText = "Poslouchám..."
    }

    func stop() {
        // Send stop message
        wsTask?.send(.string("""{"type":"stop"}""")) { _ in }

        cleanup()
    }

    func cancel() {
        cleanup()
    }

    // MARK: - Mic Recording

    private func startMicRecording() {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("watch_ws_recording.wav")
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
        } catch {
            print("[WatchVoice] Recorder error: \(error)")
        }
    }

    private func sendAudioChunk() {
        guard !isTtsPlaying else { return } // Anti-echo
        guard let url = recordingURL, recorder?.isRecording == true else { return }
        guard let data = try? Data(contentsOf: url) else { return }

        let headerSize = 44
        let startPos = max(headerSize, lastReadPosition)
        guard data.count > startPos else { return }

        let chunk = data.subdata(in: startPos..<data.count)
        lastReadPosition = data.count

        if chunk.count > 0 {
            wsTask?.send(.data(chunk)) { error in
                if let error = error {
                    print("[WatchVoice] Send chunk error: \(error)")
                }
            }
        }
    }

    // MARK: - WebSocket Receive

    private func receiveLoop() {
        wsTask?.receive { [weak self] result in
            guard let self = self else { return }
            switch result {
            case .success(let message):
                switch message {
                case .string(let text):
                    self.handleTextMessage(text)
                case .data(let data):
                    self.handleBinaryMessage(data)
                @unknown default:
                    break
                }
                // Continue receiving
                self.receiveLoop()

            case .failure(let error):
                print("[WatchVoice] Receive error: \(error)")
                DispatchQueue.main.async {
                    if self.state != .idle {
                        self.cleanup()
                    }
                }
            }
        }
    }

    private func handleTextMessage(_ text: String) {
        guard let data = text.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = json["type"] as? String else { return }

        let content = json["text"] as? String

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            switch type {
            case "listening":
                self.state = .listening
                self.statusText = "Poslouchám..."
            case "speech_start":
                self.state = .recording
                self.statusText = "Mluvíte..."
                self.transcript = ""
                self.responseText = ""
            case "transcribing":
                if let t = content {
                    self.transcript = (self.transcript + " " + t).trimmingCharacters(in: .whitespaces)
                    self.statusText = "Přepisuji..."
                }
            case "transcribed":
                if let t = content { self.transcript = t }
                self.statusText = "Zpracovávám..."
                self.state = .processing
            case "thinking":
                // Proactive acknowledgment from Jervis
                if let t = content {
                    self.statusText = t
                    self.responseText = t
                }
                self.state = .processing
            case "responding":
                self.statusText = "Generuji odpověď..."
            case "token":
                if let t = content { self.responseText += t }
            case "response":
                if let t = content { self.responseText = t }
            case "stored":
                self.statusText = "Uloženo"
            case "done":
                self.statusText = ""
                self.state = .listening
            case "error":
                self.statusText = content ?? "Chyba"
            default:
                break
            }
        }
    }

    private func handleBinaryMessage(_ data: Data) {
        // TTS audio — play and notify server (anti-echo)
        guard data.count > 0 else { return }
        isTtsPlaying = true
        DispatchQueue.main.async { self.state = .playingTts }

        wsTask?.send(.string("""{"type":"tts_playing"}""")) { _ in }

        do {
            audioPlayer = try AVAudioPlayer(data: data)
            audioPlayer?.delegate = self
            audioPlayer?.volume = 1.0
            audioPlayer?.play()
        } catch {
            print("[WatchVoice] TTS playback error: \(error)")
            ttsFinished()
        }
    }

    // MARK: - AVAudioPlayerDelegate

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        ttsFinished()
    }

    private func ttsFinished() {
        isTtsPlaying = false
        wsTask?.send(.string("""{"type":"tts_finished"}""")) { _ in }
        DispatchQueue.main.async {
            if self.state == .playingTts {
                self.state = .listening
            }
        }
    }

    // MARK: - Cleanup

    private func cleanup() {
        chunkTimer?.invalidate()
        chunkTimer = nil
        recorder?.stop()
        recorder = nil
        if let url = recordingURL {
            try? FileManager.default.removeItem(at: url)
        }
        recordingURL = nil
        lastReadPosition = 0
        audioPlayer?.stop()
        audioPlayer = nil
        isTtsPlaying = false
        wsTask?.cancel(with: .normalClosure, reason: nil)
        wsTask = nil
        urlSession?.invalidateAndCancel()
        urlSession = nil
        try? AVAudioSession.sharedInstance().setActive(false)

        DispatchQueue.main.async {
            self.state = .idle
            self.statusText = ""
        }
    }
}
