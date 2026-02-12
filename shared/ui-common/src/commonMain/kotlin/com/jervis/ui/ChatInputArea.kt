package com.jervis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.jervis.ui.design.JPrimaryButton
import com.jervis.ui.design.JTextField

@Composable
internal fun InputArea(
    inputText: String,
    onInputChanged: (String) -> Unit,
    onSendClick: () -> Unit,
    enabled: Boolean,
    queueSize: Int = 0,
    runningProjectId: String? = null,
    currentProjectId: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        JTextField(
            value = inputText,
            onValueChange = onInputChanged,
            label = "",
            placeholder = "Napište zprávu...",
            enabled = enabled,
            modifier =
                Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp, max = 120.dp)
                    .onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.key == Key.Enter && keyEvent.type == KeyEventType.KeyDown) {
                            if (keyEvent.isShiftPressed) {
                                // Shift+Enter → new line (let the event pass through)
                                false
                            } else {
                                // Enter → send message
                                if (enabled && inputText.isNotBlank()) {
                                    onSendClick()
                                }
                                true // consume the event
                            }
                        } else {
                            false
                        }
                    },
            maxLines = 4,
            singleLine = false,
        )

        val buttonText =
            when {
                runningProjectId == null || runningProjectId == "none" -> "Odeslat"
                runningProjectId == currentProjectId -> "Odeslat" // Inline delivery to running task
                else -> "Do fronty" // Different project or queue has items
            }

        JPrimaryButton(
            onClick = onSendClick,
            enabled = enabled && inputText.isNotBlank(),
            modifier = Modifier.height(56.dp),
        ) {
            Text(buttonText)
        }
    }
}
