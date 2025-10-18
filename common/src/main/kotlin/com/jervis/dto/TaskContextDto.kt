package com.jervis.dto

import com.jervis.common.Constants
import kotlinx.serialization.Serializable

@Serializable
data class TaskContextDto(
    val id: String = Constants.GLOBAL_ID_STRING,
    val client: ClientDto,
    val project: ProjectDto,
    val name: String = "New Context",
    val plans: List<PlanDto> = emptyList(),
    val quick: Boolean,
    val projectContextInfo: ProjectContextInfoDto? = null,
    val contextSummary: String = "",
)
