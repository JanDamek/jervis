package com.jervis.service.debug

import com.jervis.dto.events.DebugEventDto
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Service
import org.springframework.web.reactive.socket.WebSocketSession
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Simple debug service that sends debug events directly to connected WebSocket clients.
 * No Spring events, no Sink - direct send to WebSocket sessions.
 */
@Service
class DebugService {
    private val json = Json { encodeDefaults = true }
    private val sessions = CopyOnWriteArraySet<WebSocketSession>()

    fun addSession(session: WebSocketSession) {
        sessions.add(session)
    }

    fun removeSession(session: WebSocketSession) {
        sessions.remove(session)
    }

    fun sessionStarted(
        sessionId: String,
        promptType: String,
        systemPrompt: String,
        userPrompt: String,
    ) {
        val dto =
            DebugEventDto.SessionStarted(
                sessionId = sessionId,
                promptType = promptType,
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
            )
        broadcast(json.encodeToString(dto))
    }

    fun responseChunk(
        sessionId: String,
        chunk: String,
    ) {
        val dto =
            DebugEventDto.ResponseChunk(
                sessionId = sessionId,
                chunk = chunk,
            )
        broadcast(json.encodeToString(dto))
    }

    fun sessionCompleted(sessionId: String) {
        val dto =
            DebugEventDto.SessionCompleted(
                sessionId = sessionId,
            )
        broadcast(json.encodeToString(dto))
    }

    private fun broadcast(message: String) {
        sessions.forEach { session ->
            if (session.isOpen) {
                session
                    .send(
                        reactor.core.publisher.Mono
                            .just(session.textMessage(message)),
                    ).subscribe()
            }
        }
    }
}
