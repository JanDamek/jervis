package com.jervis.orchestrator.model

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable

/**
 * Detailed specification for A2A delegation (Aider or OpenHands).
 * Produced by SolutionArchitectAgent.
 */
@Serializable
data class A2ADelegationSpec(
    @property:LLMDescription("Target A2A agent: 'aider' for small localized edits, 'openhands' for complex/broad tasks, 'junie' for high-priority/fast/complex analysis and programming (paid service)")
    val agent: String,
    @property:LLMDescription("List of files to modify (specifically for Aider)")
    val targetFiles: List<String> = emptyList(),
    @property:LLMDescription("Detailed instructions on what to change, how, and edge cases to handle")
    val instructions: String,
    @property:LLMDescription("Specific commands or steps to verify the changes (build, test, etc.)")
    val verifyInstructions: String,
    @property:LLMDescription("Reasoning behind the architectural decisions")
    val reasoning: String = "",
)
