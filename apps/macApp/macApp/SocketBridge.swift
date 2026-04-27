import Foundation
import Darwin
import IOKit

protocol SocketBridgeDelegate: AnyObject {
    func socketBridgeDidRequestShowNotification(taskId: String?, title: String, body: String, category: String?, payload: [String: Any]?)
    func socketBridgeDidRequestCancelNotification(taskId: String)
    func socketBridgeDidRequestSetLoginItem(enabled: Bool)
    func socketBridgeDidRequestQueryLoginItem()
    func socketBridgeDidRequestFocusJervis()
}

final class SocketBridge {
    weak var delegate: SocketBridgeDelegate?

    private let path: String
    private var serverFd: Int32 = -1
    private var clientFd: Int32 = -1
    private var pending: [String] = []
    private let stateQueue = DispatchQueue(label: "jervis.macapp.socketbridge.state")
    private var acceptThread: Thread?
    private var readThread: Thread?

    init(path: String) {
        self.path = path
    }

    func start() {
        openSocket()
        let t = Thread { [weak self] in self?.acceptLoop() }
        t.name = "jervis.macapp.socketbridge.accept"
        acceptThread = t
        t.start()
    }

    func stop() {
        stateQueue.sync {
            if clientFd >= 0 { close(clientFd); clientFd = -1 }
        }
        if serverFd >= 0 { close(serverFd); serverFd = -1 }
        unlink(path)
    }

    // MARK: - Outgoing (Swift → JVM)

    func sendToken(hexToken: String, deviceId: String) {
        let json = "{\"kind\":\"token\",\"hexToken\":\"\(hexToken)\",\"deviceId\":\"\(deviceId)\"}\n"
        send(line: json)
    }

