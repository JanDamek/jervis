package com.jervis.ui.sidebar

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jervis.ui.sidebar.BackgroundViewModel.ActiveVnc

@Composable
expect fun VncWebView(
    active: ActiveVnc,
    modifier: Modifier = Modifier,
)
