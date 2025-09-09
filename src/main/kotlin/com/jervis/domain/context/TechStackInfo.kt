package com.jervis.domain.context

import kotlinx.serialization.Serializable

@Serializable
data class TechStackInfo(
    val framework: String, // "Spring Boot", "Spring WebFlux"
    val language: String, // "Kotlin", "Java"
    val version: String?, // Spring Boot version
    val securityFramework: String?, // "Spring Security", "None"
    val databaseType: String?, // "MongoDB", "PostgreSQL"
    val buildTool: String?, // "Maven", "Gradle"
)
