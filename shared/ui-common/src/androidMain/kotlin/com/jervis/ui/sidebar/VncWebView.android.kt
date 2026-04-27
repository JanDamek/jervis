package com.jervis.ui.sidebar

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import com.jervis.ui.sidebar.BackgroundViewModel.ActiveVnc
import com.multiplatform.webview.web.WebView
import com.multiplatform.webview.web.rememberWebViewState

@Composable
actual fun VncWebView(
    active: ActiveVnc,
    modifier: Modifier,
) {
    // key(connectionId) — see VncWebView.jvm.kt for rationale.
    key(active.connectionId) {
        val webViewState = rememberWebViewState(active.vncUrl)
        // noVNC's webutil.js calls localStorage.getItem(...) for per-session
        // settings (resolution, scaling, password). Android WebView ships
        // with JavaScript + DOM storage *disabled* by default; without these
        // the page throws "Cannot read properties of null (reading 'getItem')"
        // immediately on load and nothing renders.
        webViewState.webSettings.apply {
            isJavaScriptEnabled = true
            androidWebSettings.domStorageEnabled = true
        }
        WebView(
            state = webViewState,
            modifier = modifier.fillMaxSize(),
        )
    }
}
