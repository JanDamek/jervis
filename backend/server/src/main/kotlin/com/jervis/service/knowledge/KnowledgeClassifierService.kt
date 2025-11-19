package com.jervis.service.knowledge

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.model.ModelTypeEnum
import com.jervis.domain.rag.KnowledgeSeverity
import com.jervis.domain.rag.KnowledgeType
import com.jervis.service.gateway.core.LlmGateway
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Service for classifying knowledge fragments using LLM.
 *
 * Analyzes text to determine:
 * - Type (RULE vs MEMORY)
 * - Severity (MUST, SHOULD, INFO)
 * - Tags (key concepts/topics)
 */
@Service
class KnowledgeClassifierService(
    private val llmGateway: LlmGateway,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Classify knowledge text into structured metadata.
     */
    suspend fun classifyKnowledge(
        text: String,
        correlationId: String,
    ): Result<KnowledgeClassification> =
        runCatching {
            logger.info { "Classifying knowledge text (${text.length} chars)" }

            val response =
                llmGateway.callLlm(
                    type = PromptTypeEnum.KNOWLEDGE_CLASSIFIER,
                    responseSchema = KnowledgeClassification(),
                    correlationId = correlationId,
                    quick = true, // Use fast model for classification
                    mappingValue = mapOf("text" to text),
                    backgroundMode = false,
                )

            val classification = response.result
            logger.info {
                "Knowledge classified: type=${classification.type}, " +
                    "severity=${classification.severity}, tags=${classification.tags}"
            }

            classification
        }

    @Serializable
    data class KnowledgeClassification(
        val type: String = "MEMORY", // "RULE" or "MEMORY"
        val severity: String = "INFO", // "MUST", "SHOULD", "INFO"
        val tags: List<String> = emptyList(),
        val reasoning: String = "", // Short explanation
    ) {
        fun toKnowledgeType(): KnowledgeType = KnowledgeType.valueOf(type)

        fun toKnowledgeSeverity(): KnowledgeSeverity = KnowledgeSeverity.valueOf(severity)
    }
}
