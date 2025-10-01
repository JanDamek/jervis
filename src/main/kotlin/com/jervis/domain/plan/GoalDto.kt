package com.jervis.domain.plan

import kotlinx.serialization.Serializable

@Serializable
data class GoalDto(
    val goalId: Int = 0,
    val goalIntent: String = "",
    val dependsOn: List<Int> = emptyList(),
)
