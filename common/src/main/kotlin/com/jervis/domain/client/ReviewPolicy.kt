package com.jervis.domain.client

import kotlinx.serialization.Serializable

@Serializable
data class ReviewPolicy(
    val requireCodeOwner: Boolean = true,
    val minApprovals: Int = 1,
    val reviewersHints: List<String> = emptyList(),
)
