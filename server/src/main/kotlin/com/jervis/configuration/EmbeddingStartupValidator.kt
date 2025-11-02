package com.jervis.configuration

import com.jervis.configuration.properties.ModelsProperties
import com.jervis.domain.model.ModelProviderEnum
import com.jervis.domain.model.ModelTypeEnum
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
        listOf(ModelTypeEnum.EMBEDDING_TEXT, ModelTypeEnum.EMBEDDING_CODE).forEach { type ->
            val invalid = modelsProperties.models[type].orEmpty().any { it.provider == ModelProviderEnum.ANTHROPIC }
            check(
                !invalid,
            ) { "Anthropic provider is not supported for embeddings. Please remove ANTHROPIC from models.$type in application.yml" }
        }
    }
}
