package com.jervis.service.rag.domain

import kotlinx.serialization.Serializable

/**
 * Response from synthesis LLM containing the final answer.
 */
@Serializable
data class AnswerResponse(
    val answer: String = "",
)
