package com.jervis.domain.context

import kotlinx.serialization.Serializable

@Serializable
data class Guidelines(
    val rules: List<String> = emptyList(),
    val patterns: List<String> = emptyList(),
    val conventions: Map<String, String> = emptyMap(),
    val restrictions: List<String> = emptyList(),
)

@Serializable
data class ProgrammingStyle(
    val language: String, // "Kotlin", "Java"
    val framework: String, // "Spring Boot WebFlux"
    val architecturalPatterns: List<String>, // ["Reactive", "SOLID", "Clean Architecture"]
    val codingConventions: Map<String, String>,
    val testingApproach: String, // "TDD", "BDD", "Integration"
    val documentationLevel: String, // "Minimal", "Standard", "Comprehensive"
)

@Serializable
data class CodingGuidelines(
    val clientStandards: Guidelines?,
    val projectStandards: Guidelines?,
    val effectiveGuidelines: Guidelines,
    val programmingStyle: ProgrammingStyle,
)
