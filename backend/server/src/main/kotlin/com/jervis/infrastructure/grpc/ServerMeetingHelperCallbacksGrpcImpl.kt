package com.jervis.infrastructure.grpc

import com.jervis.contracts.server.HelperPushAck
import com.jervis.contracts.server.HelperPushRequest
import com.jervis.contracts.server.ServerMeetingHelperCallbacksServiceGrpcKt
import com.jervis.dto.meeting.HelperMessageDto
import com.jervis.dto.meeting.HelperMessageType
import com.jervis.meeting.MeetingHelperService
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class ServerMeetingHelperCallbacksGrpcImpl(
    private val helperService: MeetingHelperService,
) : ServerMeetingHelperCallbacksServiceGrpcKt.ServerMeetingHelperCallbacksServiceCoroutineImplBase() {
    private val logger = KotlinLogging.logger {}

    override suspend fun pushMessage(request: HelperPushRequest): HelperPushAck {
        return try {
            val message = HelperMessageDto(
                type = when (request.type) {
                    "translation" -> HelperMessageType.TRANSLATION
                    "suggestion" -> HelperMessageType.SUGGESTION
                    "question_predict" -> HelperMessageType.QUESTION_PREDICT
                    "visual_insight" -> HelperMessageType.VISUAL_INSIGHT
                    "whiteboard_ocr" -> HelperMessageType.WHITEBOARD_OCR
                    "screen_ocr" -> HelperMessageType.SCREEN_OCR
                    "transcript" -> HelperMessageType.TRANSCRIPT
                    else -> HelperMessageType.STATUS
                },
                text = request.text,
                context = request.context,
                fromLang = request.fromLang,
                toLang = request.toLang,
                timestamp = request.timestamp.ifBlank { Instant.now().toString() },
            )
            helperService.pushMessage(request.meetingId, message)
            HelperPushAck.newBuilder().setStatus("ok").build()
        } catch (e: Exception) {
            logger.warn(e) { "MEETING_HELPER_PUSH_ERROR meetingId=${request.meetingId}" }
            HelperPushAck.newBuilder()
                .setStatus("error")
                .setError(e.message.orEmpty())
                .build()
        }
    }
}
