package com.jervis.ui.questions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jervis.dto.AgentQuestionDto
import com.jervis.ui.design.JCard
import com.jervis.ui.design.JCenteredLoading
import com.jervis.ui.design.JEmptyState
import com.jervis.ui.design.JErrorState
import com.jervis.ui.design.JIconButton
import com.jervis.ui.design.JPrimaryButton
import com.jervis.ui.design.JSecondaryButton
import com.jervis.ui.design.JTextField
import com.jervis.ui.design.JervisSpacing

/**
 * Panel showing list of pending agent questions.
 * Used in chat area as a tab or overlay.
 */
@Composable
fun PendingQuestionsPanel(
    viewModel: PendingQuestionsViewModel,
    modifier: Modifier = Modifier,
) {
    val questions by viewModel.questions.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    // Load questions when panel becomes visible
    LaunchedEffect(Unit) {
        viewModel.loadPendingQuestions()
    }

    Column(modifier = modifier.fillMaxWidth()) {
        when {
            isLoading -> JCenteredLoading()
            error != null -> JErrorState(
                message = error ?: "Chyba",
                onRetry = { viewModel.loadPendingQuestions() },
            )
            questions.isEmpty() -> JEmptyState(
                message = "Žádné čekající dotazy",
                icon = Icons.Default.QuestionAnswer,
            )
            else -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(JervisSpacing.itemGap),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    items(questions, key = { it.id }) { question ->
                        PendingQuestionCard(
                            question = question,
                            onAnswer = { answer -> viewModel.answerQuestion(question.id, answer) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Card for a single pending agent question.
 * Shows priority badge, agent type, question text, collapsible context,
 * text input for answer + [Odpovědět] and [Ignorovat] buttons.
 */
@Composable
private fun PendingQuestionCard(
    question: AgentQuestionDto,
    onAnswer: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var answerText by remember { mutableStateOf("") }
    var contextExpanded by remember { mutableStateOf(false) }

    JCard(modifier = modifier) {
        // Header row: priority badge + agent type + task name
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PriorityBadge(priority = question.priority)
            Text(
                text = question.agentType,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "Task: ${question.taskId.takeLast(6)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        Spacer(Modifier.height(JervisSpacing.itemGap))

        // Question text
        Text(
            text = question.question,
            style = MaterialTheme.typography.bodyMedium,
        )

        // Collapsible context
        if (question.context.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                JIconButton(
                    onClick = { contextExpanded = !contextExpanded },
                    icon = if (contextExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (contextExpanded) "Skrýt kontext" else "Zobrazit kontext",
                )
                Text(
                    text = "Kontext",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = contextExpanded) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = question.context,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(JervisSpacing.itemGap))

        // Answer input
        JTextField(
            value = answerText,
            onValueChange = { answerText = it },
            label = "Odpověď",
            placeholder = "Zadejte odpověď...",
            singleLine = false,
            minLines = 2,
            maxLines = 5,
        )

        Spacer(Modifier.height(JervisSpacing.itemGap))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            JPrimaryButton(
                onClick = {
                    if (answerText.isNotBlank()) {
                        onAnswer(answerText)
                        answerText = ""
                    }
                },
                enabled = answerText.isNotBlank(),
            ) {
                Text("Odpovědět")
            }
            JSecondaryButton(
                onClick = { onAnswer("") },
            ) {
                Text("Ignorovat")
            }
        }
    }
}

/**
 * Priority badge with colored dot.
 * BLOCKING = red, QUESTION = orange, INFO = blue.
 */
@Composable
private fun PriorityBadge(
    priority: String,
    modifier: Modifier = Modifier,
) {
    val color = when (priority.uppercase()) {
        "BLOCKING" -> MaterialTheme.colorScheme.error
        "QUESTION" -> Color(0xFFF57C00) // orange
        "INFO" -> Color(0xFF1976D2) // blue
        else -> MaterialTheme.colorScheme.outline
    }
    val label = when (priority.uppercase()) {
        "BLOCKING" -> "Blokující"
        "QUESTION" -> "Dotaz"
        "INFO" -> "Info"
        else -> priority
    }

    Surface(
        color = color.copy(alpha = 0.15f),
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Surface(
                modifier = Modifier.size(8.dp),
                shape = CircleShape,
                color = color,
            ) {}
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
    }
}
