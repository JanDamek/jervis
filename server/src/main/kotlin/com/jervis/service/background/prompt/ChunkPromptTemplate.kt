package com.jervis.service.background.prompt

import com.jervis.domain.background.BackgroundTask

/**
 * Template for generating prompts for background chunk processing.
 */
object ChunkPromptTemplate {
    private const val SYSTEM_PROMPT =
        """You are JERVIS Background Analyzer. You run only during idle time.
Rules:
- Work strictly on the given task chunk.
- Be interruptible at any moment. Keep responses compact.
- Return ONLY valid JSON per schema. No <think> tags.
- If confidence < 0.6 mark "needs_review": true in artifact payload.

Input:
- taskType: The type of background task being executed
- targetRef: Reference to the target being analyzed
- checkpoint: Current progress checkpoint (if resuming)
- chunkData: Data for this specific chunk

Output JSON Schema:
{
  "artifacts": [
    {
      "type": "RAG_NOTE|RAG_LINK|EVIDENCE|PLAN|DRAFT_REPLY|GUIDELINE_PROPOSAL",
      "payload": { ... },
      "confidence": 0.0
    }
  ],
  "progressDelta": 0.0,
  "newCheckpoint": {
    "kind": "DocumentScan|CodeAnalysis|ThreadClustering|Generic",
    "data": { ... }
  },
  "next_actions": ["CONTINUE" | "REQUEST_MORE_CONTEXT" | "STOP"]
}"""

    fun buildSystemPrompt(): String = SYSTEM_PROMPT

    fun buildUserPrompt(
        task: BackgroundTask,
        chunkData: String,
    ): String =
        buildString {
            appendLine("Task Type: ${task.taskType}")
            appendLine("Target: ${task.targetRef.type} (${task.targetRef.id})")
            appendLine("Current Progress: ${task.progress}")

            if (task.checkpoint != null) {
                appendLine("Checkpoint: ${task.checkpoint.kind}")
            }

            appendLine()
            appendLine("Chunk Data:")
            appendLine(chunkData)

            appendLine()
            appendLine("Generate JSON response following the output schema.")
        }
}
