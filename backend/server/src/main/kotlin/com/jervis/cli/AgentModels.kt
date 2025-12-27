package com.jervis.cli

import kotlinx.serialization.Serializable

@Serializable
enum class IntentType {
    PROGRAMMING,
    QA,
    CHAT,
    UNKNOWN,
}

@Serializable
enum class UrgencyLevel {
    CRITICAL,
    HIGH,
    NORMAL,
    LOW,
}

@Serializable
enum class ProjectCertainty {
    CERTAIN,
    CANDIDATES,
    UNKNOWN,
}

@Serializable
data class IntakeDocument(
    val intent: IntentType,
    val jiraKeys: List<String> = emptyList(),
    val unknowns: List<String> = emptyList(),
    val urgency: UrgencyLevel = UrgencyLevel.NORMAL,
)

@Serializable
data class ProjectScopeSummary(
    val projects: List<String>,
    val certainty: ProjectCertainty,
)

@Serializable
data class ScopeArgs(
    val jiraKeys: List<String> = emptyList(),
    val keywords: String? = null,
)

@Serializable
data class OrchestratorResponse(
    val summary: String,
    val intent: IntentType,
    val projects: List<String> = emptyList(),
)
