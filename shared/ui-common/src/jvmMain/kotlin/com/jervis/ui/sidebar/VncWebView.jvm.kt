package com.jervis.ui.sidebar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.key
import com.jervis.ui.sidebar.BackgroundViewModel.ActiveVnc
import com.multiplatform.webview.web.WebView
import com.multiplatform.webview.web.rememberWebViewState

@Composable
actual fun VncWebView(
    active: ActiveVnc,
    modifier: Modifier,
) {
    // Lazy KCEF init — starts on first VNC click. Eager init at app
    // startup races Skia's libjawt bridge and crashes Skiko_GetAWT.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        KcefManager.startInitialization()
    }

    val status by KcefManager.status.collectAsState()
    val percent by KcefManager.downloadPercent.collectAsState()

    when (status) {
        KcefStatus.Ready -> {
            // key(connectionId) forces a full WebView recreate when the
            // user switches between VNC sessions. Without it the cached
            // WebView instance keeps showing the previous URL even after
            // rememberWebViewState is keyed on the new vncUrl — KCEF
            // browser handle survives recomposition by design.
            key(active.connectionId) {
                val webViewState = rememberWebViewState(active.vncUrl)
                WebView(
                    state = webViewState,
                    modifier = modifier.fillMaxSize(),
                )
            }
        }
        KcefStatus.Initializing,
        KcefStatus.Downloading -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (status == KcefStatus.Downloading) {
                        Text(
                            "Stahuji webview runtime…",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { (percent / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxSize().height(6.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "${percent.toInt()} %",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Inicializuji webview runtime…",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        }
        KcefStatus.Error,
        KcefStatus.Disabled -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "VNC viewer není k dispozici",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Inicializace webview runtime selhala — zkuste restartovat aplikaci nebo otevřít VNC v externím prohlížeči.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
