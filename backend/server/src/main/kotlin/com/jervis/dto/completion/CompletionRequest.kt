package com.jervis.dto.completion

data class CompletionRequest(
    override val model: String? = null,
    val prompt: String = "",
    override val temperature: Double? = null,
    override val maxTokens: Int? = null,
    override val stream: Boolean? = null,
) : BaseCompletionRequest(model, temperature, maxTokens, stream)
