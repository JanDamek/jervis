package com.jervis.service.analysis

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.repository.mongo.ProjectMongoRepository
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.gateway.processing.LlmResponseWrapper
import com.jervis.service.prompts.PromptRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Service for generating comprehensive project descriptions using LLM analysis.
 * This meets the requirement: "Na konci aplikace se provede přes LLM sumarizace celého projektu a ten se vloží do Description projektu"
 * "přidej do projektu description short, full a co ještě uznáš za vhodné"
 */
@Service
class ProjectDescriptionService(
    private val llmGateway: LlmGateway,
    private val projectRepository: ProjectMongoRepository,
    private val promptRepository: PromptRepository,
) {
    private val logger = KotlinLogging.logger {}

    data class ProjectDescriptionResult(
        val shortDescription: String,
        val fullDescription: String,
    )

    /**
     * Generate comprehensive project descriptions and update the project document
     */
    suspend fun generateAndUpdateProjectDescriptions(
        project: ProjectDocument,
        indexingDescriptions: List<String> = emptyList(),
    ): ProjectDescriptionResult =
        withContext(Dispatchers.Default) {
            try {
                logger.info { "Generating project descriptions for project: ${project.name}" }

                val descriptions = generateProjectDescriptions(project, indexingDescriptions)

                // Update project document with new descriptions
                val updatedProject =
                    project.copy(
                        shortDescription = descriptions.shortDescription,
                        fullDescription = descriptions.fullDescription,
                    )

                projectRepository.save(updatedProject)
                logger.info { "Successfully updated project descriptions for: ${project.name}" }

                descriptions
            } catch (e: Exception) {
                logger.error(e) { "Failed to generate project descriptions for: ${project.name}" }
                ProjectDescriptionResult("", "")
            }
        }

    /**
     * Generate comprehensive project descriptions using LLM analysis
     */
    suspend fun generateProjectDescriptions(
        project: ProjectDocument,
        indexingDescriptions: List<String> = emptyList(),
    ): ProjectDescriptionResult {
        try {
            logger.debug { "Analyzing project for description generation: ${project.name}" }

            // Generate short description
            val shortDescription = generateShortDescription(project, indexingDescriptions)

            // Generate full description
            val fullDescription = generateFullDescription(project, indexingDescriptions, shortDescription)

            return ProjectDescriptionResult(shortDescription, fullDescription)
        } catch (e: Exception) {
            logger.error(e) { "Failed to generate descriptions for project: ${project.name}" }
            return ProjectDescriptionResult("", "")
        }
    }

    /**
     * Generate short description for high-level project overview
     */
    private suspend fun generateShortDescription(
        project: ProjectDocument,
        indexingDescriptions: List<String>,
    ): String {
        val currentDescription = project.description?.let { "Current Description: $it\n" } ?: ""
        
        val indexingSummary = if (indexingDescriptions.isNotEmpty()) {
            buildString {
                appendLine("Analysis Summary from Project Indexing:")
                indexingDescriptions.take(5).forEach { desc ->
                    val summary = if (desc.length > 300) {
                        desc.take(300) + "..."
                    } else {
                        desc
                    }
                    appendLine("• $summary")
                }
            }
        } else {
            ""
        }

        val mappingValues = mapOf(
            "projectName" to project.name,
            "projectPath" to project.path,
            "languages" to project.languages.joinToString(", "),
            "currentDescription" to currentDescription,
            "indexingSummary" to indexingSummary
        )

        val llmResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.PROJECT_DESCRIPTION_SHORT,
                userPrompt = "",
                quick = false,
                LlmResponseWrapper(),
                mappingValue = mappingValues
            )

        return llmResponse.response.trim()
    }

    /**
     * Generate comprehensive full description with detailed analysis
     */
    private suspend fun generateFullDescription(
        project: ProjectDocument,
        indexingDescriptions: List<String>,
        shortDescription: String,
    ): String {
        val currentDescription = project.description?.let { "Current Description: $it\n" } ?: ""
        
        val detailedIndexingAnalysis = if (indexingDescriptions.isNotEmpty()) {
            buildString {
                appendLine("Detailed Analysis from Project Indexing:")
                appendLine("=".repeat(60))
                indexingDescriptions.forEachIndexed { index, desc ->
                    appendLine("Analysis ${index + 1}:")
                    appendLine(desc)
                    appendLine()
                    appendLine("-".repeat(40))
                }
            }
        } else {
            ""
        }

        val mappingValues = mapOf(
            "projectName" to project.name,
            "projectPath" to project.path,
            "languages" to project.languages.joinToString(", "),
            "shortDescription" to shortDescription,
            "currentDescription" to currentDescription,
            "detailedIndexingAnalysis" to detailedIndexingAnalysis
        )

        val llmResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.PROJECT_DESCRIPTION_FULL,
                userPrompt = "",
                quick = false,
                LlmResponseWrapper(),
                mappingValue = mappingValues
            )

        return buildString {
            appendLine("# ${project.name} - Technical Analysis")
            appendLine()
            appendLine("**Project Path:** ${project.path}")
            appendLine("**Languages:** ${project.languages.joinToString(", ")}")
            appendLine("**Generated:** ${java.time.Instant.now()}")
            appendLine()
            appendLine("---")
            appendLine()
            appendLine(llmResponse.response)
            appendLine()
            appendLine("---")
            appendLine(
                "*This description was generated by comprehensive project analysis including code indexing, Joern static analysis, dependency analysis, and architectural review.*",
            )
        }
    }

    /**
     * Collect important descriptions from various indexing services
     * This should be called during the indexing process to gather all relevant descriptions
     */
    fun collectIndexingDescriptions(
        joernAnalysisResults: List<String> = emptyList(),
        classAnalysisResults: List<String> = emptyList(),
        dependencyAnalysisResults: List<String> = emptyList(),
        fileAnalysisResults: List<String> = emptyList(),
        architectureAnalysisResults: List<String> = emptyList(),
    ): List<String> {
        val descriptions = mutableListOf<String>()

        // Add Joern analysis insights
        if (joernAnalysisResults.isNotEmpty()) {
            val joernSummary =
                buildString {
                    appendLine("JOERN STATIC ANALYSIS INSIGHTS:")
                    joernAnalysisResults.take(3).forEach { result ->
                        val summary =
                            if (result.length > 500) {
                                result.take(500) + "... (analysis continues)"
                            } else {
                                result
                            }
                        appendLine("• $summary")
                    }
                }
            descriptions.add(joernSummary)
        }

        // Add class analysis insights
        if (classAnalysisResults.isNotEmpty()) {
            val classSummary =
                buildString {
                    appendLine("CLASS AND METHOD ANALYSIS INSIGHTS:")
                    classAnalysisResults.take(5).forEach { result ->
                        val summary =
                            if (result.length > 400) {
                                result.take(400) + "..."
                            } else {
                                result
                            }
                        appendLine("• $summary")
                    }
                }
            descriptions.add(classSummary)
        }

        // Add dependency analysis insights
        if (dependencyAnalysisResults.isNotEmpty()) {
            val depSummary =
                buildString {
                    appendLine("DEPENDENCY ANALYSIS INSIGHTS:")
                    dependencyAnalysisResults.take(3).forEach { result ->
                        val summary =
                            if (result.length > 400) {
                                result.take(400) + "..."
                            } else {
                                result
                            }
                        appendLine("• $summary")
                    }
                }
            descriptions.add(depSummary)
        }

        // Add file analysis insights
        if (fileAnalysisResults.isNotEmpty()) {
            val fileSummary =
                buildString {
                    appendLine("FILE STRUCTURE AND CODE ANALYSIS INSIGHTS:")
                    appendLine("Total analyzed files: ${fileAnalysisResults.size}")
                    fileAnalysisResults.take(3).forEach { result ->
                        val summary =
                            if (result.length > 300) {
                                result.take(300) + "..."
                            } else {
                                result
                            }
                        appendLine("• $summary")
                    }
                }
            descriptions.add(fileSummary)
        }

        // Add architecture analysis insights
        if (architectureAnalysisResults.isNotEmpty()) {
            val archSummary =
                buildString {
                    appendLine("ARCHITECTURE ANALYSIS INSIGHTS:")
                    architectureAnalysisResults.take(2).forEach { result ->
                        val summary =
                            if (result.length > 500) {
                                result.take(500) + "..."
                            } else {
                                result
                            }
                        appendLine("• $summary")
                    }
                }
            descriptions.add(archSummary)
        }

        return descriptions
    }
}
