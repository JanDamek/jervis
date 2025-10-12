package com.jervis.dto

import com.jervis.domain.project.ProjectContextInfo
import kotlinx.serialization.Serializable

@Serializable
data class TaskContextDto(
    val id: String,
    val client: ClientDto,
    val project: ProjectDto,
    val name: String = "New Context",
    val plans: List<PlanDto> = emptyList(),
    val createdAt: String,
    val updatedAt: String,
    val quick: Boolean,
    val projectContextInfo: ProjectContextInfo? = null,
    val contextSummary: String = "",
)
