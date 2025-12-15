package com.jervis.koog.executor

import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor
import com.jervis.configuration.properties.EndpointProperties
import org.springframework.stereotype.Component

@Component("GooglePromptExecutor")
class GooglePromptExecutor(
    private val endpointProperties: EndpointProperties,
) : PromptExecutor by simpleGoogleAIExecutor(
    apiKey = endpointProperties.google.apiKey,
)
