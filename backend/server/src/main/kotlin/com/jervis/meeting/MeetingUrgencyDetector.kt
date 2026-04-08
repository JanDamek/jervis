package com.jervis.meeting

import com.jervis.dto.meeting.HelperMessageDto
import com.jervis.dto.meeting.HelperMessageType
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Live urgency detector for meeting transcription streams.
 *
 * Hooks into the Whisper segment-progress callback while a meeting is being
 * recorded (read-only desktop loopback or attender pod) and watches each new
 * segment of recognised speech for signals that the user is being addressed,
 * asked a question, or asked to make a decision. When such a signal fires it
 * pushes a `HelperMessageDto` (type `SUGGESTION` or `QUESTION_PREDICT`) into
 * `MeetingHelperService`, which propagates it to the user device via the
 * existing `JervisEvent.MeetingHelperMessage` event stream and any
 * registered helper WebSocket sessions.
 *
 * The detector is intentionally **regex-only**. It runs on the segment hot
 * path and must add zero noticeable latency. Anything that needs an LLM
 * (semantic intent, summarisation) is the job of the offline correction /
 * orchestrator pipeline that runs after the meeting ends. The goal here is
 * "tap the user on the shoulder when their name was just said", not
 * "understand the meeting".
 *
 * ## Read-only v1 invariants
 *
 * - The detector NEVER replies into the meeting chat. It only emits a
 *   helper message to the **user's** device.
 * - The detector does not store transcripts. The full transcript pipeline
 *   already persists segments via `MeetingDocument`.
 * - Per-meeting cooldown prevents notification flooding when a single
 *   utterance contains multiple matches or the same trigger repeats within
 *   a short window.
 */
@Component
class MeetingUrgencyDetector(
    private val helperService: MeetingHelperService,
) {
    /** meetingId → epoch-millis of the last urgency push. */
    private val lastEmitAtMs = ConcurrentHashMap<String, Long>()

    /**
     * Inspect one freshly-transcribed segment of meeting speech.
     *
     * Called from `WhisperTranscriptionClient.buildProgressCallback` after the
     * existing `emitMeetingTranscriptionProgress` notification, so a per-call
     * failure here can never block the main progress stream — wrap in
     * `runCatching` at the call site.
     *
     * @param meetingId  the meeting whose audio produced this segment
     * @param clientId   the meeting's owning client (used for routing)
     * @param segmentText the latest recognised text — may be `null` or blank
     *                   between active speech, in which case we no-op
     */
    suspend fun analyzeSegment(
        meetingId: String,
        clientId: String,
        segmentText: String?,
    ) {
        val text = segmentText?.trim().orEmpty()
        if (text.isBlank()) return

        val match = detect(text) ?: return

        val nowMs = System.currentTimeMillis()
        val previous = lastEmitAtMs[meetingId]
        if (previous != null && nowMs - previous < COOLDOWN_MS) {
            logger.debug { "MeetingUrgencyDetector: cooldown active for meeting=$meetingId" }
            return
        }
        lastEmitAtMs[meetingId] = nowMs

        val (helperType, prefix) = when (match.kind) {
            UrgencyKind.NAME_MENTION -> HelperMessageType.SUGGESTION to "Pozor — bylo zmíněno tvé jméno"
            UrgencyKind.DIRECT_QUESTION -> HelperMessageType.QUESTION_PREDICT to "Otázka směrem k tobě"
            UrgencyKind.DECISION_REQUIRED -> HelperMessageType.SUGGESTION to "Žádost o rozhodnutí"
        }

        helperService.pushMessage(
            meetingId = meetingId,
            message = HelperMessageDto(
                type = helperType,
                text = "$prefix: \"${text.take(MAX_QUOTE_LENGTH)}\"",
                timestamp = Instant.now().toString(),
            ),
        )

        logger.info {
            "MeetingUrgencyDetector: emitted ${match.kind} for meeting=$meetingId client=$clientId"
        }
    }

    /** Drop per-meeting state after a meeting ends. */
    fun forgetMeeting(meetingId: String) {
        lastEmitAtMs.remove(meetingId)
    }

    // ---- Detection logic ---------------------------------------------------

    private data class UrgencyMatch(val kind: UrgencyKind)

    private enum class UrgencyKind { NAME_MENTION, DIRECT_QUESTION, DECISION_REQUIRED }

    private fun detect(text: String): UrgencyMatch? {
        val lower = text.lowercase()

        // 1) Direct mention of Jervis or the user's first name placeholder.
        // The user-name list is intentionally kept here as a fallback; the
        // proper source of truth is preferences (preference key
        // `meeting.urgency.names` — wired up later).
        if (NAME_PATTERN.containsMatchIn(lower)) {
            return UrgencyMatch(UrgencyKind.NAME_MENTION)
        }

        // 2) Direct question — sentence ends with `?` AND contains a 2nd-person
        //    Czech / English verb form. Avoids matching rhetorical questions.
        if (text.contains('?') && SECOND_PERSON_PATTERN.containsMatchIn(lower)) {
            return UrgencyMatch(UrgencyKind.DIRECT_QUESTION)
        }

        // 3) Decision-required keywords (Czech-first since the project's UI
        //    language is Czech).
        if (DECISION_PATTERN.containsMatchIn(lower)) {
            return UrgencyMatch(UrgencyKind.DECISION_REQUIRED)
        }

        return null
    }

    companion object {
        private const val COOLDOWN_MS = 30_000L
        private const val MAX_QUOTE_LENGTH = 160

        private val NAME_PATTERN = Regex(
            pattern = "(?:^|\\W)(jervis|@jervis|jandamek|jan damek)(?:\\W|\$)",
            options = setOf(RegexOption.IGNORE_CASE),
        )

        // Czech 2nd person verb endings (-š, -te, -íš, -ete) plus English "you".
        private val SECOND_PERSON_PATTERN = Regex(
            pattern = "(?:\\byou\\b|\\b\\w+(?:íš|ete|íte|eš|áš)\\b|\\bmůžeš\\b|\\bmůžete\\b)",
            options = setOf(RegexOption.IGNORE_CASE),
        )

        private val DECISION_PATTERN = Regex(
            pattern = "(?:musíme rozhodnout|potřebujeme rozhodnut|schválíš|schválíte|schval[íi][šs]" +
                "|need (?:your )?(?:approval|decision)|approve this|sign[- ]?off)",
            options = setOf(RegexOption.IGNORE_CASE),
        )
    }
}
