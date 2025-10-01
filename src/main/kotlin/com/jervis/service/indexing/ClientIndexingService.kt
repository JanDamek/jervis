package com.jervis.service.indexing

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.entity.mongo.ClientDocument
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.repository.mongo.ClientMongoRepository
import com.jervis.repository.mongo.ProjectMongoRepository
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.gateway.processing.dto.LlmResponseWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * Service for managing client-level indexing and description aggregation.
 * Combines project descriptions across all client projects to create comprehensive client descriptions.
 *
 * This addresses the requirement: "celkový popis i short bude také u klienta, ale tam ten celkový popis
 * bude tvořen ze všech popisku jednotlivých projektů, to znamená, že pokud se jeden projekt naidexuje,
 * tak se pak vemou všechny description ze všech projektu klienta a z toho se udělá short a full description"
 */
@Service
class ClientIndexingService(
    private val clientRepository: ClientMongoRepository,
    private val projectRepository: ProjectMongoRepository,
    private val llmGateway: LlmGateway,
) {
    private val logger = KotlinLogging.logger {}

    data class ClientDescriptionResult(
        val shortDescription: String,
        val fullDescription: String,
        val projectCount: Int,
    )

    /**
     * Update client descriptions by aggregating all project descriptions
     * This should be called whenever any project for the client is reindexed
     */
    suspend fun updateClientDescriptions(clientId: ObjectId): ClientDescriptionResult =
        withContext(Dispatchers.Default) {
            try {
                logger.info { "Updating client descriptions for client: $clientId" }

                val client = clientRepository.findById(clientId)
                if (client == null) {
                    logger.warn { "Client not found: $clientId" }
                    return@withContext ClientDescriptionResult("", "", 0)
                }

                // Get all projects for this client
                val projects = projectRepository.findByClientId(clientId)
                if (projects.isEmpty()) {
                    logger.info { "No projects found for client: ${client.name}" }
                    return@withContext ClientDescriptionResult("", "", 0)
                }

                logger.info { "Found ${projects.size} projects for client: ${client.name}" }

                // Generate client descriptions based on project descriptions
                val clientDescriptions = generateClientDescriptions(client, projects)

                // Update the client document with new descriptions
                val updatedClient =
                    client.copy(
                        shortDescription = clientDescriptions.shortDescription,
                        fullDescription = clientDescriptions.fullDescription,
                    )

                clientRepository.save(updatedClient)
                logger.info { "Successfully updated client descriptions for: ${client.name}" }

                ClientDescriptionResult(
                    clientDescriptions.shortDescription,
                    clientDescriptions.fullDescription,
                    projects.size,
                )
            } catch (e: Exception) {
                logger.error(e) { "Failed to update client descriptions for client: $clientId" }
                ClientDescriptionResult("", "", 0)
            }
        }

    /**
     * Generate comprehensive client descriptions by analyzing all project descriptions
     */
    private suspend fun generateClientDescriptions(
        client: ClientDocument,
        projects: List<ProjectDocument>,
    ): ClientDescriptionResult {
        try {
            logger.debug { "Generating client descriptions for: ${client.name} with ${projects.size} projects" }

            // Collect all project descriptions
            val projectDescriptions =
                projects.map { project ->
                    when {
                        !project.fullDescription.isNullOrBlank() -> {
                            "**${project.name}**: ${project.fullDescription}"
                        }

                        !project.shortDescription.isNullOrBlank() -> {
                            "**${project.name}**: ${project.shortDescription}"
                        }

                        !project.description.isNullOrBlank() -> {
                            "**${project.name}**: ${project.description}"
                        }

                        else -> {
                            "**${project.name}**: ${project.languages.joinToString(", ")} project"
                        }
                    }
                }

            // Generate short description
            val shortDescription = generateClientShortDescription(client, projects, projectDescriptions)

            // Generate full description
            val fullDescription = generateClientFullDescription(client, projects, projectDescriptions, shortDescription)

            return ClientDescriptionResult(shortDescription, fullDescription, projects.size)
        } catch (e: Exception) {
            logger.error(e) { "Failed to generate client descriptions for: ${client.name}" }
            return ClientDescriptionResult("", "", 0)
        }
    }

    /**
     * Generate short client description for high-level overview
     */
    private suspend fun generateClientShortDescription(
        client: ClientDocument,
        projects: List<ProjectDocument>,
        projectDescriptions: List<String>,
    ): String {
        val userPrompt =
            buildString {
                appendLine("Create a short description for this client organization:")
                appendLine()
                appendLine("Client Name: ${client.name}")
                if (!client.description.isNullOrBlank()) {
                    appendLine("Client Description: ${client.description}")
                }
                appendLine("Number of Projects: ${projects.size}")
                appendLine()
                appendLine("Project Portfolio:")
                projectDescriptions.take(5).forEach { desc ->
                    val summary =
                        if (desc.length > 300) {
                            desc.take(300) + "..."
                        } else {
                            desc
                        }
                    appendLine("• $summary")
                }

                if (projects.size > 5) {
                    appendLine("... and ${projects.size - 5} more projects")
                }

                appendLine()
                appendLine("Technology Overview:")
                val allLanguages = projects.flatMap { it.languages }.distinct()
                appendLine("• Languages: ${allLanguages.joinToString(", ")}")

                appendLine()
                appendLine("Generate a concise, professional short description that captures this client's business and technical profile.")
            }

        val llmResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.CLIENT_DESCRIPTION_SHORT,
                responseSchema = LlmResponseWrapper(),
                quick = false,
                mappingValue = mapOf("clientAnalysisData" to userPrompt),
            )

        return llmResponse.response.trim()
    }

    /**
     * Generate comprehensive full client description
     */
    private suspend fun generateClientFullDescription(
        client: ClientDocument,
        projects: List<ProjectDocument>,
        projectDescriptions: List<String>,
        shortDescription: String,
    ): String {
        val userPrompt =
            buildString {
                appendLine("Create a comprehensive business and technical description for this client organization:")
                appendLine()
                appendLine("Client Name: ${client.name}")
                if (!client.description.isNullOrBlank()) {
                    appendLine("Client Description: ${client.description}")
                }
                appendLine("Short Description: $shortDescription")
                appendLine("Total Projects: ${projects.size}")
                appendLine()

                appendLine("Complete Project Portfolio Analysis:")
                appendLine("=".repeat(60))
                projectDescriptions.forEachIndexed { index, desc ->
                    appendLine("Project ${index + 1}:")
                    appendLine(desc)
                    appendLine()
                    appendLine("-".repeat(40))
                }

                appendLine()
                appendLine("Technical Profile:")
                val allLanguages = projects.flatMap { it.languages }.distinct()
                appendLine("• Programming Languages: ${allLanguages.joinToString(", ")}")
                appendLine("• Active Projects: ${projects.count { !it.isDisabled }}")
                appendLine("• Total Codebase Projects: ${projects.size}")

                val projectPaths = projects.map { it.path }.filter { it.isNotBlank() }
                appendLine("• Managed Repositories: ${projectPaths.size}")

                appendLine()
                appendLine("Generate a comprehensive client organization description with the sections outlined above.")
                appendLine("Focus on business insights and technical capabilities that would be valuable for strategic planning.")
            }

        val llmResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.CLIENT_DESCRIPTION_FULL,
                responseSchema = LlmResponseWrapper(),
                quick = false,
                mappingValue = mapOf("clientAnalysisData" to userPrompt),
            )

        return buildString {
            appendLine("# ${client.name} - Client Organization Analysis")
            appendLine()
            appendLine("**Total Projects:** ${projects.size}")
            appendLine("**Active Projects:** ${projects.count { !it.isDisabled }}")
            appendLine("**Technology Stack:** ${projects.flatMap { it.languages }.distinct().joinToString(", ")}")
            appendLine("**Generated:** ${java.time.Instant.now()}")
            appendLine()
            appendLine("---")
            appendLine()
            appendLine(llmResponse.response)
            appendLine()
            appendLine("---")
            appendLine(
                "*This description was generated by comprehensive analysis of all client projects including code indexing, architectural analysis, and business domain assessment.*",
            )
        }
    }
}
