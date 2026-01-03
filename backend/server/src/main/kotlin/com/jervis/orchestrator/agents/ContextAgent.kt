package com.jervis.orchestrator.agents

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeLLMRequestStructured
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.prompt.dsl.Prompt
import com.jervis.entity.TaskDocument
import com.jervis.koog.KoogPromptExecutorFactory
import com.jervis.koog.SmartModelSelector
import com.jervis.orchestrator.model.ContextPack
import com.jervis.rag.internal.graphdb.GraphDBService
import com.jervis.rag.internal.graphdb.model.Direction
import com.jervis.service.project.ProjectService
import com.jervis.service.storage.DirectoryStructureService
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * ContextAgent - Internal agent providing mandatory context.
 *
 * Role:
 * - Load essential info: clientId, projectId, correlationId
 * - Load project path and build/test commands
 * - Query GraphDB for knownFacts
 * - Identify missingInfo
 *
 * Used by Orchestrator at the start of every run.
 * Ensures LLM never "forgets" context.
 *
 * Implementation: Pure Koog agent wrapped as Tool via ContextAgentTool.
 */
@Component
class ContextAgent(
    private val promptExecutorFactory: KoogPromptExecutorFactory,
    private val smartModelSelector: SmartModelSelector,
    private val graphDBService: GraphDBService,
    private val projectService: ProjectService,
    private val directoryStructureService: DirectoryStructureService,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    /**
     * Create context agent instance.
     * Agent returns ContextPack directly - Koog serializes automatically.
     */
    suspend fun create(task: TaskDocument): AIAgent<String, ContextPack> {
        val promptExecutor = promptExecutorFactory.getExecutor("OLLAMA")

        val model =
            smartModelSelector.selectModel(
                baseModelName = SmartModelSelector.BaseModelTypeEnum.AGENT,
                inputContent = task.content,
            )

        val exampleContextPack =
            ContextPack(
                projectName = "Example project",
                workspacePath = "/mnt/shared/client/project/correlation",
                buildCommands = listOf("./gradlew build"),
                testCommands = listOf("./gradlew test"),
                environmentHints = "Kotlin/Gradle",
                knownFacts = listOf("project::example (Project)"),
                missingInfo = emptyList(),
            )

        val agentStrategy =
            strategy<String, ContextPack>("Context Gathering") {
                val nodeContextPack by
                    nodeLLMRequestStructured<ContextPack>(
                        examples = listOf(exampleContextPack),
                    ).transform { result ->
                        val structured =
                            result.getOrElse { e ->
                                throw IllegalStateException("ContextAgent: structured output parsing failed", e)
                            }
                        structured.data
                    }

                edge(nodeStart forwardTo nodeContextPack)
                edge(nodeContextPack forwardTo nodeFinish)
            }

        val agentConfig =
            AIAgentConfig(
                prompt =
                    Prompt.build("context-agent") {
                        system(
                            """
                            You are ContextAgent.

                            Goal: Output ONLY a structured ContextPack for the current run.

                            Rules:
                            - Use tools to gather real data (project build config, known facts, task metadata).
                            - Do NOT invent values. Do NOT assume defaults.
                            - If something is missing or unknown, keep the corresponding field empty (empty list / null) and add a clear item into missingInfo.
                            - Do not propose plans or perform any execution.
                            """.trimIndent(),
                        )
                    },
                model = model,
                maxAgentIterations = 1, // Single pass only
            )

        val toolRegistry =
            ToolRegistry {
                // Tools for context gathering
                tools(ContextGatheringTools(task, projectService, graphDBService, directoryStructureService))
            }

        return AIAgent(
            promptExecutor = promptExecutor,
            toolRegistry = toolRegistry,
            strategy = agentStrategy,
            agentConfig = agentConfig,
        )
    }

    /**
     * Run context agent.
     * Agent gathers context via tools and returns ContextPack directly.
     */
    suspend fun run(
        task: TaskDocument,
        userQuery: String,
    ): ContextPack {
        logger.info { "CONTEXT_AGENT_START | correlationId=${task.correlationId}" }

        val agent = create(task)
        val result = agent.run(userQuery)

        logger.debug { "CONTEXT_AGENT_COMPLETE | projectName=${result.projectName}" }

        return result
    }
}

/**
 * Tools for ContextAgent to gather context data.
 */
class ContextGatheringTools(
    private val task: TaskDocument,
    private val projectService: ProjectService,
    private val graphDBService: GraphDBService,
    private val directoryStructureService: DirectoryStructureService,
) : ToolSet {
    @Tool
    @LLMDescription("Get project build configuration (build/test commands)")
    fun getProjectBuildConfig(): Map<String, Any> {
        val projectId =
            task.projectId ?: return mapOf(
                "buildCommands" to emptyList<String>(),
                "testCommands" to emptyList<String>(),
                "workingDirectory" to ".",
                "missing" to listOf("projectId is null"),
            )

        val project =
            projectService.getProjectById(projectId)
                ?: throw IllegalStateException("Project not found for projectId=$projectId")

        return mapOf(
            "buildCommands" to (project.buildConfig?.buildCommands ?: emptyList<String>()),
            "testCommands" to (project.buildConfig?.testCommands ?: emptyList<String>()),
            "workingDirectory" to directoryStructureService.projectDir(project),
        )
    }

    @Tool
    @LLMDescription("Get known facts from GraphDB for this project")
    suspend fun getKnownFacts(): List<String> {
        val projectId = task.projectId ?: return emptyList()
        return try {
            graphDBService
                .getRelated(
                    clientId = task.clientId,
                    nodeKey = "project::$projectId",
                    edgeTypes = emptyList(),
                    direction = Direction.ANY,
                    limit = 10,
                ).map { "${it.key} (${it.entityType})" }
        } catch (e: Exception) {
            throw IllegalStateException("Failed to load GraphDB facts for projectId=$projectId", e)
        }
    }

    @Tool
    @LLMDescription("Get task metadata")
    fun getTaskMetadata(): Map<String, String> {
        val projectPath =
            task.projectId?.let { projectId ->
                val project =
                    projectService.getProjectById(projectId)
                        ?: throw IllegalStateException("Project not found for projectId=$projectId")
                directoryStructureService.projectDir(project).toString()
            } ?: ""

        return mapOf(
            "clientId" to task.clientId.toString(),
            "projectId" to (task.projectId?.toString() ?: "none"),
            "correlationId" to task.correlationId,
            "projectPath" to projectPath,
        )
    }
}
