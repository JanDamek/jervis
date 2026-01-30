package com.jervis.koog.executor

import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.message.Message
import ai.koog.prompt.llm.LLModel
import com.jervis.configuration.properties.EndpointProperties
import org.springframework.stereotype.Component
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.dsl.ModerationResult

/**
 * Ollama Qualifier Prompt Executor.
 *
 * IMPORTANT: Koog's simpleOllamaAIExecutor() does NOT support num_ctx parameter!
 * Ollama will use model defaults (usually 4096), causing truncation for large prompts.
 *
 * SOLUTION: Configure num_ctx in Ollama Modelfile.
 *
 * CRITICAL: Context window = INPUT + OUTPUT combined!
 * Example: If you send 45k tokens input and expect 50k tokens output = 95k tokens total needed.
 * Always set num_ctx with safety margin (e.g., 120k for 95k expected usage).
 *
 * ```modelfile
 * FROM qwen2.5:32b
 * PARAMETER num_ctx 120000  # 120k context (enough for ~50k input + ~50k output + margin)
 * PARAMETER temperature 0.0
 * ```
 *
 * Create custom model:
 * ```bash
 * ollama create qwen2.5-qualifier -f ./Modelfile
 * ```
 *
 * Configuration in models-config.yaml:
 * ```yaml
 * providers:
 *   - name: OLLAMA_QUALIFIER
 *     models:
 *       - modelId: qwen2.5-qualifier  # Custom model with num_ctx=120000
 *         contextLength: 120000  # MUST match Modelfile num_ctx
 * ```
 *
 * If contextLength in AIAgentConfig differs from Modelfile num_ctx:
 * - Ollama uses Modelfile num_ctx (actual limit)
 * - Koog uses contextLength (tracking only)
 * - ALWAYS keep them in sync!
 *
 * See docs/koog-libraries.md - "Prompt Configuration & Context Window (CRITICAL)"
 */
@Component("OllamaQualifierPromptExecutor")
class OllamaQualifierPromptExecutor(
    private val endpointProperties: EndpointProperties,
) : PromptExecutor {
    private val delegate: PromptExecutor =
        SingleLLMPromptExecutor(
            OllamaClient(
                baseUrl = endpointProperties.ollama.qualifier.baseUrl.removeSuffix("/"),
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
        logger.info {
            "OllamaQualifierPromptExecutor initialized | baseUrl=${endpointProperties.ollama.qualifier.baseUrl}"
        }
    }
}
