import Foundation
import AVFoundation

/// API client for watchOS — voice chat via SSE streaming.
class WatchJervisApiClient: NSObject, AVAudioPlayerDelegate {
    static let shared = WatchJervisApiClient()

    private let baseURL = "https://jervis.damek-soft.eu"
    private let session: URLSession
    private var audioPlayer: AVAudioPlayer?

    /// Callback for SSE status updates
    var onStatusUpdate: ((String) -> Void)?

    private override init() {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 120
        config.timeoutIntervalForResource = 180
        config.waitsForConnectivity = false
        session = URLSession(configuration: config)
        super.init()
    }

    /// Send a text chat query (Siri intents).
    func sendChatQuery(_ query: String) async -> String {
        guard let url = URL(string: "\(baseURL)/api/v1/chat/siri") else { return "Chyba URL" }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONSerialization.data(withJSONObject: ["query": query, "source": "siri_watch"])
        do {
            let (data, _) = try await session.data(for: request)
            if let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let text = json["response"] as? String { return text }
            return String(data: data, encoding: .utf8) ?? "Zpracováno"
        } catch { return "Chyba: \(error.localizedDescription)" }
    }

    struct VoiceChatResponse {
        let text: String
        let ttsAudioData: Data?
        let transcription: String?
    }

    /// Send audio via SSE streaming endpoint — real-time status updates.
    func sendVoiceCommand(_ audioData: Data) async -> VoiceChatResponse {
        print("[WatchAPI] SSE voice, audio: \(audioData.count) bytes")

        guard let url = URL(string: "\(baseURL)/api/v1/voice/stream") else {
            return VoiceChatResponse(text: "Chyba URL", ttsAudioData: nil, transcription: nil)
        }

        let boundary = UUID().uuidString
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.timeoutInterval = 120

        var body = Data()
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"file\"; filename=\"voice.wav\"\r\n".data(using: .utf8)!)
        body.append("Content-Type: audio/wav\r\n\r\n".data(using: .utf8)!)
        body.append(audioData)
        body.append("\r\n--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"source\"\r\n\r\n".data(using: .utf8)!)
        body.append("watch_chat\r\n".data(using: .utf8)!)
        body.append("--\(boundary)--\r\n".data(using: .utf8)!)
        request.httpBody = body

        var transcription: String? = nil
        var responseText = ""
        var ttsDataParts: [Data] = []

        do {
            DispatchQueue.main.async { self.onStatusUpdate?("Odesílám...") }

            // Try SSE streaming first
            let (bytes, response) = try await session.bytes(for: request)

            guard let httpResponse = response as? HTTPURLResponse,
                  httpResponse.statusCode == 200 else {
                let code = (response as? HTTPURLResponse)?.statusCode ?? 0
                return VoiceChatResponse(text: "Server \(code)", ttsAudioData: nil, transcription: nil)
            }

            var currentEvent = ""
            var currentData = ""

            for try await line in bytes.lines {
                if line.hasPrefix("event: ") {
                    currentEvent = String(line.dropFirst(7))
                } else if line.hasPrefix("data: ") {
                    currentData = String(line.dropFirst(6))
                } else if line.isEmpty && !currentData.isEmpty {
                    // Process SSE event
                    if let jsonData = currentData.data(using: .utf8),
                       let json = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any] {

                        switch currentEvent {
                        case "transcribing":
                            let t = json["text"] as? String ?? "Přepisuji..."
                            DispatchQueue.main.async { self.onStatusUpdate?(t) }

                        case "transcribed":
                            transcription = json["text"] as? String
                            let preview = String((transcription ?? "").prefix(40))
                            DispatchQueue.main.async { self.onStatusUpdate?("🎤 \(preview)") }

                        case "responding":
                            DispatchQueue.main.async { self.onStatusUpdate?("Generuji odpověď...") }

                        case "token":
                            responseText += json["text"] as? String ?? ""

                        case "response":
                            let t = json["text"] as? String ?? ""
                            if responseText.isEmpty { responseText = t }

                        case "tts_audio":
                            if let b64 = json["data"] as? String, !b64.isEmpty,
                               let data = Data(base64Encoded: b64) {
                                ttsDataParts.append(data)
                                // Play first chunk immediately
                                if ttsDataParts.count == 1 {
                                    playTtsAudio(data)
                                }
                            }

                        case "error":
                            let errText = json["text"] as? String ?? "Chyba"
                            if responseText.isEmpty { responseText = errText }

                        case "done":
                            break

                        default: break
                        }
                    }
                    currentEvent = ""
                    currentData = ""
                }
            }

            print("[WatchAPI] SSE done: text=\(responseText.prefix(60)), tts=\(ttsDataParts.count) chunks")

            // Combine all TTS chunks
            let combinedTts = ttsDataParts.isEmpty ? nil : ttsDataParts.reduce(Data()) { $0 + $1 }

            return VoiceChatResponse(
                text: responseText.isEmpty ? "Zpracováno" : responseText,
                ttsAudioData: combinedTts,
                transcription: transcription
            )
        } catch {
            print("[WatchAPI] SSE error: \(error)")
            // Return whatever we collected
            if !responseText.isEmpty {
                return VoiceChatResponse(text: responseText, ttsAudioData: nil, transcription: transcription)
            }
            return VoiceChatResponse(text: "Chyba: \(error.localizedDescription)", ttsAudioData: nil, transcription: nil)
        }
    }

    /// Play TTS audio on watch speaker.
    func playTtsAudio(_ wavData: Data) {
        do {
            let audioSession = AVAudioSession.sharedInstance()
            try audioSession.setCategory(.playback, mode: .spokenAudio, options: [.duckOthers])
            try audioSession.setActive(true)
            audioPlayer = try AVAudioPlayer(data: wavData)
            audioPlayer?.delegate = self
            audioPlayer?.volume = 1.0
            audioPlayer?.play()
        } catch {
            print("[WatchAPI] TTS error: \(error)")
        }
    }

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        try? AVAudioSession.sharedInstance().setActive(false)
        audioPlayer = nil
    }
}
