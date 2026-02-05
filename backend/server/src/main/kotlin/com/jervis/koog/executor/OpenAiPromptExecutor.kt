package com.jervis.koog.executor

import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import com.jervis.configuration.properties.EndpointProperties
import org.springframework.stereotype.Component

@Component("OpenAiPromptExecutor")
class OpenAiPromptExecutor(
    endpointProperties: EndpointProperties,
) : PromptExecutor {
    private val delegate: PromptExecutor =
        SingleLLMPromptExecutor(
            OpenAILLMClient(
                apiKey = endpointProperties.openai.apiKey,
                settings =
                    OpenAIClientSettings(
                        baseUrl = endpointProperties.openai.baseUrl,
                        timeoutConfig = infiniteTimeoutConfig(),
                    ),
            ),
        )

    override suspend fun execute(
        prompt: ai.koog.prompt.dsl.Prompt,
        model: LLModel,
        tools: List<ai.koog.agents.core.tools.ToolDescriptor>,
    ): List<Message.Response> = delegate.execute(prompt, model, tools)

    override fun executeStreaming(
        prompt: ai.koog.prompt.dsl.Prompt,
        model: LLModel,
        tools: List<ai.koog.agents.core.tools.ToolDescriptor>,
    ): kotlinx.coroutines.flow.Flow<ai.koog.prompt.streaming.StreamFrame> = delegate.executeStreaming(prompt, model, tools)

    override suspend fun moderate(
        prompt: ai.koog.prompt.dsl.Prompt,
        model: LLModel,
    ): ai.koog.prompt.dsl.ModerationResult = delegate.moderate(prompt, model)

    override fun close() {
        delegate.close()
    }
}
