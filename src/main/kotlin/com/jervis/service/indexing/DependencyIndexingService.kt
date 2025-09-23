package com.jervis.service.indexing

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.model.ModelType
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagDocumentType
import com.jervis.domain.rag.RagSourceType
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.prompts.PromptRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val indexingMonitorService: com.jervis.service.indexing.monitoring.IndexingMonitorService,
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
                indexingMonitorService.addStepLog(
                    project.id, "dependencies", 
                    "Found ${dependencies.size} dependencies to index from Joern analysis"
                )

                var processedDependencies = 0
                var errorDependencies = 0

                for ((index, dependency) in dependencies.withIndex()) {
                    try {
                        indexingMonitorService.addStepLog(
                            project.id, "dependencies", 
                            "Processing dependency (${index + 1}/${dependencies.size}): ${dependency.name}${dependency.version?.let { ":$it" } ?: ""}"
                        )
                        
                        val success = indexDependency(project, dependency)
                        if (success) {
                            processedDependencies++
                            indexingMonitorService.addStepLog(
                                project.id, "dependencies", 
                                "✓ Successfully indexed dependency: ${dependency.name} (${dependency.type})"
                            )

                            // Also create LLM-enhanced description
                            indexDependencyDescription(project, dependency)
                        } else {
                            errorDependencies++
                            indexingMonitorService.addStepLog(
                                project.id, "dependencies", 
                                "✗ Failed to index dependency: ${dependency.name}"
                            )
                        }
                    } catch (e: Exception) {
                        indexingMonitorService.addStepLog(
                            project.id, "dependencies", 
                            "✗ Error indexing dependency: ${dependency.name} - ${e.message}"
                        )
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
                    projectId = project.id,
                    clientId = project.clientId,
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
                    clientId = project.clientId,
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
                            val descriptions = extractDependencyDescriptionsFromText(content, file.pathString)
                            // Create basic dependency info from text descriptions for RAG indexing
                            descriptions.forEach { description ->
                                val dependencyInfo =
                                    DependencyInfo(
                                        name = extractNameFromDescription(description),
                                        version = null,
                                        type = "Analysis Output",
                                        scope = null,
                                        description = description,
                                        usageContext = listOf("Extracted from Joern analysis: ${file.fileName}"),
                                        securityIssues = emptyList(),
                                    )
                                dependencies.add(dependencyInfo)
                            }
                        } catch (e: Exception) {
                            logger.warn(e) { "Failed to extract dependency descriptions from file: ${file.fileName}" }
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
     * Extract textual dependency descriptions from Joern output for RAG indexing
     */
    private fun extractDependencyDescriptionsFromText(
        content: String,
        fileName: String,
    ): List<String> =
        try {
            val descriptions = mutableListOf<String>()
            val lines = content.lines()

            // Extract meaningful textual information from Joern output
            for (line in lines) {
                val trimmedLine = line.trim()

                // Skip empty lines and pure log lines
                if (trimmedLine.isEmpty() ||
                    trimmedLine.startsWith("[") ||
                    trimmedLine.startsWith("WARNING:") ||
                    trimmedLine.startsWith("executing") ||
                    trimmedLine.startsWith("--")
                ) {
                    continue
                }

                // Look for dependency-related information
                if (trimmedLine.contains("import", ignoreCase = true) ||
                    trimmedLine.contains("dependency", ignoreCase = true) ||
                    trimmedLine.contains("package", ignoreCase = true) ||
                    trimmedLine.contains("library", ignoreCase = true) ||
                    trimmedLine.contains("module", ignoreCase = true)
                ) {
                    descriptions.add(trimmedLine)
                }
            }

            descriptions
        } catch (e: Exception) {
            logger.debug(e) { "Failed to extract dependency descriptions from file: $fileName" }
            emptyList()
        }

    /**
     * Extract a simple name from description for dependency identification
     */
    private fun extractNameFromDescription(description: String): String {
        // Try to extract a meaningful name from the description
        val words = description.split("\\s+".toRegex())

        // Look for package-like names (containing dots)
        for (word in words) {
            if (word.contains(".") && !word.startsWith(".") && !word.endsWith(".")) {
                return word
            }
        }

        // Fallback to first meaningful word
        return words.firstOrNull { it.length > 2 && it.matches("[a-zA-Z].*".toRegex()) }
            ?: "unknown-dependency"
    }
}
