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
import com.jervis.koog.tools.KnowledgeStorageTools
import com.jervis.orchestrator.model.EvidencePack
import com.jervis.orchestrator.model.TaskDocument
import com.jervis.rag.KnowledgeService
import com.jervis.rag.internal.graphdb.GraphDBService
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * MemoryRetriever (Specialist 6.4)
 *
 * Responsibilities:
 * - Query LTM (RAG + GraphDB)
 * - Rerank and select top evidence
 */
@Component
class MemoryRetriever(
    private val promptExecutorFactory: KoogPromptExecutorFactory,
    private val smartModelSelector: SmartModelSelector,
    private val knowledgeService: KnowledgeService,
    private val graphDBService: GraphDBService,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    suspend fun create(task: TaskDocument): AIAgent<String, EvidencePack> {
        val promptExecutor = promptExecutorFactory.getExecutor("OLLAMA")

        val agentStrategy = strategy<String, EvidencePack>("Memory Retrieval") {
            val nodeRetrieve by nodeLLMRequestStructured<EvidencePack>().transform { it.getOrThrow().data }
            edge(nodeStart forwardTo nodeRetrieve)
            edge(nodeRetrieve forwardTo nodeFinish)
        }

        val model = smartModelSelector.selectModel(
            baseModelName = SmartModelSelector.BaseModelTypeEnum.AGENT,
            inputContent = task.content
        )

        // Convert orchestrator TaskDocument to entity TaskDocument for tools
        val entityTask = com.jervis.entity.TaskDocument(
            content = task.content,
            clientId = com.jervis.types.ClientId.fromString(task.clientId),
            projectId = task.projectId?.let { com.jervis.types.ProjectId.fromString(it) },
            correlationId = task.correlationId,
            sourceUrn = com.jervis.types.SourceUrn(task.sourceUrn),
            type = com.jervis.dto.TaskTypeEnum.valueOf(task.type)
        )

        val toolRegistry = ToolRegistry {
            tools(KnowledgeStorageTools(entityTask, knowledgeService, graphDBService))
        }

        val agentConfig = AIAgentConfig(
            prompt = Prompt.build("memory-retriever") {
                system("""
                    You are Memory Retriever for JERVIS Orchestrator.
                    Your goal is to extract the most relevant evidence from Long-Term Memory (RAG + GraphDB).
                    
                    Workflow:
                    1. Use semantic search tools for knowledge retrieval.
                    2. Expand context by exploring related entities in the knowledge graph.
                    3. Synthesize the results into an EvidencePack.
                    4. Keep extracts concise (150-400 tokens) but high-signal.
                    
                    Max 3-5 top sources per query.
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

    suspend fun run(task: TaskDocument, query: String): EvidencePack {
        val agent = create(task)
        return agent.run(query)
    }
}
