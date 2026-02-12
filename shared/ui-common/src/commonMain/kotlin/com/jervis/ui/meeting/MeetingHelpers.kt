package com.jervis.ui.meeting

import com.jervis.dto.meeting.MeetingStateEnum
import com.jervis.dto.meeting.MeetingTypeEnum

/** Format ISO 8601 startedAt to "YYYY-MM-DD HH:MM" */
internal fun formatDateTime(isoString: String): String =
    isoString.take(16).replace('T', ' ')

internal fun stateIcon(state: MeetingStateEnum): String =
    when (state) {
        MeetingStateEnum.RECORDING -> "\uD83D\uDD34"
        MeetingStateEnum.UPLOADING -> "\u2B06"
        MeetingStateEnum.UPLOADED -> "\u231B"
        MeetingStateEnum.TRANSCRIBING -> "\uD83C\uDFA4"
        MeetingStateEnum.TRANSCRIBED -> "\u23F3"
        MeetingStateEnum.CORRECTING -> "\u270D"
        MeetingStateEnum.CORRECTION_REVIEW -> "\u2753"
        MeetingStateEnum.CORRECTED -> "\u23F3"
        MeetingStateEnum.INDEXED -> "\u2705"
        MeetingStateEnum.FAILED -> "\u274C"
    }

internal fun stateLabel(state: MeetingStateEnum): String =
    when (state) {
        MeetingStateEnum.RECORDING -> "Nahrává se"
        MeetingStateEnum.UPLOADING -> "Odesílá se"
        MeetingStateEnum.UPLOADED -> "Čeká na přepis"
        MeetingStateEnum.TRANSCRIBING -> "Přepisuje se"
        MeetingStateEnum.TRANSCRIBED -> "Čeká na korekci"
        MeetingStateEnum.CORRECTING -> "Opravuje se"
        MeetingStateEnum.CORRECTION_REVIEW -> "Čeká na odpověď"
        MeetingStateEnum.CORRECTED -> "Čeká na indexaci"
        MeetingStateEnum.INDEXED -> "Hotovo"
        MeetingStateEnum.FAILED -> "Chyba"
    }

internal fun meetingTypeLabel(type: MeetingTypeEnum): String =
    when (type) {
        MeetingTypeEnum.MEETING -> "Schůzka"
        MeetingTypeEnum.TASK_DISCUSSION -> "Diskuse úkolů"
        MeetingTypeEnum.STANDUP_PROJECT -> "Standup projekt"
        MeetingTypeEnum.STANDUP_TEAM -> "Standup tým"
        MeetingTypeEnum.INTERVIEW -> "Pohovor"
        MeetingTypeEnum.WORKSHOP -> "Workshop"
        MeetingTypeEnum.REVIEW -> "Review"
        MeetingTypeEnum.OTHER -> "Jiné"
    }

/** Format ISO timestamp to short "HH:MM" for chat bubbles. */
internal fun formatChatTimestamp(isoString: String): String {
    // ISO 8601: "2025-01-15T10:30:45.123Z" -> "10:30"
    val timeStart = isoString.indexOf('T')
    if (timeStart < 0) return ""
    return isoString.substring(timeStart + 1).take(5)
}
