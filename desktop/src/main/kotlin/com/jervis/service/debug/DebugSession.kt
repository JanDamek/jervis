package com.jervis.service.debug

import java.time.LocalDateTime

data class DebugSession(
    val id: String,
    val promptType: String,
    val systemPrompt: String,
    val userPrompt: String,
    val startTime: LocalDateTime,
    val responseBuffer: StringBuilder,
    var completionTime: LocalDateTime? = null,
) {
    fun complete() {
        completionTime = LocalDateTime.now()
    }

    fun isCompleted(): Boolean = completionTime != null
}
