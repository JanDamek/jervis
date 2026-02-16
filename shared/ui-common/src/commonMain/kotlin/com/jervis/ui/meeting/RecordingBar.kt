package com.jervis.ui.meeting

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jervis.ui.design.JervisSpacing

/**
 * Global recording bar displayed at the top of the app during active recording.
 * Visible from any screen — enables stopping recording or navigating to Meetings.
 */
@Composable
fun RecordingBar(
    durationSeconds: Long,
    uploadState: UploadState,
    onStop: () -> Unit,
    onNavigateToMeetings: () -> Unit,
    isOnMeetingsScreen: Boolean,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "recording_bar")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot_blink",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f))
            .padding(horizontal = 16.dp)
            .heightIn(min = JervisSpacing.touchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Animated red dot
        Spacer(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(Color.Red)
                .alpha(dotAlpha),
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = "Nahravani",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = formatDuration(durationSeconds),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )

        // Upload state indicator
        when (uploadState) {
            is UploadState.Retrying -> {
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "(retry ${uploadState.attempt}/${uploadState.maxAttempts})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f),
                )
            }
            is UploadState.RetryFailed -> {
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "(upload failed)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            else -> {}
        }

        Spacer(modifier = Modifier.weight(1f))

        // Navigate to Meetings link (only when not already on Meetings screen)
        if (!isOnMeetingsScreen) {
            Text(
                text = "Meetingy",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClick = onNavigateToMeetings)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        TextButton(onClick = onStop) {
            Text(
                text = "Zastavit",
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
