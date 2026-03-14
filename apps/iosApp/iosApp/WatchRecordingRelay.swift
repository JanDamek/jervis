import Foundation
import UIKit

/// Relays watch recording audio to Jervis server via REST.
/// iPhone acts as buffer/proxy — more storage, better connectivity than watch.
/// Uses background task to prevent iOS from suspending during upload.
class WatchRecordingRelay {
    static let shared = WatchRecordingRelay()

    private let baseURL = "https://jervis.damek-soft.eu"
    private let session: URLSession
    private var currentMeetingId: String?
    private var chunkBuffer: [(Data, Int, Bool)] = [] // (data, index, isLast)
    private var recordingStartTime: Date?
    private var backgroundTaskId: UIBackgroundTaskIdentifier = .invalid

    private init() {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 120
        session = URLSession(configuration: config)
    }

    /// Called when watch starts recording — create meeting on server
    func startRecording() {
        recordingStartTime = Date()
        chunkBuffer.removeAll()
        currentMeetingId = nil

        // Prevent iOS from suspending while recording/uploading
        if backgroundTaskId != .invalid {
            UIApplication.shared.endBackgroundTask(backgroundTaskId)
        }
        backgroundTaskId = UIApplication.shared.beginBackgroundTask(withName: "WatchRecordingUpload") { [weak self] in
            print("[WatchRelay] Background task expired")
            self?.backgroundTaskId = .invalid
        }
        print("[WatchRelay] Background task started: \(backgroundTaskId)")

        let body: [String: Any] = [
            "title": "Watch nahravka",
            "deviceSessionId": UUID().uuidString,
        ]

        postJSON(path: "/api/v1/meeting/start", body: body) { [weak self] json in
            if let meetingId = json?["meetingId"] as? String {
                print("[WatchRelay] Meeting created: \(meetingId)")
                self?.currentMeetingId = meetingId
                // Upload any buffered chunks
                self?.flushBuffer()
            } else {
                print("[WatchRelay] Failed to create meeting")
            }
        }
    }

    /// Called when watch sends audio chunk
    func addChunk(data: Data, chunkIndex: Int, isLast: Bool) {
        if let meetingId = currentMeetingId {
            uploadChunk(meetingId: meetingId, data: data, chunkIndex: chunkIndex, isLast: isLast)
        } else {
            // Buffer until meeting is created
            chunkBuffer.append((data, chunkIndex, isLast))
        }
    }

    /// Called when watch stops recording — finalize meeting
    func stopRecording() {
        guard let meetingId = currentMeetingId else {
            print("[WatchRelay] No meeting to stop")
            return
        }

        let duration = Int(Date().timeIntervalSince(recordingStartTime ?? Date()))

        let body: [String: Any] = [
            "meetingId": meetingId,
            "durationSeconds": duration,
        ]

        postJSON(path: "/api/v1/meeting/stop", body: body) { [weak self] json in
            print("[WatchRelay] Meeting finalized: \(json?["status"] ?? "?")")
            // End background task after finalization
            if let taskId = self?.backgroundTaskId, taskId != .invalid {
                UIApplication.shared.endBackgroundTask(taskId)
                self?.backgroundTaskId = .invalid
                print("[WatchRelay] Background task ended")
            }
        }

        currentMeetingId = nil
        recordingStartTime = nil
    }

    // MARK: - Private

    private func flushBuffer() {
        guard let meetingId = currentMeetingId else { return }
        let chunks = chunkBuffer
        chunkBuffer.removeAll()
        for (data, index, isLast) in chunks {
            uploadChunk(meetingId: meetingId, data: data, chunkIndex: index, isLast: isLast)
        }
    }

    private func uploadChunk(meetingId: String, data: Data, chunkIndex: Int, isLast: Bool) {
        let base64 = data.base64EncodedString()

        let body: [String: Any] = [
            "meetingId": meetingId,
            "chunkIndex": chunkIndex,
            "audioBase64": base64,
            "isLast": isLast,
        ]

        postJSON(path: "/api/v1/meeting/chunk", body: body) { json in
            print("[WatchRelay] Chunk \(chunkIndex) uploaded (isLast=\(isLast)): \(json?["status"] ?? "?")")
        }
    }

    private func postJSON(path: String, body: [String: Any], completion: @escaping ([String: Any]?) -> Void) {
        guard let url = URL(string: "\(baseURL)\(path)") else {
            completion(nil)
            return
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        do {
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
        } catch {
            print("[WatchRelay] JSON error: \(error)")
            completion(nil)
            return
        }

        session.dataTask(with: request) { data, _, error in
            if let error = error {
                print("[WatchRelay] Request error: \(error.localizedDescription)")
                completion(nil)
                return
            }
            guard let data = data,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                completion(nil)
                return
            }
            completion(json)
        }.resume()
    }
}
