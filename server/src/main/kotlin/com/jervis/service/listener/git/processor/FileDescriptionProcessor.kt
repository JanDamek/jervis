package com.jervis.service.listener.git.processor

import com.jervis.domain.model.ModelTypeEnum
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagSourceType
import com.jervis.domain.task.PendingTask
import com.jervis.service.rag.RagIndexingService
import com.jervis.service.rag.VectorStoreIndexService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Processes FILE_STRUCTURE_ANALYSIS task results and stores them in RAG.
 *
 * Purpose:
 * - Extract AI-generated file description from agent's analysis
 * - Create TEXT embedding of the description
 * - Store in Weaviate with FILE_DESCRIPTION sourceType
 * - Track in MongoDB for retrieval
 *
 * This completes the flow:
 * Task → Agent Analysis → THIS PROCESSOR → RAG Storage
 *
 * Agent can then find descriptions using knowledge_search:
 * knowledge_search(sourceType=FILE_DESCRIPTION, fileName="EmailService.kt")
 */
@Service
class FileDescriptionProcessor(
    private val ragIndexingService: RagIndexingService,
    private val vectorStoreIndexService: VectorStoreIndexService,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Process FILE_STRUCTURE_ANALYSIS task result.
     * Called AFTER agent finishes analysis.
     *
     * Extracts description from agent output and stores to RAG.
     */
    suspend fun processAnalysisResult(
        task: PendingTask,
        agentOutput: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                logger.info { "Processing file structure analysis result for task ${task.id}" }

                // Extract required fields from task context
                val projectId =
                    task.projectId ?: run {
                        logger.warn { "Task ${task.id} has no projectId, skipping" }
                        return@withContext false
                    }

                val clientId = task.clientId
                val filePath =
                    task.context["filePath"] ?: run {
                        logger.warn { "Task ${task.id} has no filePath in context" }
                        return@withContext false
                    }

                val commitHash =
                    task.context["commitHash"] ?: run {
                        logger.warn { "Task ${task.id} has no commitHash in context" }
                        return@withContext false
                    }

                // Extract metadata and description from agent output
                extractMetadataFromOutput(agentOutput)
                val description = extractDescriptionFromOutput(agentOutput)

                if (description.isBlank()) {
                    logger.warn { "No description found in agent output for task ${task.id}" }
                    return@withContext false
                }

                // Create RAG document
                val ragDocument =
                    RagDocument(
                        projectId = projectId,
                        clientId = clientId,
                        ragSourceType = RagSourceType.FILE_DESCRIPTION,
                        text = description,
                        fileName = filePath,
                        createdAt = Instant.now(),
                    )

                // Use RagIndexingService for embedding + storage
                val result =
                    ragIndexingService
                        .indexDocument(ragDocument, ModelTypeEnum.EMBEDDING_TEXT)
                        .getOrThrow()

                // Track in MongoDB for management
                val fileName = filePath.substringAfterLast("/")
                val branch = task.context["branch"] ?: "main"

                vectorStoreIndexService.trackIndexed(
                    projectId = projectId,
                    clientId = clientId,
                    branch = branch,
                    sourceType = RagSourceType.FILE_DESCRIPTION,
                    sourceId = "$filePath-description",
                    vectorStoreId = result.vectorStoreId,
                    vectorStoreName = "file-description-$fileName",
                    content = description,
                    filePath = filePath,
                    commitHash = commitHash,
                )

                logger.info {
                    "Stored file description for $filePath (commit ${commitHash.take(8)}) - vectorStoreId=${result.vectorStoreId}"
                }

                true
            } catch (e: Exception) {
                logger.error(e) { "Failed to process file structure analysis result for task ${task.id}" }
                false
            }
        }

    /**
     * Extract structured metadata from agent output.
     * Looks for patterns like:
     * CLASS: EmailService
     * PACKAGE: com.jervis.service
     * LANGUAGE: Kotlin
     */
    private fun extractMetadataFromOutput(output: String): Map<String, String> {
        val metadata = mutableMapOf<String, String>()

        // Extract CLASS
        val classPattern = Regex("""(?:CLASS|Class):\s*(.+)""", RegexOption.IGNORE_CASE)
        classPattern.find(output)?.let { match ->
            metadata["className"] = match.groups[1]?.value?.trim() ?: ""
        }

        // Extract PACKAGE
        val packagePattern = Regex("""(?:PACKAGE|Package):\s*(.+)""", RegexOption.IGNORE_CASE)
        packagePattern.find(output)?.let { match ->
            metadata["packageName"] = match.groups[1]?.value?.trim() ?: ""
        }

        // Extract LANGUAGE
        val languagePattern = Regex("""(?:LANGUAGE|Language):\s*(.+)""", RegexOption.IGNORE_CASE)
        languagePattern.find(output)?.let { match ->
            metadata["language"] = match.groups[1]?.value?.trim() ?: ""
        }

        return metadata
    }

    /**
     * Extract full description from agent output.
     * Looks for DESCRIPTION: section or falls back to full output.
     */
    private fun extractDescriptionFromOutput(output: String): String {
        // Try to extract DESCRIPTION section
        val descriptionPattern =
            Regex("""DESCRIPTION:\s*(.+)""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val match = descriptionPattern.find(output)

        return if (match != null) {
            match.groups[1]?.value?.trim() ?: ""
        } else {
            // Fallback: use entire output (remove metadata markers if present)
            output
                .replace(Regex("""(?:CLASS|Package|LANGUAGE):\s*.+"""), "")
                .trim()
        }
    }
}
