package com.jervis.service.mcp.tools

import com.jervis.domain.context.TaskContext
import com.jervis.domain.context.TechStackInfo
import com.jervis.domain.plan.Plan
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.gateway.LlmGateway
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.mcp.util.McpFinalPromptProcessor
import com.jervis.service.prompts.PromptRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class EnhancedRagQueryTool(
    private val embeddingGateway: EmbeddingGateway,
    private val vectorStorage: VectorStorageRepository,
    private val llmGateway: LlmGateway,
    private val mcpFinalPromptProcessor: McpFinalPromptProcessor,
    private val promptRepository: PromptRepository,
    private val ragQueryTool: RagQueryTool,
) : McpTool {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val name: String = "enhanced-rag-query"
    override val description: String
        get() = "Enhanced RAG query tool with context-aware technology stack detection"

    @Serializable
    data class EnhancedRagQueryRequest(
        val query: String,
        val embedding: String,
        val topK: Int? = null,
        val minScore: Float? = null,
        val finalPrompt: String? = null,
        val filters: Map<String, String>? = null,
        val techStackContext: String? = null,
    )

    override suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
    ): ToolResult {
        logger.debug {
            "ENHANCED_RAG_QUERY_START: Executing enhanced RAG query for taskDescription='$taskDescription', contextId='${context.id}'"
        }

        // Get or detect technology stack information
        val techStack = context.projectContextInfo?.techStack ?: detectTechStack(context)
        val enhancedPrompt = buildTechStackAwarePrompt(taskDescription, techStack, context)

        // Generate technology-specific queries
        val queries = generateTechStackQueries(taskDescription, techStack)

        logger.debug { "ENHANCED_RAG_QUERY_TECH_STACK: Detected tech stack: $techStack" }
        logger.debug { "ENHANCED_RAG_QUERY_ENHANCED_PROMPT: $enhancedPrompt" }

        return executeWithEnhancedContext(enhancedPrompt, queries, context, plan)
    }

    private suspend fun detectTechStack(context: TaskContext): TechStackInfo {
        logger.debug { "ENHANCED_RAG_QUERY_DETECT_TECH_STACK: Detecting technology stack for project: ${context.projectDocument.name}" }

        // Default detection based on project context
        // In a real implementation, this would analyze build files, dependencies, etc.
        return TechStackInfo(
            framework = detectFramework(context),
            language = detectLanguage(context),
            version = detectVersion(context),
            securityFramework = detectSecurityFramework(context),
            databaseType = detectDatabaseType(context),
            buildTool = detectBuildTool(context),
        )
    }

    private fun detectFramework(context: TaskContext): String {
        val projectDescription = context.projectDocument.description?.lowercase() ?: ""
        return when {
            projectDescription.contains("spring boot") && projectDescription.contains("webflux") -> "Spring Boot WebFlux"
            projectDescription.contains("spring boot") -> "Spring Boot"
            projectDescription.contains("spring") -> "Spring Framework"
            else -> "Unknown Framework"
        }
    }

    private fun detectLanguage(context: TaskContext): String {
        val projectDescription = context.projectDocument.description?.lowercase() ?: ""
        return when {
            projectDescription.contains("kotlin") -> "Kotlin"
            projectDescription.contains("java") -> "Java"
            else -> "Kotlin" // Default assumption based on project structure
        }
    }

    private fun detectVersion(context: TaskContext): String? {
        // Would analyze pom.xml or build.gradle.kts in real implementation
        return null
    }

    private fun detectSecurityFramework(context: TaskContext): String? {
        val projectDescription = context.projectDocument.description?.lowercase() ?: ""
        return when {
            projectDescription.contains("spring security") -> "Spring Security"
            else -> "None"
        }
    }

    private fun detectDatabaseType(context: TaskContext): String? {
        val projectDescription = context.projectDocument.description?.lowercase() ?: ""
        return when {
            projectDescription.contains("mongodb") -> "MongoDB"
            projectDescription.contains("postgresql") -> "PostgreSQL"
            projectDescription.contains("mysql") -> "MySQL"
            else -> null
        }
    }

    private fun detectBuildTool(context: TaskContext): String {
        // Would check for pom.xml or build.gradle.kts in real implementation
        return "Maven" // Default assumption
    }

    private fun buildTechStackAwarePrompt(
        taskDescription: String,
        techStack: TechStackInfo?,
        context: TaskContext,
    ): String =
        buildString {
            append("ENHANCED RAG QUERY WITH TECHNOLOGY CONTEXT:\n\n")

            // Project context
            append("PROJECT CONTEXT:\n")
            append("- Client: ${context.clientDocument.name}\n")
            append("- Project: ${context.projectDocument.name}\n")
            if (context.projectDocument.description?.isNotBlank() == true) {
                append("- Description: ${context.projectDocument.description}\n")
            }
            append("\n")

            // Technology stack context
            if (techStack != null) {
                append("TECHNOLOGY STACK:\n")
                append("- Framework: ${techStack.framework}\n")
                append("- Language: ${techStack.language}\n")
                techStack.version?.let { append("- Version: $it\n") }
                techStack.securityFramework?.let { append("- Security: $it\n") }
                techStack.databaseType?.let { append("- Database: $it\n") }
                techStack.buildTool?.let { append("- Build Tool: $it\n") }
                append("\n")
            }

            // Context summary
            if (context.contextSummary.isNotBlank()) {
                append("CONTEXT SUMMARY:\n")
                append(context.contextSummary)
                append("\n")
            }

            append("USER QUERY:\n")
            append(taskDescription)
        }

    private fun generateTechStackQueries(
        taskDescription: String,
        techStack: TechStackInfo?,
    ): List<String> {
        val baseQueries = listOf(taskDescription)

        return when (techStack?.framework) {
            "Spring Boot WebFlux" -> baseQueries + springWebFluxSpecificQueries(taskDescription)
            "Spring Boot" -> baseQueries + springBootSpecificQueries(taskDescription)
            else -> baseQueries
        }
    }

    private fun springWebFluxSpecificQueries(taskDescription: String): List<String> =
        listOf(
            "$taskDescription Spring WebFlux reactive",
            "$taskDescription WebFlux Mono Flux reactive programming",
            "$taskDescription Spring Boot WebFlux controllers",
            "$taskDescription reactive repositories MongoDB WebFlux",
        )

    private fun springBootSpecificQueries(taskDescription: String): List<String> =
        listOf(
            "$taskDescription Spring Boot",
            "$taskDescription Spring Boot controllers services",
            "$taskDescription Spring Boot security configuration",
            "$taskDescription Spring Boot data repositories",
        )

    private suspend fun executeWithEnhancedContext(
        enhancedPrompt: String,
        queries: List<String>,
        context: TaskContext,
        plan: Plan,
    ): ToolResult {
        logger.debug { "ENHANCED_RAG_QUERY_EXECUTE: Executing with ${queries.size} queries" }

        return coroutineScope {
            val queryResults =
                queries
                    .map { query ->
                        async {
                            ragQueryTool.execute(context, plan, query)
                        }
                    }.awaitAll()

            // Aggregate results with enhanced context
            val aggregatedContent =
                buildString {
                    append("ENHANCED RAG QUERY RESULTS:\n\n")

                    queryResults.forEachIndexed { index, result ->
                        append("Query ${index + 1} Results:\n")
                        append(result.output)
                        append("\n\n")
                    }

                    append("TECHNOLOGY STACK CONTEXT APPLIED:\n")
                    append("Framework: ${context.projectContextInfo?.techStack?.framework ?: "Detected framework"}\n")
                    append("Language: ${context.projectContextInfo?.techStack?.language ?: "Kotlin"}\n")
                }

            ToolResult.ok(aggregatedContent)
        }
    }
}
