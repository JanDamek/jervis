package com.jervis.dto.user

import kotlinx.serialization.Serializable

@Serializable
data class UserTaskCountDto(
    val clientId: String,
    val activeCount: Int,
)
