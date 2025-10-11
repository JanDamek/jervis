package com.jervis.dto.completion

import com.jervis.dto.Choice
import com.jervis.dto.Usage
import kotlinx.serialization.Serializable

@Serializable
data class ChatCompletionResponse(
    override val id: String,
    override val `object`: String,
    override val created: Long,
    override val model: String,
    val choices: List<Choice>,
    override val usage: Usage?,
) : BaseCompletionResponse(id, `object`, created, model, usage)
