package com.jervis.koog.executor

import ai.koog.prompt.executor.clients.google.GoogleClientSettings
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.message.Message
import ai.koog.prompt.llm.LLModel
import com.jervis.configuration.properties.EndpointProperties
import com.jervis.koog.cost.CostTrackingService
import com.jervis.koog.cost.LlmPriceService
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.dsl.ModerationResult
import org.springframework.stereotype.Component

@Component("GooglePromptExecutor")
class GooglePromptExecutor(
    private val endpointProperties: EndpointProperties,
    private val costTrackingService: CostTrackingService,
    private val llmPriceService: LlmPriceService,
) : PromptExecutor {
    private val delegate: PromptExecutor =
        SingleLLMPromptExecutor(
            GoogleLLMClient(
                apiKey = endpointProperties.google.apiKey,
                settings =
                    GoogleClientSettings(
                        baseUrl = endpointProperties.google.baseUrl,
                        timeoutConfig = infiniteTimeoutConfig(),
                    ),
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
}
