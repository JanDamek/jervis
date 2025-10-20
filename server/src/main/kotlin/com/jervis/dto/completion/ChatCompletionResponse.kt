package com.jervis.dto.completion

import com.jervis.dto.Choice
import com.jervis.dto.Usage

data class ChatCompletionResponse(
    val id: String,
    val `object`: String,
    val created: Long,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage?,
)
