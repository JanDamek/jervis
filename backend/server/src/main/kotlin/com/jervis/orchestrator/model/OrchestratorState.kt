package com.jervis.orchestrator.model

import kotlinx.serialization.Serializable

/**
 * Orchestrator workflow state - flows through sequential subgraphs.
 * Each phase updates this state with its results.
 */
@Serializable
data class OrchestratorState(
    val userQuery: String,
    val iteration: Int = 0,
    val maxIterations: Int = 3,

    // Phase results
    val interpretation: NormalizedRequest? = null,
    val context: ContextPack? = null,
    val plan: OrderedPlan? = null,
    val evidence: EvidencePack? = null,
    val review: ReviewResult? = null,

    // Execution tracking
    val executedSteps: List<PlanStep> = emptyList(),
    val currentStepIndex: Int = 0,

    // Final result
    val finalAnswer: String? = null,
    val userQuestion: String? = null,
    val resumePoint: ResumePoint? = null
) {
    fun hasInterpretation() = interpretation != null
    fun hasContext() = context != null
    fun hasPlan() = plan != null
    fun hasEvidence() = evidence != null
    fun hasReview() = review != null

    fun canContinue() = iteration < maxIterations
    fun needsMoreSteps() = plan != null && currentStepIndex < plan.steps.size

    fun nextIteration() = copy(iteration = iteration + 1)

    fun withInterpretation(interpretation: NormalizedRequest) =
        copy(interpretation = interpretation)

    fun withContext(context: ContextPack) =
        copy(context = context)

    fun withPlan(plan: OrderedPlan) =
        copy(plan = plan, currentStepIndex = 0)

    fun withEvidence(evidence: EvidencePack) =
        copy(evidence = evidence)

    fun withReview(review: ReviewResult) =
        copy(review = review)

    fun withStepExecuted(step: PlanStep) = copy(
        executedSteps = executedSteps + step,
        currentStepIndex = currentStepIndex + 1
    )

    fun withFinalAnswer(answer: String) =
        copy(finalAnswer = answer)

    fun withUserQuestion(question: String, resumePoint: ResumePoint? = null) =
        copy(userQuestion = question, resumePoint = resumePoint)
}

/**
 * Phase enum for logging and progress tracking
 */
enum class OrchestratorPhase {
    INIT,
    INTERPRETATION,
    CONTEXT_GATHERING,
    PLANNING,
    EXECUTION,
    REVIEW,
    FINALIZATION,
    WAITING_FOR_USER
}

/**
 * Result type for orchestrator - determines next action
 */
@Serializable
sealed class OrchestratorResult {
    @Serializable
    data class Continue(val state: OrchestratorState, val phase: OrchestratorPhase) : OrchestratorResult()

    @Serializable
    data class Final(val answer: String) : OrchestratorResult()

    @Serializable
    data class WaitForUser(val question: String, val resumePoint: ResumePoint?) : OrchestratorResult()
}
