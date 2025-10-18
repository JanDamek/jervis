package com.jervis.dto

import kotlinx.serialization.Serializable

@Serializable
data class ProgrammingStyleDto(
    val language: String,
    val framework: String,
    val architecturalPatterns: List<String>,
    val codingConventions: Map<String, String>,
    val testingApproach: String,
    val documentationLevel: String,
)
