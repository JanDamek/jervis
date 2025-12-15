package com.jervis.koog.executor

import ai.koog.prompt.executor.llms.all.simpleOllamaAIExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import com.jervis.configuration.properties.EndpointProperties
import org.springframework.stereotype.Component

@Component
class OllamaPromptExecutor(
    private val endpointProperties: EndpointProperties,
) : PromptExecutor by simpleOllamaAIExecutor(
        baseUrl =
            endpointProperties.ollama.primary.baseUrl
                .removeSuffix("/"),
    )