    func sendPayload(userInfo: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: userInfo),
              let userInfoJson = String(data: data, encoding: .utf8) else { return }
        let json = "{\"kind\":\"payload\",\"userInfo\":\(userInfoJson)}\n"
        send(line: json)
    }

    func sendAction(taskId: String?, action: String, replyText: String?) {
        var dict: [String: Any] = ["kind": "action", "action": action]
        if let taskId = taskId { dict["taskId"] = taskId }
        if let replyText = replyText { dict["replyText"] = replyText }
        guard let data = try? JSONSerialization.data(withJSONObject: dict),
              let json = String(data: data, encoding: .utf8) else { return }
        send(line: json + "\n")
    }

    func sendLoginItemStatus(enabled: Bool) {
        let json = "{\"kind\":\"loginItemStatus\",\"enabled\":\(enabled)}\n"
        send(line: json)
    }

    /// True when a JVM client is currently connected to the socket.
    func isClientConnected() -> Bool {
        return stateQueue.sync { clientFd >= 0 }
    }

    private func send(line: String) {
        stateQueue.async { [weak self] in
            guard let self = self else { return }
            if self.clientFd >= 0 {
                if !self.writeLine(line, fd: self.clientFd) {
                    close(self.clientFd)
                    self.clientFd = -1
                    self.pending.append(line)
                }
            } else {
                self.pending.append(line)
            }
        }
    }

    private func writeLine(_ line: String, fd: Int32) -> Bool {
        return line.withCString { ptr in
            let len = strlen(ptr)
            let written = Darwin.send(fd, ptr, len, 0)
            return written == Int(len)
        }
    }

    // MARK: - Incoming (JVM → Swift)

    private func startReadLoop(fd: Int32) {
        let t = Thread { [weak self] in self?.readLoop(fd: fd) }
        t.name = "jervis.macapp.socketbridge.read"
        readThread = t
        t.start()
    }

    private func readLoop(fd: Int32) {
        var buffer = Data()
        var chunk = [UInt8](repeating: 0, count: 4096)
        while true {
            let n = recv(fd, &chunk, chunk.count, 0)
            if n <= 0 { break }
            buffer.append(chunk, count: n)
            while let nl = buffer.firstIndex(of: 0x0A) {
                let lineData = buffer.subdata(in: 0..<nl)
                buffer.removeSubrange(0...nl)
                if let line = String(data: lineData, encoding: .utf8), !line.isEmpty {
                    handleLine(line)
                }
            }
        }
    }

    private func handleLine(_ line: String) {
        guard let data = line.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let kind = obj["kind"] as? String else { return }
        switch kind {
        case "showNotification":
            let taskId = obj["taskId"] as? String
            let title = (obj["title"] as? String) ?? ""
            let body = (obj["body"] as? String) ?? ""
            let category = obj["category"] as? String
            let payload = obj["payload"] as? [String: Any]
            DispatchQueue.main.async { [weak self] in
                self?.delegate?.socketBridgeDidRequestShowNotification(
                    taskId: taskId, title: title, body: body, category: category, payload: payload
                )
            }
        case "cancelNotification":
            if let taskId = obj["taskId"] as? String {
                DispatchQueue.main.async { [weak self] in
                    self?.delegate?.socketBridgeDidRequestCancelNotification(taskId: taskId)
                }
            }
        case "setLoginItem":
            let enabled = (obj["enabled"] as? Bool) ?? false
            DispatchQueue.main.async { [weak self] in
                self?.delegate?.socketBridgeDidRequestSetLoginItem(enabled: enabled)
            }
        case "queryLoginItem":
            DispatchQueue.main.async { [weak self] in
                self?.delegate?.socketBridgeDidRequestQueryLoginItem()
            }
        case "focusJervis":
            DispatchQueue.main.async { [weak self] in
                self?.delegate?.socketBridgeDidRequestFocusJervis()
            }
        default:
            break
        }
    }

    // MARK: - Socket setup

    private func openSocket() {
        unlink(path)
        let fd = socket(AF_UNIX, SOCK_STREAM, 0)
        guard fd >= 0 else {
            perror("[Jervis/macApp] socket")
            return
        }
        var addr = sockaddr_un()
        addr.sun_family = sa_family_t(AF_UNIX)
        let pathBytes = Array(path.utf8)
        withUnsafeMutablePointer(to: &addr.sun_path) { sunPath in
            sunPath.withMemoryRebound(to: Int8.self, capacity: 104) { buf in
                for (i, b) in pathBytes.enumerated() where i < 103 {
                    buf[i] = Int8(b)
                }
            }
        }
        var addrCopy = addr
        let bindResult = withUnsafePointer(to: &addrCopy) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size))
            }
        }
        guard bindResult == 0 else {
            perror("[Jervis/macApp] bind")
            close(fd)
            return
        }
        guard listen(fd, 1) == 0 else {
            perror("[Jervis/macApp] listen")
            close(fd)
            return
        }
        serverFd = fd
        print("[Jervis/macApp] APNs bridge listening on \(path)")
    }

    private func acceptLoop() {
        while serverFd >= 0 {
            let fd = accept(serverFd, nil, nil)
            if fd < 0 {
                if serverFd < 0 { break }
                perror("[Jervis/macApp] accept")
                continue
            }
            print("[Jervis/macApp] JVM child connected")
            stateQueue.async { [weak self] in
                guard let self = self else { return }
                if self.clientFd >= 0 { close(self.clientFd) }
                self.clientFd = fd
                self.flushPending()
            }
            startReadLoop(fd: fd)
        }
    }

    private func flushPending() {
        guard clientFd >= 0 else { return }
        var failed = false
        for line in pending {
            if !writeLine(line, fd: clientFd) {
                failed = true
                break
            }
        }
        if failed {
            close(clientFd)
            clientFd = -1
        } else {
            pending.removeAll()
        }
    }

    static func hardwareUuid() -> String? {
        let entry = IORegistryEntryFromPath(kIOMainPortDefault, "IOService:/")
        defer { IOObjectRelease(entry) }
        guard let uuid = IORegistryEntryCreateCFProperty(
            entry,
            "IOPlatformUUID" as CFString,
            kCFAllocatorDefault,
            0,
        )?.takeRetainedValue() as? String else { return nil }
        return uuid
    }
}
