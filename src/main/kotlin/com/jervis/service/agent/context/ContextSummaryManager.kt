package com.jervis.service.agent.context

import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.domain.plan.PlanStep
import com.jervis.domain.plan.StepStatus
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class ContextSummaryManager {
    private val logger = KotlinLogging.logger {}

    suspend fun cleanupContextSummary(context: TaskContext): String {
        val relevantSteps = filterRelevantSteps(context.plans)
        val keyInsights = extractKeyInsights(relevantSteps)
        val projectInfo = extractProjectInfo(context)

        return buildCleanSummary(projectInfo, keyInsights)
    }

    private fun filterRelevantSteps(plans: List<Plan>): List<PlanStep> =
        plans.flatMap { it.steps }.filter { step ->
            when (step.name) {
                "scope.resolve" -> true
                "rag-query" -> step.output?.output?.contains("found") == true
                "code-extractor" -> step.status == StepStatus.DONE
                "joern" -> true
                else -> false
            }
        }

    private fun extractKeyInsights(steps: List<PlanStep>): List<String> {
        val insights = mutableListOf<String>()

        steps.forEach { step ->
            when (step.name) {
                "scope.resolve" -> {
                    step.output?.let { output ->
                        if (output.output.contains("framework") || output.output.contains("technology")) {
                            insights.add("Technology stack identified: ${extractTechInfo(output.output)}")
                        }
                    }
                }

                "rag-query" -> {
                    step.output?.let { output ->
                        if (output.output.contains("found")) {
                            insights.add("RAG query found relevant information")
                        }
                    }
                }

                "code-extractor" -> {
                    if (step.status == StepStatus.DONE) {
                        insights.add("Code analysis completed successfully")
                    }
                }

                "joern" -> {
                    step.output?.let { output ->
                        if (output.output.contains("analysis")) {
                            insights.add("Deep code analysis performed")
                        }
                    }
                }
            }
        }

        return insights
    }

    private fun extractProjectInfo(context: TaskContext): String {
        val client = context.clientDocument
        val project = context.projectDocument

        return buildString {
            append("Client: ${client.name}")
            if (client.description?.isNotBlank() == true) {
                append(" - ${client.description}")
            }
            append("\n")

            append("Project: ${project.name}")
            if (project.description?.isNotBlank() == true) {
                append(" - ${project.description}")
            }
            append("\n")
        }
    }

    private fun buildCleanSummary(
        projectInfo: String,
        keyInsights: List<String>,
    ): String =
        buildString {
            append("=== PROJECT CONTEXT ===\n")
            append(projectInfo)
            append("\n")

            if (keyInsights.isNotEmpty()) {
                append("=== KEY INSIGHTS ===\n")
                keyInsights.forEach { insight ->
                    append("• $insight\n")
                }
                append("\n")
            }

            append("=== CONTEXT STATUS ===\n")
            append("Summary updated at: ${java.time.Instant.now()}\n")
            append("Ready for new planning cycle.\n")
        }

    private fun extractTechInfo(output: String): String {
        // Extract technology information from scope.resolve output
        return when {
            output.contains("Spring Boot") -> "Spring Boot"
            output.contains("Spring WebFlux") -> "Spring WebFlux"
            output.contains("Kotlin") -> "Kotlin"
            output.contains("Java") -> "Java"
            else -> "Unknown"
        }
    }
}
