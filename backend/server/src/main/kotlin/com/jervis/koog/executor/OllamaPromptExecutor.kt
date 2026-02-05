package com.jervis.koog.executor

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import com.jervis.configuration.properties.EndpointProperties
import kotlinx.coroutines.flow.Flow
import org.springframework.stereotype.Component

@Component
class OllamaPromptExecutor(
    private val endpointProperties: EndpointProperties,
) : PromptExecutor {
    private val delegate: PromptExecutor =
        SingleLLMPromptExecutor(
            OllamaClient(
                baseUrl =
                    endpointProperties.ollama.primary.baseUrl
                        .removeSuffix("/"),
                timeoutConfig = infiniteTimeoutConfig(),
            ),
        )

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): List<Message.Response> = delegate.execute(prompt, model, tools)

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> = delegate.executeStreaming(prompt, model, tools)

    override suspend fun moderate(
        prompt: Prompt,
        model: LLModel,
    ): ModerationResult = delegate.moderate(prompt, model)

    override fun close() {
        delegate.close()
    }

    private val logger = mu.KotlinLogging.logger {}

    init {
        logger.info { "OllamaPromptExecutor initialized | baseUrl=${endpointProperties.ollama.primary.baseUrl}" }
    }
}
