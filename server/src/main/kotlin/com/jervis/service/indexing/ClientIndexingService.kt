package com.jervis.service.indexing

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.entity.mongo.ClientDocument
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.repository.mongo.ClientMongoRepository
import com.jervis.repository.mongo.ProjectMongoRepository
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.indexing.dto.ClientFullDescriptionResponse
import com.jervis.service.indexing.dto.ClientShortDescriptionResponse
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
    }

    /**
     * Generate short client description for high-level overview
     */
    private suspend fun generateClientShortDescription(
        client: ClientDocument,
        projects: List<ProjectDocument>,
        projectDescriptions: List<String>,
    ): String {
        val allLanguages = projects.flatMap { it.languages }.distinct()
        val projectSummaries =
            projectDescriptions.take(5).map { desc ->
                if (desc.length > 300) desc.take(300) + "..." else desc
            }

        return try {
            val llmResponse =
                llmGateway.callLlm(
                    type = PromptTypeEnum.CLIENT_DESCRIPTION_SHORT,
                    responseSchema = ClientShortDescriptionResponse(),
                    quick = false,
                    mappingValue =
                        mapOf(
                            "clientName" to client.name,
                            "clientDescription" to (client.description ?: ""),
                            "projectCount" to projects.size.toString(),
                            "projectSummaries" to projectSummaries.joinToString(", ", "• "),
                            "technologies" to allLanguages.joinToString(", "),
                        ),
                )
            llmResponse.result.shortDescription.trim()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to generate short description using LLM for client ${client.name}, using fallback" }
            "${client.name} manages ${projects.size} project${if (projects.size != 1) "s" else ""} using technologies: ${
                allLanguages.take(
                    5,
                ).joinToString(", ")
            }${if (allLanguages.size > 5) ", and others" else ""}"
        }
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
        val allLanguages = projects.flatMap { it.languages }.distinct()
        val projectPaths = projects.map { it.path }.filter { it.isNotBlank() }
        val portfolioAnalysis =
            projectDescriptions
                .mapIndexed { index, desc ->
                    "Project ${index + 1}:\n$desc"
                }.joinToString("\n\n" + "-".repeat(40) + "\n\n")

        val llmGeneratedDescription =
            try {
                val llmResponse =
                    llmGateway.callLlm(
                        type = PromptTypeEnum.CLIENT_DESCRIPTION_FULL,
                        responseSchema = ClientFullDescriptionResponse(),
                        quick = false,
                        mappingValue =
                            mapOf(
                                "clientName" to client.name,
                                "clientDescription" to (client.description ?: ""),
                                "shortDescription" to shortDescription,
                                "totalProjects" to projects.size.toString(),
                                "portfolioAnalysis" to portfolioAnalysis,
                                "programmingLanguages" to allLanguages.joinToString(", "),
                                "activeProjects" to projects.count { !it.isDisabled }.toString(),
                                "managedRepositories" to projectPaths.size.toString(),
                            ),
                    )
                llmResponse.result.fullDescription
            } catch (e: Exception) {
                logger.warn(e) { "Failed to generate full description using LLM for client ${client.name}, using fallback" }
                buildString {
                    appendLine("## Overview")
                    appendLine(shortDescription)
                    appendLine()
                    appendLine("## Projects")
                    appendLine(
                        "This client manages ${projects.size} project(s), with ${projects.count { !it.isDisabled }} currently active.",
                    )
                    appendLine()
                    if (projectDescriptions.isNotEmpty()) {
                        appendLine("## Project Portfolio")
                        projectDescriptions.take(10).forEachIndexed { index, desc ->
                            appendLine("### ${index + 1}. ${desc.substringBefore(":**").removePrefix("**")}")
                            appendLine(desc.substringAfter(":** ").take(200))
                        appendLine()
                    }
                }
            }
        }

        return buildString {
            appendLine("# ${client.name} - Client Organization Analysis")
            appendLine()
            appendLine("**Total Projects:** ${projects.size}")
            appendLine("**Active Projects:** ${projects.count { !it.isDisabled }}")
            appendLine("**Technology Stack:** ${allLanguages.joinToString(", ")}")
            appendLine("**Generated:** ${java.time.Instant.now()}")
            appendLine()
            appendLine("---")
            appendLine()
            appendLine(llmGeneratedDescription)
            appendLine()
            appendLine("---")
            appendLine(
                "*This description was generated by comprehensive analysis of all client projects including code indexing, architectural analysis, and business domain assessment.*",
            )
        }
    }
}
