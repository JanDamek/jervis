package com.jervis.domain.client

data class ProgrammingStyle(
    val language: String, // "Kotlin", "Java"
    val framework: String, // "Spring Boot WebFlux"
    val architecturalPatterns: List<String>, // ["Reactive", "SOLID", "Clean Architecture"]
    val codingConventions: Map<String, String>,
    val testingApproach: String, // "TDD", "BDD", "Integration"
    val documentationLevel: String, // "Minimal", "Standard", "Comprehensive"
)
