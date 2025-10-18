package com.jervis.dto

import kotlinx.serialization.Serializable

@Serializable
data class CodingGuidelinesDto(
    val clientStandards: GuidelinesDto?,
    val projectStandards: GuidelinesDto?,
    val effectiveGuidelines: GuidelinesDto,
    val programmingStyle: ProgrammingStyleDto,
)
