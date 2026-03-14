import Foundation
import WatchConnectivity

enum WatchCommand: String {
    case startRecording = "start_recording"
    case stopRecording = "stop_recording"
}

class WatchConnectivityManager: NSObject, ObservableObject, WCSessionDelegate {
    @Published var isPhoneReachable = false
    @Published var lastChatResponse: String? = nil

    private var session: WCSession?

    override init() {
        super.init()
        if WCSession.isSupported() {
            session = WCSession.default
            session?.delegate = self
            session?.activate()
        }
    }

    func sendCommand(_ command: WatchCommand) {
        guard let session = session else {
            print("[Watch] No WCSession for command: \(command.rawValue)")
            return
        }
        // Use transferUserInfo — works even when phone is not immediately reachable.
        // sendMessage requires isReachable and foreground app, transferUserInfo queues reliably.
        session.transferUserInfo(["type": "command", "command": command.rawValue])
        print("[Watch] Command queued: \(command.rawValue)")
    }

    func sendAudioChunk(_ data: Data, chunkIndex: Int, isLast: Bool = false) {
        guard let session = session else { return }

        let metadata: [String: Any] = [
            "type": "audio_chunk",
            "chunkIndex": chunkIndex,
            "isLast": isLast,
            "size": data.count,
        ]

        // Use transferFile for reliable delivery (works even if phone is not immediately reachable)
        let tempURL = FileManager.default.temporaryDirectory.appendingPathComponent("chunk_\(chunkIndex).wav")
        try? data.write(to: tempURL)
        session.transferFile(tempURL, metadata: metadata)
    }

    func sendVoiceCommand(_ audioData: Data) {
        guard let session = session else {
            print("[Watch] No WCSession for voice command")
            return
        }

        // Use transferFile — sendMessageData has ~65KB limit, audio is larger
        let tempURL = FileManager.default.temporaryDirectory.appendingPathComponent("voice_cmd_\(Int(Date().timeIntervalSince1970)).wav")
        do {
            try audioData.write(to: tempURL)
        } catch {
            print("[Watch] Failed to write voice command audio: \(error)")
            DispatchQueue.main.async {
                self.lastChatResponse = "Chyba: nelze ulozit audio"
            }
            return
        }

        let metadata: [String: Any] = [
            "type": "voice_command",
            "size": audioData.count,
        ]
        session.transferFile(tempURL, metadata: metadata)
        print("[Watch] Voice command sent via transferFile (\(audioData.count) bytes)")
    }

    // MARK: - WCSessionDelegate

    func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
        DispatchQueue.main.async {
            self.isPhoneReachable = session.isReachable
        }
    }

    func sessionReachabilityDidChange(_ session: WCSession) {
        DispatchQueue.main.async {
            self.isPhoneReachable = session.isReachable
        }
    }

    func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
        if let type = message["type"] as? String, type == "chat_response",
           let text = message["text"] as? String {
            DispatchQueue.main.async {
                self.lastChatResponse = text
            }
        }
    }
}
