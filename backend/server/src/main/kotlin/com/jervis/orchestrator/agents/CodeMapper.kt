package com.jervis.orchestrator.agents

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeLLMRequestStructured
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.prompt.dsl.Prompt
import com.jervis.koog.KoogPromptExecutorFactory
import com.jervis.koog.SmartModelSelector
import com.jervis.koog.tools.coding.CodingTools
import com.jervis.orchestrator.model.CodeMapSummary
import com.jervis.orchestrator.model.TaskDocument
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * CodeMapper (Specialist 6.6)
 *
 * Responsibilities:
 * - Identify entrypoints, modules, symbols, affected files
 * - Output CodeMapSummary
 */
@Component
class CodeMapper(
    private val promptExecutorFactory: KoogPromptExecutorFactory,
    private val smartModelSelector: SmartModelSelector,
    private val codingTools: CodingTools,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    suspend fun create(task: TaskDocument): AIAgent<String, CodeMapSummary> {
        val promptExecutor = promptExecutorFactory.getExecutor("OLLAMA")

        val agentStrategy =
            strategy<String, CodeMapSummary>("Code Mapping") {
                val nodeMap by nodeLLMRequestStructured<CodeMapSummary>().transform { it.getOrThrow().data }
                edge(nodeStart forwardTo nodeMap)
                edge(nodeMap forwardTo nodeFinish)
            }

        val model =
            smartModelSelector.selectModelBlocking(
                baseModelName = SmartModelSelector.BaseModelTypeEnum.AGENT,
                inputContent = task.content,
            )

        val toolRegistry =
            ToolRegistry {
                tools(codingTools)
            }

        val agentConfig =
            AIAgentConfig(
                prompt =
                    Prompt.build("code-mapper") {
                        system(
                            """
                            You are Code Mapper for JERVIS Orchestrator.
                            Your goal is to identify relevant source code entrypoints, modules, and files for a given task.
                            
                            Workflow:
                            1. Use coding tools to explore the codebase.
                            2. Identify core classes, functions, and files related to the goal.
                            3. Produce a concise CodeMapSummary.
                            
                            Keep the summary focused and avoid listing irrelevant files.
                            """.trimIndent(),
                        )
                    },
                model = model,
                maxAgentIterations = 100,
            )

        return AIAgent(
            promptExecutor = promptExecutor,
            toolRegistry = toolRegistry,
            strategy = agentStrategy,
            agentConfig = agentConfig,
        )
    }

    suspend fun run(
        task: TaskDocument,
        goal: String,
    ): CodeMapSummary {
        val agent = create(task)
        return agent.run(goal)
    }
}
