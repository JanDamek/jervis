package com.jervis.domain.client

data class CodingGuidelines(
    val clientStandards: Guidelines?,
    val projectStandards: Guidelines?,
    val effectiveGuidelines: Guidelines,
    val programmingStyle: ProgrammingStyle,
)
