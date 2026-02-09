package com.jervis.ui.audio

actual class AudioPlayer actual constructor() {
    actual fun play(audioData: ByteArray) { /* TODO */ }
    actual fun stop() {}
    actual val isPlaying: Boolean get() = false
    actual fun release() {}
}
