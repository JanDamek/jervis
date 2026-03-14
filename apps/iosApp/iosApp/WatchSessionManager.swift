import Foundation
import WatchConnectivity

class WatchSessionManager: NSObject, WCSessionDelegate {
    static let shared = WatchSessionManager()

    /// Callback when watch starts recording — iOS app creates recording session
    var onWatchRecordingStarted: (() -> Void)?
    /// Callback when watch sends audio chunk
    var onAudioChunkReceived: ((Data, Int, Bool) -> Void)?
    /// Callback when watch stops recording
    var onWatchRecordingStopped: (() -> Void)?
    /// Callback when watch sends voice command for chat
    var onVoiceCommandReceived: ((Data, @escaping (String) -> Void) -> Void)?

    private override init() {
        super.init()
    }

    func activate() {
        guard WCSession.isSupported() else { return }
        WCSession.default.delegate = self
        WCSession.default.activate()
    }

    // MARK: - WCSessionDelegate

    func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
        print("[WatchSession] Activation: \(activationState.rawValue), error: \(error?.localizedDescription ?? "none")")
    }

    func sessionDidBecomeInactive(_ session: WCSession) {}
    func sessionDidDeactivate(_ session: WCSession) {
        session.activate()
    }

    func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
        handleCommand(message)
    }

    // Receives commands sent via transferUserInfo (reliable, works in background)
    func session(_ session: WCSession, didReceiveUserInfo userInfo: [String: Any] = [:]) {
        handleCommand(userInfo)
    }

    private func handleCommand(_ info: [String: Any]) {
        guard let type = info["type"] as? String, type == "command",
              let command = info["command"] as? String else { return }

        print("[WatchSession] Received command: \(command)")
        DispatchQueue.main.async {
            switch command {
            case "start_recording":
                self.onWatchRecordingStarted?()
            case "stop_recording":
                self.onWatchRecordingStopped?()
            default:
                break
            }
        }
    }

    func session(_ session: WCSession, didReceive file: WCSessionFile) {
        guard let metadata = file.metadata,
              let type = metadata["type"] as? String else { return }

        switch type {
        case "audio_chunk":
            guard let chunkIndex = metadata["chunkIndex"] as? Int,
                  let isLast = metadata["isLast"] as? Bool else { return }
            do {
                let data = try Data(contentsOf: file.fileURL)
                DispatchQueue.main.async {
                    self.onAudioChunkReceived?(data, chunkIndex, isLast)
                }
            } catch {
                print("[WatchSession] Failed to read audio chunk: \(error)")
            }

        case "voice_command":
            do {
                let data = try Data(contentsOf: file.fileURL)
                DispatchQueue.main.async {
                    self.onVoiceCommandReceived?(data) { response in
                        // Send response back to watch via message
                        self.sendChatResponse(response)
                    }
                }
            } catch {
                print("[WatchSession] Failed to read voice command: \(error)")
            }

        default:
            break
        }
    }

    func session(_ session: WCSession, didReceiveMessageData messageData: Data, replyHandler: @escaping (Data) -> Void) {
        // Legacy path — voice command via sendMessageData (small payloads only)
        DispatchQueue.main.async {
            self.onVoiceCommandReceived?(messageData) { response in
                if let data = response.data(using: .utf8) {
                    replyHandler(data)
                }
            }
        }
    }

    /// Send chat response back to watch
    func sendChatResponse(_ text: String) {
        guard WCSession.default.isReachable else { return }
        WCSession.default.sendMessage(
            ["type": "chat_response", "text": text],
            replyHandler: nil,
            errorHandler: { error in
                print("[WatchSession] Failed to send response: \(error)")
            }
        )
    }
}
