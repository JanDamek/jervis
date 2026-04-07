import AVFoundation
import AppKit
import Foundation

/// Native macOS mic capture helper for Jervis Desktop.
///
/// Captures audio from the default mic via AVAudioEngine, converts to
/// PCM 16kHz 16-bit mono, and outputs raw bytes to stdout.
///
/// This is needed because Java Sound API (TargetDataLine) doesn't properly
/// trigger macOS TCC permission dialogs — it opens the mic line successfully
/// but returns all-zeros audio when permission isn't granted.
///
/// AVAudioEngine goes through AVFoundation which correctly integrates
/// with macOS TCC: it triggers the "wants to use your microphone" dialog
/// and receives actual audio data after permission is granted.
///
/// The JVM AudioRecorder launches this as a subprocess and reads PCM
/// from stdout. Stderr is used for diagnostic messages.
///
/// Lifecycle: runs until stdin is closed (parent JVM exits) or SIGTERM.

class MicCapture {
    let engine = AVAudioEngine()
    let sampleRate: Double
    let channels: UInt32

    init(sampleRate: Double = 16000, channels: UInt32 = 1) {
        self.sampleRate = sampleRate
        self.channels = channels
    }

    func start() -> Bool {
        let inputNode = engine.inputNode
        let inputFormat = inputNode.outputFormat(forBus: 0)

        guard let outputFormat = AVAudioFormat(
            commonFormat: .pcmFormatInt16,
            sampleRate: sampleRate,
            channels: channels,
            interleaved: true
        ) else {
            log("Cannot create output format")
            return false
        }

        guard let converter = AVAudioConverter(from: inputFormat, to: outputFormat) else {
            log("Cannot create converter from \(inputFormat) to \(outputFormat)")
            return false
        }

        let bufferSize: AVAudioFrameCount = AVAudioFrameCount(sampleRate * 0.1) // 100ms

        inputNode.installTap(onBus: 0, bufferSize: 1024, format: inputFormat) { (buffer, _) in
            guard let convertedBuffer = AVAudioPCMBuffer(
                pcmFormat: outputFormat,
                frameCapacity: bufferSize
            ) else { return }

            var error: NSError?
            let status = converter.convert(to: convertedBuffer, error: &error) { _, outStatus in
                outStatus.pointee = .haveData
                return buffer
            }

            if status == .error { return }

            if let channelData = convertedBuffer.int16ChannelData {
                let frameCount = Int(convertedBuffer.frameLength)
                if frameCount > 0 {
                    let data = Data(bytes: channelData[0], count: frameCount * 2)
                    FileHandle.standardOutput.write(data)
                }
            }
        }

        do {
            try engine.start()
            log("Engine started: \(inputFormat.sampleRate)Hz → \(sampleRate)Hz, capturing...")
            return true
        } catch {
            log("Engine start failed: \(error)")
            return false
        }
    }

    func stop() {
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
    }

    private func log(_ msg: String) {
        FileHandle.standardError.write("MicCapture: \(msg)\n".data(using: .utf8)!)
    }
}

// ── App Delegate for GUI context (needed for TCC permission dialogs) ──

class AppDelegate: NSObject, NSApplicationDelegate {
    var captureInstance: MicCapture?

    func applicationDidFinishLaunching(_ notification: Notification) {
        // Hide from dock (we're a background helper)
        NSApp.setActivationPolicy(.accessory)

        let status = AVCaptureDevice.authorizationStatus(for: .audio)
        log("auth status=\(status.rawValue) (0=notDetermined, 2=denied, 3=authorized)")

        if status == .notDetermined {
            log("requesting mic permission...")
            AVCaptureDevice.requestAccess(for: .audio) { [self] granted in
                if !granted {
                    log("permission DENIED")
                    exit(1)
                }
                log("permission GRANTED")
                DispatchQueue.main.async { self.startCapture() }
            }
        } else if status == .denied || status == .restricted {
            log("mic DENIED. Open System Settings → Privacy → Microphone")
            exit(1)
        } else {
            startCapture()
        }
    }

    func startCapture() {
        captureInstance = MicCapture()
        guard captureInstance!.start() else { exit(1) }

        // Monitor stdin in background — when parent closes stdin, exit
        DispatchQueue.global().async {
            var buf = [UInt8](repeating: 0, count: 1)
            while true {
                let bytesRead = Foundation.read(STDIN_FILENO, &buf, 1)
                if bytesRead <= 0 { break }
            }
            self.captureInstance?.stop()
            log("parent closed stdin, exiting")
            exit(0)
        }
    }
}

private func log(_ msg: String) {
    FileHandle.standardError.write("MicCapture: \(msg)\n".data(using: .utf8)!)
}

// ── Main ──────────────────────────────────────────────────────────────

signal(SIGTERM) { _ in exit(0) }
signal(SIGINT) { _ in exit(0) }

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.run()
