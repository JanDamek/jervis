package com.jervis.koog.executor

import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.message.Message
import ai.koog.prompt.llm.LLModel
import com.jervis.configuration.properties.EndpointProperties
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.dsl.ModerationResult
import org.springframework.stereotype.Component

@Component
class OllamaPromptExecutor(
    private val endpointProperties: EndpointProperties,
) : PromptExecutor {
    private val delegate: PromptExecutor =
        SingleLLMPromptExecutor(
            OllamaClient(
                baseUrl = endpointProperties.ollama.primary.baseUrl.removeSuffix("/"),
                timeoutConfig = infiniteTimeoutConfig(),
            ),
        )

    override suspend fun execute(
        prompt: ai.koog.prompt.dsl.Prompt,
        model: LLModel,
        tools: List<ai.koog.agents.core.tools.ToolDescriptor>
    ): List<Message.Response> =
        delegate.execute(prompt, model, tools)

    override fun executeStreaming(
        prompt: ai.koog.prompt.dsl.Prompt,
        model: LLModel,
        tools: List<ai.koog.agents.core.tools.ToolDescriptor>
    ): kotlinx.coroutines.flow.Flow<ai.koog.prompt.streaming.StreamFrame> =
        delegate.executeStreaming(prompt, model, tools)

    override suspend fun moderate(prompt: ai.koog.prompt.dsl.Prompt, model: LLModel): ai.koog.prompt.dsl.ModerationResult =
        delegate.moderate(prompt, model)

    override fun close() {
        delegate.close()
    }

    private val logger = mu.KotlinLogging.logger {}

    init {
        logger.info { "OllamaPromptExecutor initialized | baseUrl=${endpointProperties.ollama.primary.baseUrl}" }
    }
}
