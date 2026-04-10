package com.jervis.ui.meeting

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jervis.ui.design.JervisSpacing

/**
 * Single bubble displaying accumulated live assist hints from KB.
 * All hints joined into one scrollable text — "celá nápověda je jedna bublina".
 */
@Composable
fun LiveHintsBubble(
    hints: List<String>,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()

    // Auto-scroll to bottom when new hints arrive
    LaunchedEffect(hints.size) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF2E7D32).copy(alpha = 0.08f))
            .padding(horizontal = JervisSpacing.outerPadding, vertical = 8.dp)
            .verticalScroll(scrollState),
    ) {
        Text(
            text = "Nápověda z KB",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF2E7D32),
        )
        Text(
            text = hints.joinToString("\n\n"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
