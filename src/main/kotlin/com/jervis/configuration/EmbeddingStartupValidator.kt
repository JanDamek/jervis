package com.jervis.configuration

import com.jervis.domain.model.ModelProvider
import com.jervis.domain.model.ModelType
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

/**
 * Validates embedding configuration at startup.
 * Fails fast when Anthropic is used for embeddings since it has no embedding API.
 */
@Component
class EmbeddingStartupValidator(
    private val modelsProperties: ModelsProperties,
) {
    @PostConstruct
    fun validate() {
        listOf(ModelType.EMBEDDING_TEXT, ModelType.EMBEDDING_CODE).forEach { type ->
            val invalid = modelsProperties.models[type].orEmpty().any { it.provider == ModelProvider.ANTHROPIC }
            if (invalid) {
                throw IllegalStateException("Anthropic provider is not supported for embeddings. Please remove ANTHROPIC from models.$type in application.yml")
            }
        }
    }
}
