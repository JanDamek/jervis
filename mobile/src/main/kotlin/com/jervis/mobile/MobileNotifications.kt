package com.jervis.mobile

import com.jervis.client.NotificationsWebSocketClient
import com.jervis.dto.events.AgentResponseEventDto
import com.jervis.dto.events.ErrorNotificationEventDto
import com.jervis.dto.events.JiraAuthPromptEventDto
import com.jervis.dto.events.PlanStatusChangeEventDto
import com.jervis.dto.events.StepCompletionEventDto
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.ApplicationEventPublisherAware
import org.springframework.context.PayloadApplicationEvent

/**
 * Mobile wrapper over NotificationsWebSocketClient that exposes events as Flows.
 * Online-only notifications: active only when [start] is called; no offline delivery handled here.
 */
class MobileNotifications(
    private val baseUrl: String,
) : ApplicationEventPublisherAware {

    private lateinit var publisher: ApplicationEventPublisher

    private val client: NotificationsWebSocketClient by lazy {
        NotificationsWebSocketClient(baseUrl, object : ApplicationEventPublisher {
            override fun publishEvent(event: Any) {
                // Bridge Spring events to Flows for mobile consumers
                when (event) {
                    is AgentResponseEventDto -> _agentResponses.tryEmit(event)
                    is StepCompletionEventDto -> _stepCompletions.tryEmit(event)
                    is PlanStatusChangeEventDto -> _planStatus.tryEmit(event)
                    is JiraAuthPromptEventDto -> _jiraPrompts.tryEmit(event)
                    is ErrorNotificationEventDto -> _errors.tryEmit(event)
                }
                // still publish to spring if someone is listening (optional)
                publisher.publishEvent(PayloadApplicationEvent(this, event))
            }
        })
    }

    // Flows
    private val _agentResponses = MutableSharedFlow<AgentResponseEventDto>(replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val agentResponses: SharedFlow<AgentResponseEventDto> = _agentResponses

    private val _stepCompletions = MutableSharedFlow<StepCompletionEventDto>(replay = 0, extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val stepCompletions: SharedFlow<StepCompletionEventDto> = _stepCompletions

    private val _planStatus = MutableSharedFlow<PlanStatusChangeEventDto>(replay = 0, extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val planStatus: SharedFlow<PlanStatusChangeEventDto> = _planStatus

    private val _jiraPrompts = MutableSharedFlow<JiraAuthPromptEventDto>(replay = 0, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val jiraPrompts: SharedFlow<JiraAuthPromptEventDto> = _jiraPrompts

    private val _errors = MutableSharedFlow<ErrorNotificationEventDto>(replay = 0, extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val errors: SharedFlow<ErrorNotificationEventDto> = _errors

    val sessionId: String get() = client.sessionId

    fun start() = client.start()

    fun stop() = client.stop()

    override fun setApplicationEventPublisher(applicationEventPublisher: ApplicationEventPublisher) {
        this.publisher = applicationEventPublisher
    }
}
