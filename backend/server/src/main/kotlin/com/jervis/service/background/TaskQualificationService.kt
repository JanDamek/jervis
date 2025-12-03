package com.jervis.service.background

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.configuration.prompts.ProviderCapabilitiesService
import com.jervis.configuration.properties.ModelsProperties
import com.jervis.domain.model.ModelProviderEnum
import com.jervis.domain.model.ModelTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.dto.PendingTaskStateEnum
import com.jervis.entity.PendingTaskDocument
import com.jervis.koog.qualifier.KoogQualifierAgent
import com.jervis.service.client.ClientService
import com.jervis.service.prompts.PromptRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * CPU-only qualification service.
 * Uses KoogQualifierAgent directly to qualify tasks.
 * Decides whether each task should be discarded or dispatched to GPU (main agent pipeline).
 */
@Service
class TaskQualificationService(
    private val pendingTaskService: PendingTaskService,
    private val koogQualifierAgent: KoogQualifierAgent,
    private val providerCapabilitiesService: ProviderCapabilitiesService,
    private val promptRepository: PromptRepository,
    private val modelsProperties: ModelsProperties,
    private val clientService: ClientService,
) {
    private val logger = KotlinLogging.logger {}
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Backpressure-aware processing of tasks awaiting qualification.
     *
     * Design guidelines (please keep in sync with docs):
     * - Kotlin first: use Flow + flatMapMerge for bounded concurrency; no Java-style list batching.
     * - Backpressure: the operator-level concurrency is derived from provider capabilities (OLLAMA_QUALIFIER),
     *   so thousands of tasks won't spawn thousands of coroutines; at most N will run concurrently.
     * - Uses KoogQualifierAgent directly with Plan context.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun processAllQualifications() {
        val capabilities = providerCapabilitiesService.getProviderCapabilities(ModelProviderEnum.OLLAMA_QUALIFIER)
        val effectiveConcurrency = (capabilities.maxConcurrentRequests).coerceAtLeast(1)

        pendingTaskService
            .findTasksForQualification()
            .buffer(effectiveConcurrency)
            .flatMapMerge(concurrency = effectiveConcurrency) { task ->
                flow {
                    runCatching { processOne(task) }
                        .onFailure { e ->
                            logger.error(e) { "QUALIFICATION_ERROR: task=${task.id} type=${task.type} msg=${e.message}" }
                        }
                    emit(Unit)
                }
            }.catch { e ->
                logger.error(e) { "Qualification stream failure: ${e.message}" }
            }.collect()

        logger.info { "Qualification: stream cycle complete" }
    }

    private suspend fun processOne(original: PendingTaskDocument) {
        val task = pendingTaskService.tryClaimForQualification(original.id)

        if (task.state != PendingTaskStateEnum.QUALIFYING) {
            logger.debug { "QUALIFICATION_SKIP: id=${task.id} state=${task.state}" }
            return
        }

        val promptType = task.type.promptType

        // Get client document for Plan
        val clientDocument = clientService.getClientById(task.clientId)
            ?: throw IllegalStateException("Client not found: ${task.clientId}")

        // Build Plan context for KoogQualifierAgent
        val plan = Plan(
            id = ObjectId(),
            taskInstruction = task.content,
            originalLanguage = "en",
            englishInstruction = task.content,
            clientDocument = clientDocument,
            projectDocument = null,
            correlationId = task.correlationId,
            backgroundMode = true,
        )

        // Get system prompt from prompts.yaml
        val systemPrompt = promptRepository.getPrompt(promptType).systemPrompt

        // Build user prompt with task content
        val userPrompt = buildUserPrompt(task)

        // Get model name from models configuration for OLLAMA_QUALIFIER
        val qualifierModels = modelsProperties.models[ModelTypeEnum.QUALIFIER]
            ?: throw IllegalStateException("No QUALIFIER configuration found")

        val qualifierModel = qualifierModels.firstOrNull { it.provider == ModelProviderEnum.OLLAMA_QUALIFIER }
            ?: throw IllegalStateException("No OLLAMA_QUALIFIER model found in QUALIFIER configuration")

        val modelName = qualifierModel.model

        // Call KoogQualifierAgent
        val response = koogQualifierAgent.run(
            plan = plan,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            modelName = modelName,
        )

        // Parse response
        when (promptType) {
            PromptTypeEnum.LINK_QUALIFIER -> handleLinkQualifierResponse(task, response.answer)
            else -> handleGenericQualifierResponse(task, response.answer)
        }
    }

    private fun buildUserPrompt(task: PendingTaskDocument): String {
        return buildString {
            appendLine("Content:")
            appendLine(task.content)
            appendLine()
            appendLine("ClientId: ${task.clientId.toHexString()}")
            task.projectId?.let {
                appendLine("ProjectId: ${it.toHexString()}")
            }
            appendLine("CorrelationId: ${task.correlationId}")
            appendLine()
            appendLine("Decision (JSON): {\"discard\": true/false, \"reason\": \"...\"}")
        }
    }

    private suspend fun handleGenericQualifierResponse(
        task: PendingTaskDocument,
        answer: String,
    ) {
        val out = runCatching {
            // Try to parse JSON from the answer
            val jsonStart = answer.indexOf('{')
            val jsonEnd = answer.lastIndexOf('}')
            if (jsonStart != -1 && jsonEnd != -1) {
                val jsonStr = answer.substring(jsonStart, jsonEnd + 1)
                json.decodeFromString<GenericQualifierOut>(jsonStr)
            } else {
                // Fallback: check if answer contains "discard" keyword
                GenericQualifierOut(
                    discard = answer.contains("discard", ignoreCase = true),
                    reason = answer.take(200),
                )
            }
        }.getOrElse {
            logger.warn { "Failed to parse qualifier response, defaulting to DELEGATE: ${it.message}" }
            GenericQualifierOut(discard = false, reason = "Parse error: ${it.message}")
        }

        if (out.discard) {
            logger.info { "QUALIFICATION_DECISION: DISCARD id=${task.id} type=${task.type} reason='${out.reason ?: ""}'" }
            pendingTaskService.deleteTask(task.id)
        } else {
            logger.info { "QUALIFICATION_DECISION: DELEGATE id=${task.id} type=${task.type} reason='${out.reason ?: ""}'" }
            pendingTaskService.updateState(task.id, PendingTaskStateEnum.QUALIFYING, PendingTaskStateEnum.DISPATCHED_GPU)
        }
    }

    private suspend fun handleLinkQualifierResponse(
        task: PendingTaskDocument,
        answer: String,
    ) {
        val out = runCatching {
            val jsonStart = answer.indexOf('{')
            val jsonEnd = answer.lastIndexOf('}')
            if (jsonStart != -1 && jsonEnd != -1) {
                val jsonStr = answer.substring(jsonStart, jsonEnd + 1)
                json.decodeFromString<LinkQualifierOut>(jsonStr)
            } else {
                LinkQualifierOut(
                    discard = answer.contains("discard", ignoreCase = true),
                    reason = answer.take(200),
                )
            }
        }.getOrElse {
            logger.warn { "Failed to parse link qualifier response, defaulting to DELEGATE: ${it.message}" }
            LinkQualifierOut(discard = false, reason = "Parse error: ${it.message}")
        }

        if (out.discard) {
            logger.info {
                "QUALIFICATION_DECISION: DISCARD_LINK id=${task.id} regex='${out.suggestedRegex ?: ""}' descr='${out.patternDescription ?: ""}'"
            }
            pendingTaskService.deleteTask(task.id)
        } else {
            logger.info { "QUALIFICATION_DECISION: DELEGATE_LINK id=${task.id} reason='${out.reason ?: ""}'" }
            pendingTaskService.updateState(task.id, PendingTaskStateEnum.QUALIFYING, PendingTaskStateEnum.DISPATCHED_GPU)
        }
    }

    @Serializable
    data class GenericQualifierOut(
        val discard: Boolean = false,
        val reason: String? = null,
    )

    @Serializable
    data class LinkQualifierOut(
        val discard: Boolean = false,
        val reason: String? = null,
        val suggestedRegex: String? = null,
        val patternDescription: String? = null,
    )
}
