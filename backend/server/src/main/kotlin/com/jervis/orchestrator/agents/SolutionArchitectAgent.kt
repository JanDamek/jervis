package com.jervis.orchestrator.agents

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeLLMRequestStructured
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.Prompt
import com.jervis.entity.TaskDocument
import com.jervis.koog.KoogPromptExecutorFactory
import com.jervis.koog.SmartModelSelector
import com.jervis.orchestrator.model.ContextPack
import com.jervis.orchestrator.model.DelegationSpec
import com.jervis.orchestrator.model.EvidencePack
import com.jervis.orchestrator.model.PlanStep
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * SolutionArchitectAgent - Internal agent for technical implementation design.
 *
 * Role:
 * - Synthesize evidence and context into a concrete implementation plan
 * - Decide between 'aider', 'openhands' and 'junie'
 * - Specify target files and detailed instructions
 * - Provide verification steps
 *
 * Used by Orchestrator before every A2A delegation.
 */
@Component
class SolutionArchitectAgent(
    private val promptExecutorFactory: KoogPromptExecutorFactory,
    private val smartModelSelector: SmartModelSelector,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    suspend fun create(
        task: TaskDocument,
        context: ContextPack,
        evidence: EvidencePack,
        step: PlanStep,
    ): AIAgent<String, DelegationSpec> {
        val model =
            smartModelSelector.selectModel(
                baseModelName = SmartModelSelector.BaseModelTypeEnum.AGENT,
                inputContent = task.content,
            )

        val promptExecutor = promptExecutorFactory.getExecutor("OLLAMA")

        val exampleSpec =
            DelegationSpec(
                agent = "aider",
                targetFiles = listOf("src/main/kotlin/com/jervis/service/UserService.kt"),
                instructions = "Add validation to createUser method. Ensure email is unique by checking against DB. Handle DuplicateKeyException.",
                verifyInstructions = "./gradlew test --tests UserServiceTest",
                reasoning = "Localized change in a single file, aider is suitable.",
            )

        val agentStrategy =
            strategy<String, DelegationSpec>("Solution Architecture") {
                val nodeSpec by
                    nodeLLMRequestStructured<DelegationSpec>(
                        examples = listOf(exampleSpec),
                    ).transform { result ->
                        result
                            .getOrElse { e ->
                                throw IllegalStateException("SolutionArchitectAgent: structured output parsing failed", e)
                            }.data
                    }

                edge(nodeStart forwardTo nodeSpec)
                edge(nodeSpec forwardTo nodeFinish)
            }

        val agentConfig =
            AIAgentConfig(
                prompt =
                    Prompt.build("architect-agent") {
                        system(
                            """
                            You are Solution Architect Agent. Your goal is to create a detailed technical specification for a coding agent.
                            
                            INPUTS:
                            1. ContextPack: Project metadata, build/test commands, known facts.
                            2. EvidencePack: Gathered research, file contents, logs, Jira details.
                            3. PlanStep: The current atomic step to execute.
                            
                            RULES FOR AGENT SELECTION:
                            - Prefer 'aider' when:
                                * The change is small and localized (1-3 files).
                                * You know the exact files and lines to change.
                                * It's a quick bugfix or a simple feature enhancement.
                            - Prefer 'openhands' when:
                                * The task is broad or complex (architectural changes).
                                * You don't know all the files involved (requires exploration).
                                * Requires running complex builds/tests and debugging from output.
                                * Involves multiple modules or cross-cutting concerns.
                            - Prefer 'junie' when:
                                * The task requires high-quality analysis or complex programming and the user explicitly wants it FAST (paid service).
                                * High-priority status investigation where speed and depth are key.
                                * Complex coding where time is a primary factor and user accepts higher cost.
                            
                            OUTPUT:
                            - You MUST output a structured DelegationSpec.
                            - 'instructions' must be precise, covering logic, edge cases, and style.
                            - 'verifyInstructions' must include actual commands to run based on ContextPack.
                            - 'targetFiles' must be absolute or relative to project root (required for Aider).
                            
                            DETERMINISM & QUALITY:
                            - Ensure the specification is self-contained. The coding agent should not need to ask clarifying questions if the evidence is already there.
                            - If evidence is insufficient for a clear specification, indicate this (which might lead the orchestrator to a research phase or WAITING_FOR_USER).
                            
                            Be professional and technically precise.
                            """.trimIndent(),
                        )
                    },
                model = model,
                maxAgentIterations = 100,
            )

        return AIAgent(
            promptExecutor = promptExecutor,
            toolRegistry = ToolRegistry {},
            strategy = agentStrategy,
            agentConfig = agentConfig,
        )
    }

    suspend fun run(
        task: TaskDocument,
        context: ContextPack,
        evidence: EvidencePack,
        step: PlanStep,
    ): DelegationSpec {
        logger.info { "ARCHITECT_AGENT_START | correlationId=${task.correlationId} | step=${step.action}" }

        val promptInput =
            buildString {
                appendLine("CURRENT_STEP: [${step.action}/${step.executor}] ${step.description}")
                appendLine()
                appendLine("CONTEXT:")
                appendLine("  projectPath: ${context.projectPath}")
                appendLine("  buildCommands: ${context.buildCommands.joinToString(", ")}")
                appendLine("  testCommands: ${context.testCommands.joinToString(", ")}")
                appendLine("  environment: ${context.environmentHints}")
                appendLine()
                appendLine("EVIDENCE_SUMMARY:")
                appendLine(evidence.summary.ifBlank { evidence.combinedSummary() })
                appendLine()
                appendLine("Create technical DelegationSpec:")
            }

        val agent = create(task, context, evidence, step)
        val result = agent.run(promptInput)

        logger.debug { "ARCHITECT_AGENT_COMPLETE | agent=${result.agent} | targetFiles=${result.targetFiles.size}" }

        return result
    }
}
