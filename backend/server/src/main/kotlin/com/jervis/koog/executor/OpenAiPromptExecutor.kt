package com.jervis.koog.executor

import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import com.jervis.configuration.properties.EndpointProperties
import org.springframework.stereotype.Component

@Component("OpenAiPromptExecutor")
class OpenAiPromptExecutor(
    private val endpointProperties: EndpointProperties,
) : PromptExecutor by simpleOpenAIExecutor(
    apiToken = endpointProperties.openai.apiKey,
)
