import Foundation
import Darwin
import IOKit

/// Tiny newline-delimited JSON protocol over a Unix domain socket.
///
/// Swift host writes messages to the socket; the JVM child connects and
/// reads them. One message per line. Supported kinds:
///
///   { "kind": "token", "hexToken": "...", "deviceId": "..." }
///   { "kind": "payload", "userInfo": { ... } }
///
/// The JVM side keeps the connection open for the lifetime of the
/// Compose window. If it drops, we queue messages and replay them on
/// the next connect (so an APNs token that arrives before the JVM has
/// latched the socket isn't lost).
final class SocketBridge {
    private let path: String
    private var serverFd: Int32 = -1
    private var clientFd: Int32 = -1
    private var pending: [String] = []
    // State mutex — protects clientFd + pending. The accept() thread must
    // NOT share a serial queue with send/flush, otherwise the blocking
    // accept() call holds the queue forever and queued sends never fire.
    private let stateQueue = DispatchQueue(label: "jervis.macapp.socketbridge.state")
    // Dedicated accept thread.
    private var acceptThread: Thread?

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

    private func send(line: String) {
        stateQueue.async { [weak self] in
            guard let self = self else { return }
            if self.clientFd >= 0 {
                if !self.writeLine(line, fd: self.clientFd) {
                    // Write failed — client died without closing cleanly.
                    // Queue the line for the next reconnect.
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
                // Close any previous connection before accepting a new one.
                if self.clientFd >= 0 { close(self.clientFd) }
                self.clientFd = fd
                self.flushPending()
            }
        }
    }

    /// Must be called on stateQueue.
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
            // Keep pending queue intact for the next connect.
        } else {
            pending.removeAll()
        }
    }

    /// Stable per-machine UUID from IOKit so the deviceId survives
    /// reinstalls (matches what Apple's `ioreg -rd1 -c IOPlatformExpertDevice`
    /// returns under `IOPlatformUUID`).
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
