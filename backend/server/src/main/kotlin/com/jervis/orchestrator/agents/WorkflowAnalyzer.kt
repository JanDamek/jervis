package com.jervis.orchestrator.agents

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeLLMRequestStructured
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.Prompt
import com.jervis.koog.KoogPromptExecutorFactory
import com.jervis.koog.SmartModelSelector
import com.jervis.orchestrator.model.TaskDocument
import com.jervis.types.ProjectId
import com.jervis.orchestrator.model.WorkflowMapping
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * WorkflowAnalyzer (Specialist 6.3)
 * 
 * Responsibilities:
 * - Derive status groups from tracker workflow definition using heuristics
 * - Map states to: draft, ready, doing, review, blocked, terminal
 * - Provide confidence score
 */
@Component
class WorkflowAnalyzer(
    private val promptExecutorFactory: KoogPromptExecutorFactory,
    private val smartModelSelector: SmartModelSelector,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    suspend fun create(task: TaskDocument): AIAgent<String, WorkflowMapping> {
        val promptExecutor = promptExecutorFactory.getExecutor("OLLAMA")
        
        val agentStrategy = strategy<String, WorkflowMapping>("Analyze Workflow") {
            val nodeAnalyze by nodeLLMRequestStructured<WorkflowMapping>().transform { it.getOrThrow().data }
            edge(nodeStart forwardTo nodeAnalyze)
            edge(nodeAnalyze forwardTo nodeFinish)
        }

        val model = smartModelSelector.selectModelBlocking(
            baseModelName = SmartModelSelector.BaseModelTypeEnum.AGENT,
            inputContent = task.content,
            projectId = task.projectId?.let { ProjectId.fromString(it) }
        )

        val agentConfig = AIAgentConfig(
            prompt = Prompt.build("workflow-analyzer") {
                system("""
                    You are Workflow Analyzer for JERVIS Orchestrator.
                    Your goal is to map tracker-specific states to universal state groups.
                    
                    State Groups:
                    - draftStates: Not ready for work (e.g. "Draft", "Backlog", "Refinement")
                    - executionReadyStates: Can be implemented (e.g. "To Do", "Ready", "Approved")
                    - doingStates: Currently being worked on (e.g. "In Progress", "In Development")
                    - reviewStates: Waiting for review (e.g. "Peer Review", "QA", "Waiting for Approval")
                    - blockedStates: Blocked by external factor (e.g. "Blocked", "On Hold")
                    - terminalStates: Finished (e.g. "Done", "Resolved", "Closed", "Cancelled")
                    
                    Heuristics:
                    - Use lexical analysis (names of states)
                    - Use structural analysis (where it sits in the graph)
                    - Check for required fields or roles
                    
                    Provide a confidence score (0.0 - 1.0). If confidence is low, explain why in reasoning.
                """.trimIndent())
            },
            model = model,
            maxAgentIterations = 100
        )

        return AIAgent(
            promptExecutor = promptExecutor,
            strategy = agentStrategy,
            agentConfig = agentConfig
        )
    }

    suspend fun run(task: TaskDocument, workflowDefinitionJson: String): WorkflowMapping {
        val agent = create(task)
        return agent.run(workflowDefinitionJson)
    }
}
