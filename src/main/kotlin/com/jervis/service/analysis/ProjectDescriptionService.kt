package com.jervis.service.analysis

import com.jervis.domain.model.ModelType
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.repository.mongo.ProjectMongoRepository
import com.jervis.service.gateway.LlmGateway
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
        val systemPrompt =
            """
            You are a senior software architect and technical writer. Create a concise, high-level description 
            of a software project that can be used as a quick overview for developers, managers, and stakeholders.
            
            The description should be:
            - Maximum 2-3 sentences (under 200 words)
            - Clear and professional
            - Focused on the main purpose and value proposition
            - Understandable by both technical and non-technical audiences
            - Suitable for use in scopes, summaries, and quick references
            
            Focus on:
            - What the project does (main functionality)
            - Who it serves (target users/systems)
            - Key technology stack or approach
            - Primary business value or purpose
            
            Avoid:
            - Technical implementation details
            - Specific code examples
            - Internal architecture specifics
            - Development history or processes
            """.trimIndent()

        val userPrompt =
            buildString {
                appendLine("Create a short description for this software project:")
                appendLine()
                appendLine("Project Name: ${project.name}")
                appendLine("Path: ${project.path}")
                appendLine("Languages: ${project.languages.joinToString(", ")}")

                project.description?.let {
                    appendLine("Current Description: $it")
                    appendLine()
                }

                if (indexingDescriptions.isNotEmpty()) {
                    appendLine("Analysis Summary from Project Indexing:")
                    indexingDescriptions.take(5).forEach { desc ->
                        val summary =
                            if (desc.length > 300) {
                                desc.take(300) + "..."
                            } else {
                                desc
                            }
                        appendLine("• $summary")
                    }
                    appendLine()
                }

                appendLine("Generate a concise, professional short description that captures the essence of this project.")
            }

        val llmResponse =
            llmGateway.callLlm(
                type = ModelType.INTERNAL,
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                outputLanguage = "en",
                quick = false,
            )

        return llmResponse.answer.trim()
    }

    /**
     * Generate comprehensive full description with detailed analysis
     */
    private suspend fun generateFullDescription(
        project: ProjectDocument,
        indexingDescriptions: List<String>,
        shortDescription: String,
    ): String {
        val systemPrompt =
            """
            You are a senior software architect and technical documentation expert. Create a comprehensive, 
            detailed description of a software project that provides deep insights for developers, 
            architects, and technical stakeholders.
            
            The description should be:
            - Comprehensive and detailed (1000-3000 words)
            - Well-structured with clear sections
            - Technical but accessible
            - Suitable for architectural reviews and development planning
            - Rich in actionable insights and recommendations
            
            Include these sections:
            1. **Project Overview** - Expand on the short description with context
            2. **Architecture & Design** - System architecture, design patterns, key components
            3. **Technology Stack** - Languages, frameworks, libraries, tools used
            4. **Core Functionality** - Main features, capabilities, and business logic
            5. **Code Quality & Structure** - Code organization, quality metrics, maintainability
            6. **Security & Performance** - Security measures, performance characteristics
            7. **Integration & Dependencies** - External systems, APIs, third-party libraries
            8. **Development & Deployment** - Build process, testing, deployment considerations
            9. **Recommendations** - Improvement suggestions, technical debt, next steps
            
            Base the analysis ONLY on the provided information. Never invent or assume details not present in the data.
            """.trimIndent()

        val userPrompt =
            buildString {
                appendLine("Create a comprehensive technical description for this software project:")
                appendLine()
                appendLine("Project Name: ${project.name}")
                appendLine("Path: ${project.path}")
                appendLine("Languages: ${project.languages.joinToString(", ")}")
                appendLine("Short Description: $shortDescription")
                appendLine()

                project.description?.let {
                    appendLine("Current Description: $it")
                    appendLine()
                }

                if (indexingDescriptions.isNotEmpty()) {
                    appendLine("Detailed Analysis from Project Indexing:")
                    appendLine("=".repeat(60))
                    indexingDescriptions.forEachIndexed { index, desc ->
                        appendLine("Analysis ${index + 1}:")
                        appendLine(desc)
                        appendLine()
                        appendLine("-".repeat(40))
                    }
                }

                appendLine()
                appendLine("Generate a comprehensive technical description with the sections outlined above.")
                appendLine("Focus on insights that would help developers understand and work with this project.")
            }

        val llmResponse =
            llmGateway.callLlm(
                type = ModelType.INTERNAL,
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                outputLanguage = "en",
                quick = false,
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
            appendLine(llmResponse.answer)
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
