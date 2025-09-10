package com.jervis.entity.mongo

import com.jervis.configuration.prompts.FinalProcessingConfig
import com.jervis.configuration.prompts.McpToolType
import com.jervis.configuration.prompts.ModelParams
import com.jervis.configuration.prompts.UserInteractionPrompts
import com.jervis.domain.model.ModelType
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "prompts")
@CompoundIndexes(
    CompoundIndex(name = "tool_model_status_idx", def = "{'toolType': 1, 'modelType': 1, 'status': 1}"),
    CompoundIndex(name = "tool_status_priority_idx", def = "{'toolType': 1, 'status': 1, 'priority': -1}"),
)
data class PromptDocument(
    @Id val id: ObjectId = ObjectId.get(),
    @Indexed val toolType: McpToolType,
    val modelType: ModelType? = null,
    val version: String = "1.0.0",
    val systemPrompt: String? = null,
    val userPrompt: String? = null,
    val description: String? = null,
    val finalProcessing: FinalProcessingConfig? = null,
    val userInteractionPrompts: UserInteractionPrompts? = null,
    val modelParams: ModelParams = ModelParams(),
    val metadata: PromptMetadata = PromptMetadata(),
    val status: PromptStatus = PromptStatus.ACTIVE,
    val priority: Int = 0,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val createdBy: String? = null,
    val updatedBy: String? = null,
)

enum class PromptStatus {
    ACTIVE,
    DRAFT,
    DEPRECATED,
    ARCHIVED,
}

data class PromptMetadata(
    val tags: List<String> = emptyList(),
    val author: String? = null,
    val source: String? = null,
    val notes: String? = null,
    val category: String? = null,
)

data class CreatePromptRequest(
    val toolType: McpToolType,
    val modelType: ModelType? = null,
    val systemPrompt: String? = null,
    val userPrompt: String? = null,
    val description: String? = null,
    val finalProcessing: FinalProcessingConfig? = null,
    val userInteractionPrompts: UserInteractionPrompts? = null,
    val modelParams: ModelParams = ModelParams(),
    val metadata: PromptMetadata = PromptMetadata(),
    val priority: Int = 0,
)

data class UpdatePromptRequest(
    val systemPrompt: String? = null,
    val userPrompt: String? = null,
    val description: String? = null,
    val finalProcessing: FinalProcessingConfig? = null,
    val userInteractionPrompts: UserInteractionPrompts? = null,
    val modelParams: ModelParams = ModelParams(),
    val metadata: PromptMetadata = PromptMetadata(),
    val status: PromptStatus = PromptStatus.ACTIVE,
    val priority: Int = 0,
)
