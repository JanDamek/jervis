package com.jervis.orchestrator.model

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mandatory context for task execution.
 *
 * Provided by ContextAgent at the start of every orchestrator run.
 * Contains only LLM-relevant information - NO system IDs (those are in TaskDocument).
 */
@Serializable
@SerialName("ContextPack")
@LLMDescription("Execution context containing project details, build/test commands, environment information, and known/missing facts. Provided at the start of task execution to inform planning and execution decisions.")
data class ContextPack(
    @property:LLMDescription("Project name (human-readable, for LLM context and logging)")
    val projectName: String?,

    @property:LLMDescription("Absolute path to project directory where code resides (from DirectoryStructureService)")
    val projectPath: String,

    @property:LLMDescription("Build commands for CODING_VERIFY tasks (e.g., ['./gradlew build', 'npm run build'])")
    val buildCommands: List<String>,

    @property:LLMDescription("Test commands for CODING_VERIFY tasks (e.g., ['./gradlew test', 'npm test'])")
    val testCommands: List<String>,

    @property:LLMDescription("Environment description and technology stack (e.g., 'Kotlin/Gradle project', 'Node.js/npm with TypeScript')")
    val environmentHints: String,

    @property:LLMDescription("Facts already known from GraphDB/RAG - use this to avoid redundant research and reference established knowledge")
    val knownFacts: List<String> = emptyList(),

    @property:LLMDescription("Information identified as missing or unclear - triggers research tasks to fill gaps before execution")
    val missingInfo: List<String> = emptyList(),
)
