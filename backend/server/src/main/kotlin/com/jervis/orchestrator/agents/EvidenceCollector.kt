package com.jervis.orchestrator.agents

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeLLMRequestStructured
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.prompt.dsl.Prompt
import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.common.types.SourceUrn
import com.jervis.integration.bugtracker.BugTrackerService
import com.jervis.integration.wiki.WikiService
import com.jervis.knowledgebase.model.EvidencePack
import com.jervis.koog.KoogPromptExecutorFactory
import com.jervis.koog.SmartModelSelector
import com.jervis.koog.tools.external.BugTrackerReadTools
import com.jervis.koog.tools.external.EmailReadTools
import com.jervis.koog.tools.external.WikiReadTools
import com.jervis.orchestrator.model.TaskDocument
import com.jervis.service.email.EmailService
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
    private val jiraService: BugTrackerService,
    private val confluenceService: WikiService,
    private val emailService: EmailService,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    suspend fun create(task: TaskDocument): AIAgent<String, EvidencePack> {
        val promptExecutor = promptExecutorFactory.getExecutor("OLLAMA")

        val agentStrategy =
            strategy<String, EvidencePack>("Evidence Collection") {
                val nodeCollect by nodeLLMRequestStructured<EvidencePack>().transform { it.getOrThrow().data }
                edge(nodeStart forwardTo nodeCollect)
                edge(nodeCollect forwardTo nodeFinish)
            }

        val model =
            smartModelSelector.selectModelBlocking(
                baseModelName = SmartModelSelector.BaseModelTypeEnum.AGENT,
                inputContent = task.content,
            )

        val entityTask =
            com.jervis.entity.TaskDocument(
                content = task.content,
                clientId =
                    ClientId
                        .fromString(task.clientId),
                projectId = task.projectId?.let { ProjectId.fromString(it) },
                correlationId = task.correlationId,
                sourceUrn = SourceUrn(task.sourceUrn),
                type =
                    com.jervis.dto.TaskTypeEnum
                        .valueOf(task.type),
            )

        val toolRegistry =
            ToolRegistry {
                tools(BugTrackerReadTools(entityTask, jiraService))
                tools(WikiReadTools(entityTask, confluenceService))
                tools(EmailReadTools(entityTask, emailService))
            }

        val agentConfig =
            AIAgentConfig(
                prompt =
                    Prompt.build("evidence-collector") {
                        system(
                            """
                            You are Evidence Collector for JERVIS Orchestrator.
                            Your goal is to fetch detailed content from external trackers (Jira, Confluence, Email).
                            
                            Workflow:
                            1. Read the specified work items, pages, or emails.
                            2. Extract the core information relevant to the query.
                            3. Produce an EvidencePack.
                            
                            Focus on completeness of the requested evidence.
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
        targets: String,
    ): EvidencePack {
        val agent = create(task)
        return agent.run(targets)
    }
}
