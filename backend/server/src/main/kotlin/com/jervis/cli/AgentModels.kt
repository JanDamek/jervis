package com.jervis.cli

import ai.koog.agents.core.tools.annotations.LLMDescription
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

@LLMDescription("Intake analysis result")
@Serializable
data class IntakeDocument(
    @LLMDescription("Primary intent")
    val intent: IntentType,
    @LLMDescription("JIRA keys mentioned")
    val jiraKeys: List<String> = emptyList(),
    @LLMDescription("What information is missing")
    val unknowns: List<String> = emptyList(),
    @LLMDescription("Urgency level")
    val urgency: UrgencyLevel = UrgencyLevel.NORMAL,
)

@LLMDescription("Project scope resolution")
@Serializable
data class ProjectScopeSummary(
    @LLMDescription("Top 3-5 relevant projects")
    val projects: List<String>,
    @LLMDescription("Certainty level")
    val certainty: ProjectCertainty,
)

@LLMDescription("Input for scope agent")
@Serializable
data class ScopeArgs(
    @LLMDescription("JIRA keys from intake")
    val jiraKeys: List<String> = emptyList(),
    @LLMDescription("Keywords for context matching")
    val keywords: String? = null,
)

@LLMDescription("Final orchestrator response")
@Serializable
data class OrchestratorResponse(
    @LLMDescription("User-facing summary")
    val summary: String,
    @LLMDescription("Intent processed")
    val intent: IntentType,
    @LLMDescription("Projects identified")
    val projects: List<String> = emptyList(),
)
