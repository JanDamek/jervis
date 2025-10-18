package com.jervis.dto

import kotlinx.serialization.Serializable

@Serializable
data class ReviewPolicyDto(
    val requireCodeOwner: Boolean = true,
    val minApprovals: Int = 1,
    val reviewersHints: List<String> = emptyList(),
)
