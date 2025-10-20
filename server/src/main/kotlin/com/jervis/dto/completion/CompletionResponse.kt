package com.jervis.dto.completion

import com.jervis.dto.Usage

data class CompletionResponse(
    val id: String,
    val `object`: String = "text_completion",
    val created: Long,
    val model: String,
    val choices: List<CompletionChoice>,
    val usageDto: Usage,
)
