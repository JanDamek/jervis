package com.jervis.koog.executor

import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.llms.all.simpleAnthropicExecutor
import com.jervis.configuration.properties.EndpointProperties
import org.springframework.stereotype.Component

@Component("AnthropicPromptExecutor")
class AnthropicPromptExecutor(
    private val endpointProperties: EndpointProperties,
) : PromptExecutor by simpleAnthropicExecutor(
    apiKey = endpointProperties.anthropic.apiKey,
)
