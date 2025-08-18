package com.jervis.service.dependency

import com.jervis.domain.dependency.Dependency
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagDocumentType
import com.jervis.domain.rag.RagSourceType
import com.jervis.service.llm.ModelRouterService
import com.jervis.service.vectordb.VectorStorageService
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * Service for managing project dependencies.
 * This service stores and retrieves dependencies using only the vector database.
 * Dependencies are stored as graph-like relationships in the vector store.
 */
@Service
class DependencyService(
    private val modelRouterService: ModelRouterService,
    private val embeddingService: com.jervis.service.indexer.EmbeddingService,
    private val vectorStorageService: VectorStorageService,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Store a single dependency in a vector database only.
     *
     * @param projectId The project ID
     * @param dependency The dependency to store
     * @return The unique ID of the stored dependency
     */
    suspend fun storeDependency(
        projectId: ObjectId,
        dependency: Dependency,
    ): String {
        val dependencyId = UUID.randomUUID().toString()

        // Create document for vector database
        val content = "Class ${dependency.sourceClass} depends on ${dependency.targetClass} (${dependency.type})"
        val metadata: Map<String, Any> =
            mapOf(
                "document_type" to RagDocumentType.DEPENDENCY.name,
                "source" to RagSourceType.ANALYSIS.name,
                "project" to projectId.toString(),
                "source_class" to dependency.sourceClass,
                "target_class" to dependency.targetClass,
                "dependency_type" to dependency.type.name,
                "source_file" to dependency.sourceFile,
                "dependency_id" to dependencyId,
                "timestamp" to Instant.now().toEpochMilli(),
            )

        val ragDocument =
            RagDocument(
                projectId = projectId,
                documentType = RagDocumentType.DEPENDENCY,
                ragSourceType = RagSourceType.ANALYSIS,
                pageContent = content,
                source = RagSourceType.ANALYSIS.name
            )

        // Generate embedding and store in a vector database
        val embedding = embeddingService.generateEmbeddingSuspend(content)
        vectorStorageService.storeDocumentSuspend(ragDocument, embedding)

        logger.debug { "Stored dependency: ${dependency.sourceClass} -> ${dependency.targetClass}" }

        return dependencyId
    }

    /**
     * Store dependency description in vector database only.
     *
     * @param projectId The project ID
     * @param description The dependency description
     * @param dependencies The list of dependencies
     * @return The unique ID of the stored description
     */
    suspend fun storeDependencyDescription(
        projectId: ObjectId,
        description: String,
        dependencies: List<Dependency>,
    ): String {
        val descriptionId = UUID.randomUUID().toString()

        // Create document for vector database
        val metadata =
            mapOf(
                "document_type" to RagDocumentType.DEPENDENCY_DESCRIPTION.name,
                "source" to RagSourceType.LLM.name,
                "project" to projectId.toString(),
                "dependency_count" to dependencies.size,
                "description_id" to descriptionId,
                "timestamp" to Instant.now().toEpochMilli(),
            )

        val ragDocument =
            RagDocument(
                projectId = projectId,
                documentType = RagDocumentType.DEPENDENCY_DESCRIPTION,
                ragSourceType = RagSourceType.LLM,
                pageContent = description,
                source = RagSourceType.LLM.name
            )

        // Generate embedding and store in a vector database
        val embedding = embeddingService.generateEmbeddingSuspend(description)
        vectorStorageService.storeDocumentSuspend(ragDocument, embedding)

        logger.info { "Stored dependency description for project: $projectId" }

        return descriptionId
    }

    /**
     * Store a dependency list in a vector database only.
     *
     * @param projectId The project ID
     * @param dependencies The list of dependencies
     * @param description The dependency description
     * @return The unique ID of the stored dependency list
     */
    suspend fun storeDependencyList(
        projectId: ObjectId,
        dependencies: List<Dependency>,
        description: String,
    ): String {
        val listId = UUID.randomUUID().toString()

        // Create content with a dependency list
        val content =
            buildString {
                append("Project Dependencies:\n\n")
                append(description)
                append("\n\nDependency List:\n")
                dependencies.forEachIndexed { index, dependency ->
                    append("${index + 1}. Class ${dependency.sourceClass} depends on ${dependency.targetClass} (${dependency.type.name})\n")
                }
            }

        val metadata =
            mapOf(
                "document_type" to RagDocumentType.DEPENDENCY_DESCRIPTION.name,
                "source" to RagSourceType.ANALYSIS.name,
                "project" to projectId.toString(),
                "dependency_count" to dependencies.size,
                "list_id" to listId,
                "timestamp" to Instant.now().toEpochMilli(),
            )

        val ragDocument =
            RagDocument(
                projectId = projectId,
                documentType = RagDocumentType.DEPENDENCY_DESCRIPTION,
                ragSourceType = RagSourceType.ANALYSIS,
                pageContent = content,
            )

        // Generate embedding and store in a vector database
        val embedding = embeddingService.generateEmbeddingSuspend(content)
        vectorStorageService.storeDocumentSuspend(ragDocument, embedding)

        logger.info { "Stored dependency list for project: $projectId" }

        return listId
    }

    /**
     * Generate a description of dependencies using the SIMPLE model.
     *
     * @param dependencies The list of dependencies to describe
     * @return The generated description
     */
    suspend fun generateDependencyDescription(dependencies: List<Dependency>): String {
        if (dependencies.isEmpty()) {
            return "No dependencies found in the project."
        }

        // Create a summary of dependencies
        val dependencySummary =
            buildString {
                append("Dependencies in the project:\n\n")
                dependencies.forEachIndexed { index, dependency ->
                    append("${index + 1}. Class ${dependency.sourceClass} depends on ${dependency.targetClass} (${dependency.type.name})\n")
                }
            }

        // Create a prompt for the LLM
        val prompt =
            """
            Analyze the following list of dependencies from a software project and identify patterns, key components, and architectural insights.
            Consider:
            1. Which classes are central to the architecture (have many dependencies)
            2. The overall structure of the codebase based on these dependencies
            3. Potential architectural patterns that might be in use

            Here's the list of dependencies:

            $dependencySummary

            Provide a concise analysis of the project's architecture based on these dependencies.
            """.trimIndent()

        // Use the SIMPLE model to generate the description
        try {
            val response = modelRouterService.processSimpleQuery(prompt)
            return response.answer
        } catch (e: Exception) {
            logger.error(e) { "Error generating dependency description: ${e.message}" }
            return "Failed to generate dependency description: ${e.message}"
        }
    }

    /**
     * Verify that all dependency data for a project has been properly stored in a vector database.
     *
     * @param projectId The project ID
     * @return True if all data is properly stored, false otherwise
     */
    suspend fun verifyDependencyStorage(projectId: ObjectId): Boolean {
        // Check if dependencies exist in a vector database
        val dependencyFilter =
            mapOf(
                "project" to projectId.toString(),
                "document_type" to RagDocumentType.DEPENDENCY.name,
            )
        val dependencyResults =
            vectorStorageService.searchSimilar(
                query = List(embeddingService.embeddingDimension) { 0f },
                limit = 1,
                filter = dependencyFilter,
            )
        if (dependencyResults.isEmpty()) {
            logger.warn { "No dependencies found in vector database for project: $projectId" }
            return false
        }

        // Check if dependency descriptions exist in a vector database
        val descriptionFilter =
            mapOf(
                "project" to projectId.toString(),
                "document_type" to RagDocumentType.DEPENDENCY_DESCRIPTION.name,
            )
        val descriptionResults =
            vectorStorageService.searchSimilar(
                query = List(embeddingService.embeddingDimension) { 0f },
                limit = 1,
                filter = descriptionFilter,
            )
        if (descriptionResults.isEmpty()) {
            logger.warn { "No dependency descriptions found in vector database for project: $projectId" }
            return false
        }

        logger.info { "All dependency data verified for project: $projectId" }
        return true
    }
}
