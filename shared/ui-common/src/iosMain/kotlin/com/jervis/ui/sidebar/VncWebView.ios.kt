package com.jervis.ui.sidebar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import com.jervis.ui.sidebar.BackgroundViewModel.ActiveVnc
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun VncWebView(
    active: ActiveVnc,
    modifier: Modifier,
) {
    // key(connectionId) forces a full UIKitView teardown/recreate when the
    // user switches VNC sessions. Without it the cached WKWebView keeps
    // serving the previous URL because UIKitView's `update` block is keyed
    // on the composable identity, not the URL.
    key(active.connectionId) {
        UIKitView(
            factory = {
                val config = WKWebViewConfiguration().apply {
                    // WKWebView has localStorage/sessionStorage on by default
                    // (WKWebsiteDataStore.defaultDataStore()), JavaScript too,
                    // so noVNC's webutil.js localStorage.getItem(...) works
                    // out of the box — no extra wiring needed.
                }
                val webView = WKWebView(
                    frame = CGRectMake(0.0, 0.0, 0.0, 0.0),
                    configuration = config,
                )
                NSURL.URLWithString(active.vncUrl)?.let { url ->
                    webView.loadRequest(NSURLRequest.requestWithURL(url))
                }
                webView
            },
            modifier = modifier,
            update = { webView ->
                NSURL.URLWithString(active.vncUrl)?.let { url ->
                    webView.loadRequest(NSURLRequest.requestWithURL(url))
                }
            },
        )
    }
}
