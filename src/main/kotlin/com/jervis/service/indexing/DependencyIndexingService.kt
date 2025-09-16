package com.jervis.service.indexing

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.model.ModelType
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagDocumentType
import com.jervis.domain.rag.RagSourceType
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.gateway.LlmGateway
import com.jervis.service.prompts.PromptRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.pathString

/**
 * Service for indexing dependency information from Joern analysis.
 * Extracts and processes dependency data for better searchability.
 */
@Service
class DependencyIndexingService(
    private val embeddingGateway: EmbeddingGateway,
    private val vectorStorage: VectorStorageRepository,
    private val llmGateway: LlmGateway,
    private val promptRepository: PromptRepository,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Result of dependency indexing operation
     */
    data class DependencyIndexingResult(
        val processedDependencies: Int,
        val skippedDependencies: Int,
        val errorDependencies: Int,
    )

    /**
     * Dependency information extracted from Joern
     */
    data class DependencyInfo(
        val name: String,
        val version: String? = null,
        val type: String,
        val scope: String? = null,
        val description: String? = null,
        val usageContext: List<String> = emptyList(),
        val securityIssues: List<String> = emptyList(),
    )

    /**
     * Index dependencies from Joern analysis results
     */
    suspend fun indexDependenciesFromJoern(
        project: ProjectDocument,
        projectPath: Path,
        joernDir: Path,
    ): DependencyIndexingResult =
        withContext(Dispatchers.Default) {
            try {
                logger.info { "Starting dependency indexing from Joern for project: ${project.name}" }

                val dependencies = extractDependenciesFromJoern(joernDir)
                logger.info { "Found ${dependencies.size} dependencies to index for project: ${project.name}" }

                var processedDependencies = 0
                var errorDependencies = 0

                for (dependency in dependencies) {
                    try {
                        val success = indexDependency(project, dependency)
                        if (success) {
                            processedDependencies++

                            // Also create LLM-enhanced description
                            indexDependencyDescription(project, dependency)
                        } else {
                            errorDependencies++
                        }
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to index dependency: ${dependency.name}" }
                        errorDependencies++
                    }
                }

                val result = DependencyIndexingResult(processedDependencies, 0, errorDependencies)
                logger.info {
                    "Dependency indexing completed for project: ${project.name} - " +
                        "Processed: $processedDependencies, Errors: $errorDependencies"
                }

                result
            } catch (e: Exception) {
                logger.error(e) { "Error during dependency indexing for project: ${project.name}" }
                DependencyIndexingResult(0, 0, 1)
            }
        }

    /**
     * Index a single dependency as DEPENDENCY document type
     */
    private suspend fun indexDependency(
        project: ProjectDocument,
        dependency: DependencyInfo,
    ): Boolean {
        try {
            val dependencySummary =
                buildString {
                    append("${dependency.name}")
                    dependency.version?.let { append(":$it") }
                    append(" (${dependency.type}")
                    dependency.scope?.let { append(",$it") }
                    append(")")
                    dependency.description?.let { append(" - $it") }

                    if (dependency.usageContext.isNotEmpty()) {
                        append(" | Usage: ${dependency.usageContext.joinToString(", ")}")
                    }

                    if (dependency.securityIssues.isNotEmpty()) {
                        append(" | Security: ${dependency.securityIssues.joinToString(", ")}")
                    }
                }

            val embedding = embeddingGateway.callEmbedding(ModelType.EMBEDDING_TEXT, dependencySummary)

            val ragDocument =
                RagDocument(
                    projectId = project.id!!,
                    documentType = RagDocumentType.DEPENDENCY,
                    ragSourceType = RagSourceType.ANALYSIS,
                    pageContent = dependencySummary,
                    source = "joern://${project.name}/dependencies/${dependency.name}",
                    path = "dependencies/${dependency.name}",
                    module = dependency.name,
                    language = dependency.type,
                )

            vectorStorage.store(ModelType.EMBEDDING_TEXT, ragDocument, embedding)
            return true
        } catch (e: Exception) {
            logger.error(e) { "Failed to index dependency: ${dependency.name}" }
            return false
        }
    }

    /**
     * Index LLM-enhanced dependency description as DEPENDENCY_DESCRIPTION document type
     */
    private suspend fun indexDependencyDescription(
        project: ProjectDocument,
        dependency: DependencyInfo,
    ) {
        try {
            logger.debug { "Generating LLM description for dependency: ${dependency.name}" }

            // Get the configured system prompt for DEPENDENCY_ANALYSIS
            promptRepository.getSystemPrompt(PromptTypeEnum.DEPENDENCY_ANALYSIS)

            val userPrompt =
                buildString {
                    appendLine("Analyze this dependency and provide a comprehensive description:")
                    appendLine("Name: ${dependency.name}")
                    dependency.version?.let { appendLine("Version: $it") }
                    appendLine("Type: ${dependency.type}")
                    dependency.scope?.let { appendLine("Scope: $it") }
                    dependency.description?.let { appendLine("Original Description: $it") }

                    if (dependency.usageContext.isNotEmpty()) {
                        appendLine("Usage Context in Project:")
                        dependency.usageContext.forEach { context ->
                            appendLine("- $context")
                        }
                    }

                    if (dependency.securityIssues.isNotEmpty()) {
                        appendLine("Identified Security Issues:")
                        dependency.securityIssues.forEach { issue ->
                            appendLine("- $issue")
                        }
                    }
                }

            val llmResponse =
                llmGateway.callLlm(
                    type = PromptTypeEnum.DEPENDENCY_ANALYSIS,
                    userPrompt = userPrompt,
                    quick = false,
                    "",
                )

            val enhancedDescription =
                buildString {
                    append("${dependency.name}: ")
                    append(llmResponse)
                }

            val embedding = embeddingGateway.callEmbedding(ModelType.EMBEDDING_TEXT, enhancedDescription)

            val ragDocument =
                RagDocument(
                    projectId = project.id,
                    documentType = RagDocumentType.DEPENDENCY_DESCRIPTION,
                    ragSourceType = RagSourceType.LLM,
                    pageContent = enhancedDescription,
                    source = "llm://${project.name}/dependencies/${dependency.name}/description",
                    path = "dependencies/${dependency.name}/description",
                    module = dependency.name,
                    language = "analysis-report",
                )

            vectorStorage.store(ModelType.EMBEDDING_TEXT, ragDocument, embedding)
            logger.debug { "Successfully indexed LLM description for dependency: ${dependency.name}" }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to generate LLM description for dependency: ${dependency.name}" }
        }
    }

    /**
     * Extract dependency information from Joern analysis files
     */
    private suspend fun extractDependenciesFromJoern(joernDir: Path): List<DependencyInfo> =
        withContext(Dispatchers.IO) {
            try {
                val dependencies = mutableListOf<DependencyInfo>()

                // Find dependency-related files in Joern output
                Files
                    .walk(joernDir)
                    .filter { it.isRegularFile() }
                    .filter { path ->
                        val fileName = path.fileName.toString().lowercase()
                        fileName.endsWith(".json") && (
                            fileName.contains("depend") ||
                                fileName.contains("import") ||
                                fileName.contains("package") ||
                                fileName.contains("cpg")
                        )
                    }.forEach { file ->
                        try {
                            val content = Files.readString(file)
                            val parsedDependencies = parseDependenciesFromJson(content, file.pathString)
                            dependencies.addAll(parsedDependencies)
                        } catch (e: Exception) {
                            logger.warn(e) { "Failed to parse dependencies from file: ${file.fileName}" }
                        }
                    }

                // Remove duplicates based on name and version
                dependencies.distinctBy { "${it.name}:${it.version ?: "unknown"}" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to extract dependencies from Joern directory" }
                emptyList()
            }
        }

    /**
     * Parse dependencies from JSON content
     */
    private fun parseDependenciesFromJson(
        jsonContent: String,
        fileName: String,
    ): List<DependencyInfo> =
        try {
            val json = Json.parseToJsonElement(jsonContent)
            val dependencies = mutableListOf<DependencyInfo>()

            when (json) {
                is JsonArray -> {
                    json.forEach { element ->
                        parseDependencyFromJsonElement(element, fileName)?.let { dependencies.add(it) }
                    }
                }

                is JsonObject -> {
                    // Look for dependency-like structures
                    json.entries.forEach { (key, value) ->
                        when {
                            key.contains("dependencies", ignoreCase = true) ||
                                key.contains("imports", ignoreCase = true) -> {
                                if (value is JsonArray) {
                                    value.forEach { element ->
                                        parseDependencyFromJsonElement(element, fileName)?.let { dependencies.add(it) }
                                    }
                                }
                            }

                            value is JsonObject -> {
                                parseDependencyFromJsonElement(value, fileName)?.let { dependencies.add(it) }
                            }
                        }
                    }
                }

                else -> {
                    // Try to parse as single dependency
                    parseDependencyFromJsonElement(json, fileName)?.let { dependencies.add(it) }
                }
            }

            dependencies
        } catch (e: Exception) {
            logger.debug(e) { "Failed to parse JSON dependencies from file: $fileName" }
            emptyList()
        }

    /**
     * Parse single dependency from JSON element
     */
    private fun parseDependencyFromJsonElement(
        element: JsonElement,
        fileName: String,
    ): DependencyInfo? {
        return try {
            if (element !is JsonObject) return null

            val name =
                element["name"]?.jsonPrimitive?.content
                    ?: element["package"]?.jsonPrimitive?.content
                    ?: element["module"]?.jsonPrimitive?.content
                    ?: element["library"]?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException("Cannot extract dependency name from JSON element in file: $fileName")

            val version =
                element["version"]?.jsonPrimitive?.content
                    ?: element["versionId"]?.jsonPrimitive?.content

            val type =
                element["type"]?.jsonPrimitive?.content
                    ?: element["kind"]?.jsonPrimitive?.content
                    ?: inferTypeFromName(name)

            val scope = element["scope"]?.jsonPrimitive?.content

            val description =
                element["description"]?.jsonPrimitive?.content
                    ?: element["summary"]?.jsonPrimitive?.content

            // Extract usage context
            val usageContext = mutableListOf<String>()
            element["usage"]?.jsonArray?.forEach { usage ->
                usage.jsonPrimitive.content.let { usageContext.add(it) }
            }
            element["methods"]?.jsonArray?.forEach { method ->
                method.jsonPrimitive.content.let { usageContext.add("Method: $it") }
            }

            // Extract security issues
            val securityIssues = mutableListOf<String>()
            element["vulnerabilities"]?.jsonArray?.forEach { vuln ->
                vuln.jsonPrimitive.content.let { securityIssues.add(it) }
            }
            element["security"]?.jsonArray?.forEach { security ->
                security.jsonPrimitive.content.let { securityIssues.add(it) }
            }

            DependencyInfo(
                name = name,
                version = version,
                type = type,
                scope = scope,
                description = description,
                usageContext = usageContext,
                securityIssues = securityIssues,
            )
        } catch (e: Exception) {
            logger.debug(e) { "Failed to parse dependency from JSON element in file: $fileName" }
            null
        }
    }

    /**
     * Infer dependency type from name
     */
    private fun inferTypeFromName(name: String): String =
        when {
            name.contains("org.springframework") -> "Spring Framework"
            name.contains("com.fasterxml.jackson") -> "JSON Processing"
            name.contains("junit") -> "Testing Framework"
            name.contains("org.slf4j") || name.contains("logback") -> "Logging"
            name.contains("kotlin") -> "Kotlin Library"
            name.contains("java") || name.contains("javax") -> "Java Library"
            name.contains("org.apache") -> "Apache Library"
            name.contains("com.google") -> "Google Library"
            name.endsWith(".jar") -> "JAR Library"
            else -> "Library"
        }
}
