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
import com.jervis.koog.tools.external.ConfluenceReadTools
import com.jervis.koog.tools.external.EmailReadTools
import com.jervis.koog.tools.external.JiraReadTools
import com.jervis.orchestrator.model.EvidencePack
import com.jervis.orchestrator.model.TaskDocument
import com.jervis.types.ProjectId
import com.jervis.service.confluence.ConfluenceService
import com.jervis.service.email.EmailService
import com.jervis.service.jira.JiraService
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * EvidenceCollector (Specialist 6.5)
 *
 * Responsibilities:
 * - Load links from tracker (Confluence/wiki/attachments)
 * - Produce clean text + extracts
 */
@Component
class EvidenceCollector(
    private val promptExecutorFactory: KoogPromptExecutorFactory,
    private val smartModelSelector: SmartModelSelector,
    private val jiraService: JiraService,
    private val confluenceService: ConfluenceService,
    private val emailService: EmailService,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    suspend fun create(task: TaskDocument): AIAgent<String, EvidencePack> {
        val promptExecutor = promptExecutorFactory.getExecutor("OLLAMA")

        val agentStrategy = strategy<String, EvidencePack>("Evidence Collection") {
            val nodeCollect by nodeLLMRequestStructured<EvidencePack>().transform { it.getOrThrow().data }
            edge(nodeStart forwardTo nodeCollect)
            edge(nodeCollect forwardTo nodeFinish)
        }

        val model = smartModelSelector.selectModelBlocking(
            baseModelName = SmartModelSelector.BaseModelTypeEnum.AGENT,
            inputContent = task.content,
            projectId = task.projectId?.let { ProjectId.fromString(it) }
        )

        val entityTask = com.jervis.entity.TaskDocument(
            content = task.content,
            clientId = com.jervis.types.ClientId.fromString(task.clientId),
            projectId = task.projectId?.let { ProjectId.fromString(it) },
            correlationId = task.correlationId,
            sourceUrn = com.jervis.types.SourceUrn(task.sourceUrn),
            type = com.jervis.dto.TaskTypeEnum.valueOf(task.type)
        )

        val toolRegistry = ToolRegistry {
            tools(JiraReadTools(entityTask, jiraService))
            tools(ConfluenceReadTools(entityTask, confluenceService))
            tools(EmailReadTools(entityTask, emailService))
        }

        val agentConfig = AIAgentConfig(
            prompt = Prompt.build("evidence-collector") {
                system("""
                    You are Evidence Collector for JERVIS Orchestrator.
                    Your goal is to fetch detailed content from external trackers (Jira, Confluence, Email).
                    
                    Workflow:
                    1. Read the specified work items, pages, or emails.
                    2. Extract the core information relevant to the query.
                    3. Produce an EvidencePack.
                    
                    Focus on completeness of the requested evidence.
                """.trimIndent())
            },
            model = model,
            maxAgentIterations = 100
        )

        return AIAgent(
            promptExecutor = promptExecutor,
            toolRegistry = toolRegistry,
            strategy = agentStrategy,
            agentConfig = agentConfig
        )
    }

    suspend fun run(task: TaskDocument, targets: String): EvidencePack {
        val agent = create(task)
        return agent.run(targets)
    }
}
