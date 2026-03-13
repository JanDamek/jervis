import Foundation

/// API client for watchOS — handles both text queries (Siri) and audio voice commands (Chat).
/// Sends directly to backend, no phone relay needed.
class WatchJervisApiClient {
    static let shared = WatchJervisApiClient()

    private let baseURL = "https://jervis.damek-soft.eu"
    private let session: URLSession

    private init() {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 60
        session = URLSession(configuration: config)
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

    /// Send audio data to backend — STT + chat processing happens server-side.
    func sendVoiceCommand(_ audioData: Data) async -> String {
        return await sendAudioForChat(audioData)
    }

    /// Send audio to backend /api/v1/chat/voice — backend does STT + chat in one call.
    private func sendAudioForChat(_ audioData: Data) async -> String {
        guard let url = URL(string: "\(baseURL)/api/v1/chat/voice") else {
            return "Chyba: neplatna URL"
        }

        let boundary = UUID().uuidString
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.timeoutInterval = 60 // STT + chat processing can take time

        // Build multipart body
        var body = Data()
        // Audio file part
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"file\"; filename=\"voice.wav\"\r\n".data(using: .utf8)!)
        body.append("Content-Type: audio/wav\r\n\r\n".data(using: .utf8)!)
        body.append(audioData)
        body.append("\r\n".data(using: .utf8)!)
        // Source field
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
                return "Server neodpovida (\(code))"
            }

            if let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let text = json["response"] as? String {
                return text
            }

            return String(data: data, encoding: .utf8) ?? "Neznama odpoved"
        } catch {
            print("[WatchAPI] Voice error: \(error)")
            return "Chyba: \(error.localizedDescription)"
        }
    }
}
