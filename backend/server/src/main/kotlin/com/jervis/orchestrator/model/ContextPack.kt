package com.jervis.orchestrator.model

import kotlinx.serialization.Serializable

/**
 * Mandatory context for task execution.
 *
 * Provided by ContextAgent at the start of every orchestrator run.
 * Contains only LLM-relevant information - NO system IDs (those are in TaskDocument).
 */
@Serializable
data class ContextPack(
    /** Project name (human-readable, for LLM context) */
    val projectName: String?,

    /** Project path where code lives (from DirectoryStructureService) */
    val projectPath: String,

    /** Build commands for CODING_VERIFY tasks (from ProjectDocument.buildConfig) */
    val buildCommands: List<String>,

    /** Test commands for CODING_VERIFY tasks */
    val testCommands: List<String>,

    /** Environment hints (e.g., "Kotlin/Gradle project", "Node.js/npm", etc.) */
    val environmentHints: String,

    /** Facts already known from GraphDB/RAG (avoid redundant research) */
    val knownFacts: List<String> = emptyList(),

    /** Information identified as missing (triggers research tasks) */
    val missingInfo: List<String> = emptyList(),
)
