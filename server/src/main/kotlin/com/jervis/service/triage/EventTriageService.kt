package com.jervis.service.triage

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.triage.dto.Decision
import com.jervis.service.triage.dto.EventEnvelope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Service

/**
 * Internal service that performs first-call event triage using the EVENT_TRIAGE prompt.
 * This service is not exposed via REST and is intended for use by in-process listeners.
 */
@Service
class EventTriageService(
    private val llmGateway: LlmGateway,
) {
    private val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
        }

    suspend fun decide(
        event: EventEnvelope,
        quick: Boolean = true,
    ): Decision {
        val eventJson = json.encodeToString(event)
        val parsed =
            llmGateway.callLlm(
                type = PromptTypeEnum.EVENT_TRIAGE,
                mappingValue = mapOf("event" to eventJson),
                quick = quick,
                responseSchema =
                    Decision(
                        classification =
                            Decision.Classification(
                                isActionable = false,
                                intent = "OTHER",
                                entities = emptyList(),
                                sensitivity = "LOW",
                                confidence = 0.0,
                            ),
                        routing = Decision.Routing(),
                        rag = Decision.Rag(needRag = false),
                        action = Decision.Action(mode = Decision.Action.Mode.STORE_ONLY),
                        indexing = Decision.Indexing(),
                    ),
            )
        return parsed.result
    }
}
