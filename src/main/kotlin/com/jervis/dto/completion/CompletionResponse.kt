package com.jervis.dto.completion

import com.jervis.dto.Usage

data class CompletionResponse(
    override val id: String,
    override val `object`: String = "text_completion",
    override val created: Long,
    override val model: String,
    val choices: List<CompletionChoice>,
    override val usage: Usage,
) : BaseCompletionResponse(id, `object`, created, model, usage)
