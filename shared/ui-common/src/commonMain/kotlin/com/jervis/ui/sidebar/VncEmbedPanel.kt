package com.jervis.ui.sidebar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jervis.ui.sidebar.BackgroundViewModel.ActiveVnc
import com.jervis.ui.util.openUrlInBrowser

/**
 * Embedded VNC viewer panel — replaces the chat content area while the
 * user is connected to a browser pod's noVNC display.
 *
 * The `vncUrl` carries a one-shot token (minted by the
 * `IConnectionService.getBrowserSessionStatus` call in
 * [BackgroundViewModel.openVncEmbed]). The vnc-router consumes the
 * token on the first request and sets a `vnc_session` cookie; the URL
 * in the WebView address bar stays stable from then on (per
 * `feedback-vnc-no-password-in-url`).
 *
 * Header: title + ↗ open in external browser + × close.
 *
 * Backed by `compose-webview-multiplatform` — KCEF on Desktop, native
 * WebView on Android, WKWebView on iOS. Per `feedback-no-quickfix`:
 * single multiplatform dependency vs three custom expect/actual impls.
 */
@Composable
fun VncEmbedPanel(
    active: ActiveVnc,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = "VNC: ${active.connectionLabel}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = active.connectionId,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { openUrlInBrowser(active.vncUrl) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "Otevřít v prohlížeči",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Zavřít VNC",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            VncWebView(
                active = active,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
