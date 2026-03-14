import Foundation
import AVFoundation

/// API client for watchOS — handles text queries (Siri) and audio voice commands (Chat).
/// Sends directly to backend, no phone relay needed.
class WatchJervisApiClient: NSObject, AVAudioPlayerDelegate {
    static let shared = WatchJervisApiClient()

    private let baseURL = "https://jervis.damek-soft.eu"
    private let session: URLSession
    private var audioPlayer: AVAudioPlayer?

    private override init() {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 90
        config.timeoutIntervalForResource = 120
        config.waitsForConnectivity = false
        session = URLSession(configuration: config)
        super.init()
    }

    /// Voice chat response from server
    struct VoiceChatResponse {
        let text: String
        let ttsAudioData: Data? // WAV audio
        let transcription: String?
    }

    /// Send a text chat query to Jervis and return the response text.
    func sendChatQuery(_ query: String) async -> String {
        guard let url = URL(string: "\(baseURL)/api/v1/chat/siri") else {
            return "Chyba: neplatna URL"
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        let body: [String: Any] = [
            "query": query,
            "source": "siri_watch",
        ]

        do {
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
            let (data, response) = try await session.data(for: request)

            guard let httpResponse = response as? HTTPURLResponse,
                  httpResponse.statusCode == 200 else {
                return "Chyba: server neodpovida"
            }

            if let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let text = json["response"] as? String {
                return text
            }

            if let text = String(data: data, encoding: .utf8), !text.isEmpty {
                return text
            }

            return "Jervis zpracoval pozadavek."
        } catch {
            return "Chyba: \(error.localizedDescription)"
        }
    }

    /// Send audio data to backend — returns text + optional TTS audio.
    func sendVoiceCommand(_ audioData: Data) async -> VoiceChatResponse {
        print("[WatchAPI] Sending voice command, audio size: \(audioData.count) bytes")

        guard let url = URL(string: "\(baseURL)/api/v1/chat/voice") else {
            return VoiceChatResponse(text: "Chyba: neplatna URL", ttsAudioData: nil, transcription: nil)
        }

        let boundary = UUID().uuidString
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.timeoutInterval = 30

        // Build multipart body
        var body = Data()
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"file\"; filename=\"voice.wav\"\r\n".data(using: .utf8)!)
        body.append("Content-Type: audio/wav\r\n\r\n".data(using: .utf8)!)
        body.append(audioData)
        body.append("\r\n".data(using: .utf8)!)
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"source\"\r\n\r\n".data(using: .utf8)!)
        body.append("watch_chat\r\n".data(using: .utf8)!)
        body.append("--\(boundary)--\r\n".data(using: .utf8)!)
        request.httpBody = body

        do {
            let (data, response) = try await session.data(for: request)

            guard let httpResponse = response as? HTTPURLResponse,
                  httpResponse.statusCode == 200 else {
                let code = (response as? HTTPURLResponse)?.statusCode ?? 0
                print("[WatchAPI] Voice endpoint returned \(code)")
                return VoiceChatResponse(text: "Server neodpovida (\(code))", ttsAudioData: nil, transcription: nil)
            }

            if let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                let text = json["response"] as? String ?? "Zpracovavam"
                let transcription = json["transcription"] as? String

                // Decode TTS audio from Base64
                var ttsData: Data? = nil
                if let ttsBase64 = json["ttsAudio"] as? String, !ttsBase64.isEmpty {
                    ttsData = Data(base64Encoded: ttsBase64)
                }

                print("[WatchAPI] Voice result: \(text.prefix(80)), tts=\(ttsData?.count ?? 0)b")
                return VoiceChatResponse(text: text, ttsAudioData: ttsData, transcription: transcription)
            }

            let text = String(data: data, encoding: .utf8) ?? "Neznama odpoved"
            return VoiceChatResponse(text: text, ttsAudioData: nil, transcription: nil)
        } catch {
            print("[WatchAPI] Voice error: \(error)")
            return VoiceChatResponse(text: "Chyba: \(error.localizedDescription)", ttsAudioData: nil, transcription: nil)
        }
    }

    /// Play TTS audio on watch speaker.
    func playTtsAudio(_ wavData: Data) {
        do {
            let audioSession = AVAudioSession.sharedInstance()
            try audioSession.setCategory(.playback, mode: .default)
            try audioSession.setActive(true)

            audioPlayer = try AVAudioPlayer(data: wavData)
            audioPlayer?.delegate = self
            audioPlayer?.play()
        } catch {
            print("[WatchAPI] TTS playback error: \(error)")
        }
    }

    // AVAudioPlayerDelegate — cleanup after playback
    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        try? AVAudioSession.sharedInstance().setActive(false)
        audioPlayer = nil
    }
}
