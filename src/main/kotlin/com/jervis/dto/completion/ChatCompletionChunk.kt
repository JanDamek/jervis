package com.jervis.dto.completion

import com.jervis.dto.ChunkChoice
import com.jervis.dto.Usage

// Classes for streaming responses
data class ChatCompletionChunk(
    override val id: String,
    override val `object`: String,
    override val created: Long,
    override val model: String,
    val choices: List<ChunkChoice>,
    override val usage: Usage? = null,
) : BaseCompletionResponse(id, `object`, created, model, usage)
