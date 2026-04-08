package com.jervis.desktop

import java.util.prefs.Preferences

/**
 * Desktop-local, machine-specific settings.
 *
 * These settings are intentionally NOT stored on the Jervis server because
 * they describe hardware that is unique to the machine the desktop app runs
 * on — a BlackHole device on macOS, a WASAPI loopback on Windows and a
 * PulseAudio monitor source on Linux. Storing them per-client on the server
 * would be wrong: the same client may have two developer machines with
 * completely different audio devices.
 *
 * Backed by `java.util.prefs.Preferences` (the JVM's cross-platform
 * user-preferences store — `~/Library/Preferences/com.jervis.desktop.plist`
 * on macOS, the registry on Windows, `~/.java/.userPrefs/...` on Linux).
 *
 * Environment variables take precedence over stored values so operators can
 * pin a value from the launch script / K8s manifest without touching the
 * preference store.
 */
class DesktopLocalSettings {
    private val prefs: Preferences = Preferences.userRoot().node("com/jervis/desktop")

    /**
     * The loopback/capture device name passed to ffmpeg by
     * [com.jervis.desktop.meeting.DesktopMeetingRecorder]. `null` means "use
     * the OS default" — BlackHole 2ch on macOS, WASAPI `loopback` on Windows,
     * `default.monitor` on Linux.
     */
    fun getAudioLoopbackDevice(): String? {
        System.getenv("JERVIS_AUDIO_LOOPBACK_DEVICE")?.takeIf { it.isNotBlank() }?.let { return it }
        return prefs.get(KEY_AUDIO_LOOPBACK_DEVICE, null)?.takeIf { it.isNotBlank() }
    }

    fun setAudioLoopbackDevice(device: String?) {
        if (device.isNullOrBlank()) {
            prefs.remove(KEY_AUDIO_LOOPBACK_DEVICE)
        } else {
            prefs.put(KEY_AUDIO_LOOPBACK_DEVICE, device.trim())
        }
        runCatching { prefs.flush() }
    }

    companion object {
        private const val KEY_AUDIO_LOOPBACK_DEVICE = "audio.loopback.device"
    }
}
