package com.jervis.dto.integration

import kotlinx.serialization.Serializable

/**
 * EPICs 11-12: Slack/Teams/Discord + Calendar Integration DTOs.
 *
 * Supports new connection capabilities, chat source indexing,
 * calendar integration, and availability awareness.
 */

// --- EPIC 11: Chat Platform Integration ---

/**
 * Chat platform providers.
 */
@Serializable
enum class ChatPlatform {
    SLACK, MICROSOFT_TEAMS, DISCORD,
}

/**
 * A message from an external chat platform.
 */
@Serializable
data class ExternalChatMessage(
    val id: String,
    val platform: ChatPlatform,
    val channelId: String,
    val channelName: String? = null,
    val threadId: String? = null,
    val authorId: String,
    val authorName: String,
    val content: String,
    val timestamp: String,
    val mentions: List<String> = emptyList(),
    val reactions: List<ChatReaction> = emptyList(),
    val isDirectMessage: Boolean = false,
)

@Serializable
data class ChatReaction(
    val emoji: String,
    val count: Int = 1,
    val users: List<String> = emptyList(),
)

/**
 * Chat reply request (goes through approval gate).
 */
@Serializable
data class ChatReplyRequest(
    val platform: ChatPlatform,
    val channelId: String,
    val threadId: String? = null,
    val content: String,
    val clientId: String,
)

// --- EPIC 12: Calendar Integration ---

/**
 * Calendar providers.
 */
@Serializable
enum class CalendarProvider {
    GOOGLE_CALENDAR, MICROSOFT_OUTLOOK, APPLE_CALENDAR,
}

/**
 * A calendar event.
 */
@Serializable
data class CalendarEvent(
    val id: String,
    val title: String,
    val startTime: String,
    val endTime: String,
    val location: String? = null,
    val description: String? = null,
    val attendees: List<String> = emptyList(),
    val isAllDay: Boolean = false,
    val isRecurring: Boolean = false,
    val provider: CalendarProvider,
)

/**
 * Availability information for scheduling.
 */
@Serializable
data class AvailabilityInfo(
    val date: String,
    val freeSlots: List<TimeSlot> = emptyList(),
    val busySlots: List<TimeSlot> = emptyList(),
    val isUserBusy: Boolean = false,
)

@Serializable
data class TimeSlot(
    val start: String,
    val end: String,
    val label: String? = null,
)

/**
 * Create calendar event request (goes through approval gate).
 */
@Serializable
data class CreateCalendarEventRequest(
    val title: String,
    val startTime: String,
    val endTime: String,
    val description: String? = null,
    val attendees: List<String> = emptyList(),
    val provider: CalendarProvider = CalendarProvider.GOOGLE_CALENDAR,
    val clientId: String,
)
