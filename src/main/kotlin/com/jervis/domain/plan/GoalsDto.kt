package com.jervis.domain.plan

import kotlinx.serialization.Serializable

@Serializable
data class GoalsDto(
    val goals: List<GoalDto> = listOf(GoalDto()),
)
