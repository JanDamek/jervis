package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.McpToolType
import com.jervis.domain.context.CodingGuidelines
import com.jervis.domain.context.Guidelines
import com.jervis.domain.context.ProgrammingStyle
import com.jervis.domain.context.ProjectContextInfo
import com.jervis.domain.context.TaskContext
import com.jervis.domain.context.TechStackInfo
import com.jervis.domain.plan.Plan
import com.jervis.service.client.ClientService
import com.jervis.service.mcp.ContextAwareMcpTool
import com.jervis.service.mcp.buildStandardContextAwarePrompt
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.project.ProjectService
import com.jervis.service.prompts.PromptRepository
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class ScopeResolutionTool(
    private val clientService: ClientService,
    private val projectService: ProjectService,
    private val promptRepository: PromptRepository,
) : ContextAwareMcpTool {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val name: String = "scope.resolve"
    override val description: String
        get() = promptRepository.getMcpToolDescription(McpToolType.SCOPE_RESOLUTION)

    override suspend fun executeWithProjectContext(
        taskDescription: String,
        context: TaskContext,
        plan: Plan,
    ): ToolResult {
        logger.debug { "SCOPE_RESOLUTION_ENHANCED: Executing enhanced scope resolution for context=${context.id}" }

        val client = context.clientDocument
        val project = context.projectDocument

        // Detect or use existing technology stack
        val techStack = context.projectContextInfo?.techStack ?: detectTechnologyStack(context)

        // Create or enhance project context info
        val projectContextInfo = context.projectContextInfo ?: createProjectContextInfo(context, techStack)

        // Update context with detected information
        context.projectContextInfo = projectContextInfo

        val summary = buildEnhancedSummary(client, project, techStack, projectContextInfo)

        logger.debug { "SCOPE_RESOLUTION_TECH_STACK: Detected technology stack: $techStack" }

        return ToolResult.ok(summary)
    }

    override fun buildContextAwarePrompt(
        basePrompt: String,
        projectInfo: ProjectContextInfo?,
    ): String = buildStandardContextAwarePrompt(basePrompt, projectInfo)

    private suspend fun detectTechnologyStack(context: TaskContext): TechStackInfo {
        logger.debug { "SCOPE_RESOLUTION_DETECT_TECH: Detecting technology stack for project: ${context.projectDocument.name}" }

        val projectDescription = context.projectDocument.description?.lowercase() ?: ""
        val clientDescription = context.clientDocument.description?.lowercase() ?: ""
        val combinedContext = "$projectDescription $clientDescription"

        return TechStackInfo(
            framework = detectFramework(combinedContext),
            language = detectLanguage(combinedContext),
            version = detectVersion(combinedContext),
            securityFramework = detectSecurityFramework(combinedContext),
            databaseType = detectDatabaseType(combinedContext),
            buildTool = detectBuildTool(combinedContext),
        )
    }

    private fun detectFramework(context: String): String =
        when {
            context.contains("spring boot") && context.contains("webflux") -> "Spring Boot WebFlux"
            context.contains("spring boot") && context.contains("reactive") -> "Spring Boot WebFlux"
            context.contains("spring boot") -> "Spring Boot"
            context.contains("spring webflux") -> "Spring Boot WebFlux"
            context.contains("spring") -> "Spring Framework"
            context.contains("webflux") -> "Spring Boot WebFlux"
            context.contains("reactive") && context.contains("spring") -> "Spring Boot WebFlux"
            else -> "Spring Boot" // Default assumption for Kotlin projects
        }

    private fun detectLanguage(context: String): String =
        when {
            context.contains("kotlin") -> "Kotlin"
            context.contains("java") -> "Java"
            context.contains("spring boot") -> "Kotlin" // Modern Spring Boot projects often use Kotlin
            else -> "Kotlin" // Default assumption
        }

    private fun detectVersion(context: String): String? {
        // In real implementation, this would parse pom.xml or build.gradle.kts
        return when {
            context.contains("spring boot 3") -> "3.x"
            context.contains("spring boot 2") -> "2.x"
            else -> null
        }
    }

    private fun detectSecurityFramework(context: String): String? =
        when {
            context.contains("spring security") -> "Spring Security"
            context.contains("security") && context.contains("spring") -> "Spring Security"
            context.contains("jwt") || context.contains("token") -> "Custom JWT Security"
            else -> "None"
        }

    private fun detectDatabaseType(context: String): String? =
        when {
            context.contains("mongodb") || context.contains("mongo") -> "MongoDB"
            context.contains("postgresql") || context.contains("postgres") -> "PostgreSQL"
            context.contains("mysql") -> "MySQL"
            context.contains("h2") -> "H2"
            context.contains("reactive") -> "MongoDB" // Reactive often uses MongoDB
            else -> null
        }

    private fun detectBuildTool(context: String): String? =
        when {
            context.contains("gradle") -> "Gradle"
            context.contains("maven") -> "Maven"
            context.contains("kotlin") -> "Gradle" // Kotlin projects often use Gradle
            else -> "Maven" // Default assumption
        }

    private suspend fun createProjectContextInfo(
        context: TaskContext,
        techStack: TechStackInfo,
    ): ProjectContextInfo {
        val codingGuidelines = createDefaultCodingGuidelines(techStack)
        val dependencies = extractDependencies(context)

        return ProjectContextInfo(
            projectDescription = context.projectDocument.description,
            techStack = techStack,
            codingGuidelines = codingGuidelines,
            dependencyInfo = dependencies,
        )
    }

    private fun createDefaultCodingGuidelines(techStack: TechStackInfo): CodingGuidelines {
        val programmingStyle =
            ProgrammingStyle(
                language = techStack.language,
                framework = techStack.framework,
                architecturalPatterns =
                    when (techStack.framework) {
                        "Spring Boot WebFlux" -> listOf("Reactive", "SOLID", "Clean Architecture", "Non-blocking")
                        "Spring Boot" -> listOf("MVC", "SOLID", "Clean Architecture", "Dependency Injection")
                        else -> listOf("SOLID", "Clean Architecture")
                    },
                codingConventions =
                    mapOf(
                        "naming" to "camelCase for variables, PascalCase for classes",
                        "testing" to "Unit tests for all public methods",
                        "documentation" to "KDoc for public APIs",
                        "coroutines" to if (techStack.framework.contains("WebFlux")) "Use suspend functions" else "Optional",
                    ),
                testingApproach = "Unit + Integration Testing",
                documentationLevel = "Standard",
            )

        val effectiveGuidelines =
            Guidelines(
                rules =
                    listOf(
                        "Use ${techStack.language} idioms and conventions",
                        "Follow ${techStack.framework} best practices",
                        "Implement proper error handling",
                        "Write testable code",
                    ),
                patterns =
                    when (techStack.framework) {
                        "Spring Boot WebFlux" ->
                            listOf(
                                "Use Mono for 0-1 elements",
                                "Use Flux for 0-N elements",
                                "Avoid blocking operations",
                                "Use reactive repositories",
                            )

                        "Spring Boot" ->
                            listOf(
                                "Use @RestController for REST APIs",
                                "Use @Service for business logic",
                                "Use @Repository for data access",
                                "Implement proper dependency injection",
                            )

                        else -> emptyList()
                    },
                conventions =
                    mapOf(
                        "fileNaming" to "PascalCase.kt",
                        "packageNaming" to "lowercase.separated.by.dots",
                        "constantNaming" to "UPPER_SNAKE_CASE",
                    ),
            )

        return CodingGuidelines(
            clientStandards = null, // Would be loaded from client settings
            projectStandards = null, // Would be loaded from project settings
            effectiveGuidelines = effectiveGuidelines,
            programmingStyle = programmingStyle,
        )
    }

    private fun extractDependencies(context: TaskContext): List<String> {
        // In real implementation, this would parse pom.xml or build.gradle.kts
        val techStack = context.projectContextInfo?.techStack
        return when (techStack?.framework) {
            "Spring Boot WebFlux" ->
                listOf(
                    "spring-boot-starter-webflux",
                    "spring-boot-starter-data-mongodb-reactive",
                    "kotlinx-coroutines-reactor",
                    "reactor-kotlin-extensions",
                )

            "Spring Boot" ->
                listOf(
                    "spring-boot-starter-web",
                    "spring-boot-starter-data-jpa",
                    "spring-boot-starter-validation",
                )

            else -> emptyList()
        }
    }

    private fun buildEnhancedSummary(
        client: com.jervis.entity.mongo.ClientDocument,
        project: com.jervis.entity.mongo.ProjectDocument,
        techStack: TechStackInfo,
        projectContextInfo: ProjectContextInfo,
    ): String =
        buildString {
            append("=== ENHANCED SCOPE RESOLUTION ===\n\n")

            append("CLIENT INFORMATION:\n")
            append("- Name: ${client.name}\n")
            client.description?.let { append("- Description: $it\n") }
            append("\n")

            append("PROJECT INFORMATION:\n")
            append("- Name: ${project.name}\n")
            project.description?.let { append("- Description: $it\n") }
            append("\n")

            append("DETECTED TECHNOLOGY STACK:\n")
            append("- Framework: ${techStack.framework}\n")
            append("- Language: ${techStack.language}\n")
            techStack.version?.let { append("- Version: $it\n") }
            techStack.securityFramework?.let { append("- Security: $it\n") }
            techStack.databaseType?.let { append("- Database: $it\n") }
            techStack.buildTool?.let { append("- Build Tool: $it\n") }
            append("\n")

            append("CODING GUIDELINES:\n")
            append(
                "- Programming Style: ${
                    projectContextInfo.codingGuidelines.programmingStyle.architecturalPatterns.joinToString(
                        ", ",
                    )
                }\n",
            )
            append("- Testing Approach: ${projectContextInfo.codingGuidelines.programmingStyle.testingApproach}\n")
            append("- Documentation Level: ${projectContextInfo.codingGuidelines.programmingStyle.documentationLevel}\n")
            append("\n")

            if (projectContextInfo.dependencyInfo.isNotEmpty()) {
                append("KEY DEPENDENCIES:\n")
                projectContextInfo.dependencyInfo.forEach { dependency ->
                    append("- $dependency\n")
                }
                append("\n")
            }

            append("CONTEXT READY FOR ENHANCED PLANNING")
        }
}
