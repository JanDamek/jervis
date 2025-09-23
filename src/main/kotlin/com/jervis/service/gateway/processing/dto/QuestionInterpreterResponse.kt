package com.jervis.service.gateway.processing.dto

import kotlinx.serialization.Serializable

/**
 * Response data class for QUESTION_INTERPRETER LLM prompt.
 * Represents the structured output for translating and decomposing user requests.
 */
@Serializable
data class QuestionInterpreterResponse(
    val englishText: String,
    val originalLanguage: String,
    val contextName: String,
    val questionChecklist: List<String>
)