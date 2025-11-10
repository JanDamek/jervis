package com.jervis.common.dto

import kotlinx.serialization.Serializable

@Serializable
data class JoernQueryDto(
    val query: String,
    val projectZipBase64: String? = null,
)

@Serializable
data class JoernResultDto(
    val stdout: String,
    val stderr: String? = null,
    val exitCode: Int,
)
