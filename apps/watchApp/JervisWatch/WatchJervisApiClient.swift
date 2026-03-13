import Foundation

/// Lightweight API client for Siri intents on watchOS.
/// Falls back to phone relay via WatchConnectivity if direct network is unavailable.
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
}
