import Foundation

/// Lightweight API client for Siri intents — sends text queries to Jervis backend chat API.
/// Shared between iOS and watchOS targets.
class JervisApiClient {
    static let shared = JervisApiClient()

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
            "source": "siri",
        ]

        do {
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
            let (data, response) = try await session.data(for: request)

            guard let httpResponse = response as? HTTPURLResponse else {
                return "Chyba: neplatna odpoved serveru"
            }

            guard httpResponse.statusCode == 200 else {
                return "Chyba: server vratil \(httpResponse.statusCode)"
            }

            if let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let text = json["response"] as? String {
                return text
            }

            // Fallback: try plain text
            if let text = String(data: data, encoding: .utf8), !text.isEmpty {
                return text
            }

            return "Jervis zpracoval pozadavek, ale neodpovedel."
        } catch {
            return "Chyba: \(error.localizedDescription)"
        }
    }
}
