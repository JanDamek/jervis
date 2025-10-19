package com.jervis.service.debug

import java.time.LocalDateTime

data class DebugSession(
    val id: String,
    val promptType: String,
    val systemPrompt: String,
    val userPrompt: String,
    val startTime: LocalDateTime,
    val responseBuffer: StringBuilder,
    val clientId: String? = null,
    val clientName: String? = null,
    var completionTime: LocalDateTime? = null,
) {
    fun complete() {
        completionTime = LocalDateTime.now()
    }

    fun isCompleted(): Boolean = completionTime != null

    fun getTabLabel(): String =
        buildString {
            if (clientName != null) {
                append("[$clientName] ")
            } else {
                append("[System] ")
            }
            append(promptType)
            if (isCompleted()) {
                append(" ✓")
            }
        }
}
