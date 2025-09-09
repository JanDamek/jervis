package com.jervis.service.agent.planner

import com.jervis.configuration.prompts.McpToolType
import com.jervis.domain.context.TaskContext
import com.jervis.domain.model.ModelType
import com.jervis.domain.plan.Plan
import com.jervis.domain.plan.PlanStep
import com.jervis.domain.plan.StepStatus
import com.jervis.service.gateway.LlmGateway
import com.jervis.service.prompts.PromptRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

@Service
class EnhancedPlanner(
    private val llmGateway: LlmGateway,
    private val promptRepository: PromptRepository,
    private val originalPlanner: Planner,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    @Serializable
    data class PlannerStepDto(
        val name: String,
        val taskDescription: String? = null,
    )

    suspend fun createMultiSourcePlan(
        request: String,
        context: TaskContext,
    ): Plan {
        val techStack = context.projectContextInfo?.techStack

        logger.debug { "ENHANCED_PLANNER_START: Creating multi-source plan for request='$request', techStack='$techStack'" }

        return when {
            isCodeQuery(request) -> createCodeAnalysisPlan(request, context)
            isSecurityQuery(request) -> createSecurityAnalysisPlan(request, context)
            isImplementationQuery(request) -> createImplementationPlan(request, context)
            else -> createGenericEnhancedPlan(request, context)
        }
    }

    private fun isCodeQuery(request: String): Boolean {
        val lowerRequest = request.lowercase()
        return lowerRequest.contains("code") ||
            lowerRequest.contains("implementation") ||
            lowerRequest.contains("class") ||
            lowerRequest.contains("method") ||
            lowerRequest.contains("function") ||
            lowerRequest.contains("controller") ||
            lowerRequest.contains("service") ||
            lowerRequest.contains("repository")
    }

    private fun isSecurityQuery(request: String): Boolean {
        val lowerRequest = request.lowercase()
        return lowerRequest.contains("security") ||
            lowerRequest.contains("authentication") ||
            lowerRequest.contains("authorization") ||
            lowerRequest.contains("permission") ||
            lowerRequest.contains("access") ||
            lowerRequest.contains("login") ||
            lowerRequest.contains("token")
    }

    private fun isImplementationQuery(request: String): Boolean {
        val lowerRequest = request.lowercase()
        return lowerRequest.contains("implement") ||
            lowerRequest.contains("create") ||
            lowerRequest.contains("build") ||
            lowerRequest.contains("develop") ||
            lowerRequest.contains("write") ||
            lowerRequest.contains("add")
    }

    private suspend fun createCodeAnalysisPlan(
        request: String,
        context: TaskContext,
    ): Plan {
        logger.debug { "ENHANCED_PLANNER_CODE_ANALYSIS: Creating code analysis plan" }

        val planId = ObjectId()
        val steps = mutableListOf<PlanStep>()

        // Multi-source strategy for code analysis
        steps.add(
            createPlanStep(
                planId = planId,
                contextId = context.id,
                order = 1,
                name = "enhanced-rag-query",
                taskDescription = "Search for code patterns and implementations related to: $request",
            ),
        )

        steps.add(
            createPlanStep(
                planId = planId,
                contextId = context.id,
                order = 2,
                name = "code-extractor",
                taskDescription = "Extract relevant code classes and methods for: $request",
            ),
        )

        steps.add(
            createPlanStep(
                planId = planId,
                contextId = context.id,
                order = 3,
                name = "llm",
                taskDescription = "Synthesize code analysis findings from RAG and code extraction for: $request",
            ),
        )

        return Plan(
            id = planId,
            contextId = context.id,
            originalQuestion = request,
            originalLanguage = "en",
            englishQuestion = request,
            steps = steps,
        )
    }

    private suspend fun createSecurityAnalysisPlan(
        request: String,
        context: TaskContext,
    ): Plan {
        logger.debug { "ENHANCED_PLANNER_SECURITY_ANALYSIS: Creating security analysis plan" }

        val planId = ObjectId()
        val steps = mutableListOf<PlanStep>()

        // Enhanced security analysis strategy
        steps.add(
            createPlanStep(
                planId = planId,
                contextId = context.id,
                order = 1,
                name = "enhanced-rag-query",
                taskDescription = "Search for security configurations and implementations: $request",
            ),
        )

        steps.add(
            createPlanStep(
                planId = planId,
                contextId = context.id,
                order = 2,
                name = "code-extractor",
                taskDescription = "Extract security-related classes and configurations",
            ),
        )

        // Add controller analysis if no explicit security framework detected
        if (context.projectContextInfo?.techStack?.securityFramework == "None" ||
            context.projectContextInfo?.techStack?.securityFramework == null
        ) {
            steps.add(
                createPlanStep(
                    planId = planId,
                    contextId = context.id,
                    order = 3,
                    name = "code-extractor",
                    taskDescription = "Analyze controllers for manual security implementations",
                ),
            )
        }

        steps.add(
            createPlanStep(
                planId = planId,
                contextId = context.id,
                order = steps.size + 1,
                name = "llm",
                taskDescription = "Synthesize security analysis findings and provide recommendations",
            ),
        )

        return Plan(
            id = planId,
            contextId = context.id,
            originalQuestion = request,
            originalLanguage = "en",
            englishQuestion = request,
            steps = steps,
        )
    }

    private suspend fun createImplementationPlan(
        request: String,
        context: TaskContext,
    ): Plan {
        logger.debug { "ENHANCED_PLANNER_IMPLEMENTATION: Creating implementation plan" }

        val planId = ObjectId()
        val steps = mutableListOf<PlanStep>()
        val techStack = context.projectContextInfo?.techStack

        // Research phase with enhanced context
        steps.add(
            createPlanStep(
                planId = planId,
                contextId = context.id,
                order = 1,
                name = "enhanced-rag-query",
                taskDescription = buildTechStackAwareQuery(request, techStack),
            ),
        )

        // Code analysis phase
        steps.add(
            createPlanStep(
                planId = planId,
                contextId = context.id,
                order = 2,
                name = "code-extractor",
                taskDescription = "Analyze existing code structure and patterns for: $request",
            ),
        )

        // Implementation phase with context-aware guidelines
        steps.add(
            createPlanStep(
                planId = planId,
                contextId = context.id,
                order = 3,
                name = "code-write",
                taskDescription = buildImplementationTask(request, context),
            ),
        )

        return Plan(
            id = planId,
            contextId = context.id,
            originalQuestion = request,
            originalLanguage = "en",
            englishQuestion = request,
            steps = steps,
        )
    }

    private suspend fun createGenericEnhancedPlan(
        request: String,
        context: TaskContext,
    ): Plan {
        logger.debug { "ENHANCED_PLANNER_GENERIC: Creating generic enhanced plan" }

        // Fall back to original planner but with enhanced context
        val originalPlan =
            originalPlanner.createPlan(
                context,
                Plan(
                    id = ObjectId(),
                    contextId = context.id,
                    originalQuestion = request,
                    originalLanguage = "en",
                    englishQuestion = "Enhanced generic plan: $request",
                ),
            )

        // Enhance the first step to use enhanced RAG if it's a rag-query
        val enhancedSteps =
            originalPlan.steps.map { step ->
                if (step.name == "rag-query") {
                    step.copy(
                        name = "enhanced-rag-query",
                        taskDescription = "Enhanced query with technology context: ${step.taskDescription}",
                    )
                } else {
                    step
                }
            }

        return originalPlan.copy(
            steps = enhancedSteps,
        )
    }

    private fun buildTechStackAwareQuery(
        request: String,
        techStack: com.jervis.domain.context.TechStackInfo?,
    ): String =
        buildString {
            append("Search for information about: $request")

            if (techStack != null) {
                append("\n\nTechnology context:")
                append("\n- Framework: ${techStack.framework}")
                append("\n- Language: ${techStack.language}")
                techStack.securityFramework?.let { append("\n- Security: $it") }
                techStack.databaseType?.let { append("\n- Database: $it") }
            }
        }

    private fun buildImplementationTask(
        request: String,
        context: TaskContext,
    ): String {
        val guidelines = context.projectContextInfo?.codingGuidelines
        val techStack = context.projectContextInfo?.techStack

        return buildString {
            append("Implement: $request")
            append("\n\nTechnology Guidelines:")

            if (techStack != null) {
                append("\n- Use ${techStack.framework} patterns")
                append("\n- Follow ${techStack.language} conventions")

                when (techStack.framework) {
                    "Spring Boot WebFlux" -> {
                        append("\n- Use reactive programming with Mono/Flux")
                        append("\n- Implement non-blocking operations")
                        append("\n- Use reactive repositories")
                    }

                    "Spring Boot" -> {
                        append("\n- Use Spring Boot annotations")
                        append("\n- Follow Spring MVC patterns")
                        append("\n- Implement proper dependency injection")
                    }
                }
            }

            if (guidelines != null) {
                append("\n\nCoding Standards:")
                append("\n- Programming Style: ${guidelines.programmingStyle.architecturalPatterns.joinToString(", ")}")
                append("\n- Testing Approach: ${guidelines.programmingStyle.testingApproach}")
                append("\n- Documentation Level: ${guidelines.programmingStyle.documentationLevel}")
            }
        }
    }

    private fun createPlanStep(
        planId: ObjectId,
        contextId: ObjectId,
        order: Int,
        name: String,
        taskDescription: String,
    ): PlanStep =
        PlanStep(
            id = ObjectId(),
            order = order,
            planId = planId,
            contextId = contextId,
            name = name,
            taskDescription = taskDescription,
            status = StepStatus.PENDING,
        )
}
