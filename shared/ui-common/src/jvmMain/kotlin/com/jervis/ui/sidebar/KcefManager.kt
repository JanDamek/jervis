package com.jervis.ui.sidebar

import dev.datlag.kcef.KCEF
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * KCEF (Kotlin wrapper around JCEF / Chromium Embedded Framework) is the
 * runtime that backs `compose-webview-multiplatform` on JVM Desktop.
 * Without `KCEF.init { }` the WebView component renders as an empty
 * Swing panel — the noVNC iframe never loads. This object kicks off
 * initialization eagerly during app startup so the first VNC click is
 * instant on subsequent runs (first run downloads ~150 MB of CEF
 * binaries to `<userHome>/.jervis/kcef`).
 */
enum class KcefStatus { Initializing, Downloading, Ready, Error, Disabled }

object KcefManager {
    @Volatile
    private var initStarted = false

    private val _status = MutableStateFlow(KcefStatus.Initializing)
    val status: StateFlow<KcefStatus> = _status.asStateFlow()

    private val _downloadPercent = MutableStateFlow(0f)
    val downloadPercent: StateFlow<Float> = _downloadPercent.asStateFlow()

    /**
     * Prefer the CEF runtime bundled inside Jervis.app (created by the
     * Gradle `downloadCefRuntime` + `bundleCefRuntime` build tasks).
     * Fallback to `~/.jervis/kcef` for IntelliJ / dev runs where the
     * bundle isn't there — KCEF will then download CEF on first use.
     */
    private val installDir: File by lazy {
        bundledCefDir() ?: File(System.getProperty("user.home"), ".jervis/kcef").also { it.mkdirs() }
    }

    /**
     * Cache directory must always be writable. Bundled installDir is
     * read-only inside a signed .app, so use a per-user mutable path.
     */
    private val cacheDir: File by lazy {
        val home = System.getProperty("user.home")
        File(home, "Library/Caches/Jervis/cef").also { it.mkdirs() }
    }

    /**
     * Walks up from the running JVM `java.home` to find
     * `Jervis.app/Contents/Resources/cef-bundle`. jpackage layout puts
     * the runtime at `Jervis.app/Contents/runtime/Contents/Home`, so
     * three `parentFile` hops land at `Contents/`, then `Resources`.
     */
    private fun bundledCefDir(): File? {
        val javaHome = System.getProperty("java.home")?.let(::File) ?: return null
        val contents = javaHome.parentFile?.parentFile?.parentFile ?: return null
        val candidate = File(contents, "Resources/cef-bundle")
        return candidate.takeIf { File(it, "install.lock").exists() }
    }

    fun startInitialization() {
        if (initStarted) return
        initStarted = true

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                KCEF.init(
                    builder = {
                        installDir(installDir)
                        progress {
                            onDownloading { percent ->
                                _status.value = KcefStatus.Downloading
                                _downloadPercent.value = percent
                                println("KCEF: downloading CEF runtime ${percent.toInt()}%")
                            }
                            onInitialized {
                                _status.value = KcefStatus.Ready
                                println("KCEF: runtime ready (install dir: ${installDir.absolutePath})")
                            }
                        }
                        settings {
                            cachePath = cacheDir.absolutePath
                        }
                    },
                    onError = { e ->
                        _status.value = KcefStatus.Error
                        println("KCEF init error: ${e?.message}")
                        e?.printStackTrace()
                    },
                    onRestartRequired = {
                        _status.value = KcefStatus.Error
                        println("KCEF requires app restart to finish CEF installation. Please relaunch Jervis.")
                    },
                )
            } catch (e: Throwable) {
                _status.value = KcefStatus.Error
                println("KCEF init threw: ${e.message}")
                e.printStackTrace()
            }
        }
    }
}
