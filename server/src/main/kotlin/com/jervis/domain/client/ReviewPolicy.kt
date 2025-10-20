package com.jervis.domain.client

data class ReviewPolicy(
    val requireCodeOwner: Boolean = true,
    val minApprovals: Int = 1,
    val reviewersHints: List<String> = emptyList(),
)
