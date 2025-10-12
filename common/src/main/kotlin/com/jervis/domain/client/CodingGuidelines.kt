package com.jervis.domain.client

import kotlinx.serialization.Serializable

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
