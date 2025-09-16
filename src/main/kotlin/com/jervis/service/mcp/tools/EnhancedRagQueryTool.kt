package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.context.TechStackInfo
import com.jervis.domain.plan.Plan
import com.jervis.service.gateway.LlmGateway
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class EnhancedRagQueryTool(
    private val ragQueryTool: RagQueryTool,
    private val llmGateway: LlmGateway,
    private val promptRepository: PromptRepository,
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
        val filters: Map<String, List<String>>? = null,
        val techStackContext: String? = null,
    )

    @Serializable
    data class EnhancedRagQueryParams(
        val queries: List<EnhancedRagQueryRequest>,
        val topK: Int = -1,
        val minScore: Float = 0.8f,
        val finalPrompt: String? = null,
        val globalFilters: Map<String, List<String>>? = null,
        val techStackFiltering: Boolean = true,
    )

    private suspend fun parseTaskDescription(
        taskDescription: String,
        context: TaskContext,
    ): EnhancedRagQueryParams {
        val userPrompt = promptRepository.getMcpToolUserPrompt(PromptTypeEnum.RAG_QUERY)
        val llmResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.RAG_QUERY,
                userPrompt = userPrompt.replace("{userPrompt}", taskDescription),
                outputLanguage = "en",
                quick = context.quick,
                mappingValue = emptyMap(),
                exampleInstance = EnhancedRagQueryParams(emptyList()),
            )

        return llmResponse
    }

    override suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
    ): ToolResult {
        logger.debug {
            "ENHANCED_RAG_QUERY_START: Executing enhanced RAG query for taskDescription='$taskDescription', contextId='${context.id}', planId='${plan.id}'"
        }

        val queryParams =
            runCatching {
                parseTaskDescription(taskDescription, context)
            }.getOrElse {
                logger.error { "ENHANCED_RAG_QUERY_PARSE_ERROR: Failed to parse task description: ${it.message}" }
                return ToolResult.error("Invalid enhanced RAG query parameters: ${it.message}")
            }

        logger.debug { "ENHANCED_RAG_QUERY_PARSED: queryParams=$queryParams" }

        if (queryParams.queries.isEmpty()) {
            logger.warn { "ENHANCED_RAG_QUERY_NO_QUERIES: No query parameters provided for taskDescription='$taskDescription'" }
            return ToolResult.error("No query parameters provided")
        }

        // Get or detect technology stack information
        val techStack = context.projectContextInfo?.techStack ?: detectTechStack(context)
        logger.debug { "ENHANCED_RAG_QUERY_TECH_STACK: Detected tech stack: $techStack" }

        // Convert EnhancedRagQueryParams to RagQueryTool format and execute
        return executeWithEnhancedContext(queryParams, techStack, context, plan)
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

    private suspend fun executeWithEnhancedContext(
        queryParams: EnhancedRagQueryParams,
        techStack: TechStackInfo,
        context: TaskContext,
        plan: Plan,
    ): ToolResult {
        logger.debug { "ENHANCED_RAG_QUERY_EXECUTE: Executing with enhanced context and tech stack: $techStack" }

        return coroutineScope {
            // Convert EnhancedRagQueryParams to RagQueryTool format
            val enhancedQueries =
                queryParams.queries
                    .map { enhancedQuery ->
                        val techStackAwareQuery = buildTechStackAwareQuery(enhancedQuery.query, techStack, context)

                        // Build enhanced filters that include tech stack context
                        val enhancedFilters =
                            buildMap<String, List<String>> {
                                // Add original filters
                                enhancedQuery.filters?.let { putAll(it) }

                                // Add tech stack filters if enabled
                                if (queryParams.techStackFiltering) {
                                    put("framework", listOf(techStack.framework))
                                    put("language", listOf(techStack.language))
                                    techStack.version?.let { put("version", listOf(it)) }
                                    techStack.securityFramework?.let { put("securityFramework", listOf(it)) }
                                    techStack.databaseType?.let { put("databaseType", listOf(it)) }
                                }
                            }

                        "query: $techStackAwareQuery\nembedding: ${enhancedQuery.embedding}\ntopK: ${enhancedQuery.topK ?: queryParams.topK}\nminScore: ${enhancedQuery.minScore ?: queryParams.minScore}\nfilters: $enhancedFilters"
                    }.joinToString("\n\n---\n\n")

            // Execute with RagQueryTool
            val queryResults = ragQueryTool.execute(context, plan, enhancedQueries)

            // Enhance results with technology stack context
            val aggregatedContent =
                buildString {
                    append("ENHANCED RAG QUERY RESULTS:\n\n")
                    append("TECHNOLOGY STACK CONTEXT:\n")
                    append("- Framework: ${techStack.framework}\n")
                    append("- Language: ${techStack.language}\n")
                    techStack.version?.let { append("- Version: $it\n") }
                    techStack.securityFramework?.let { append("- Security: $it\n") }
                    techStack.databaseType?.let { append("- Database: $it\n") }
                    append("\n")
                    append(queryResults.output)
                }

            ToolResult.ok(aggregatedContent)
        }
    }

    private fun buildTechStackAwareQuery(
        originalQuery: String,
        techStack: TechStackInfo,
        context: TaskContext,
    ): String =
        buildString {
            append("ENHANCED QUERY WITH TECHNOLOGY CONTEXT:\n\n")

            // Project context
            append("PROJECT: ${context.projectDocument.name}\n")
            if (context.projectDocument.description?.isNotBlank() == true) {
                append("DESCRIPTION: ${context.projectDocument.description}\n")
            }

            // Technology stack context
            append("FRAMEWORK: ${techStack.framework}\n")
            append("LANGUAGE: ${techStack.language}\n")
            techStack.version?.let { append("VERSION: $it\n") }
            techStack.securityFramework?.let { append("SECURITY: $it\n") }
            techStack.databaseType?.let { append("DATABASE: $it\n") }
            append("\n")

            append("USER QUERY: $originalQuery")
        }
}
