package com.jervis.o365gateway.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// -- Token response from browser pool -----------------------------------------

@Serializable
data class BrowserPoolTokenResponse(
    val token: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("age_seconds") val ageSeconds: Int,
)

// -- Graph API generic wrapper ------------------------------------------------

@Serializable
data class GraphListResponse<T>(
    @SerialName("@odata.count") val count: Int? = null,
    @SerialName("@odata.nextLink") val nextLink: String? = null,
    val value: List<T>,
)

// -- Teams Chat ---------------------------------------------------------------

@Serializable
data class GraphChat(
    val id: String,
    val topic: String? = null,
    val chatType: String? = null,
    val createdDateTime: String? = null,
    val lastUpdatedDateTime: String? = null,
    val lastMessagePreview: GraphMessagePreview? = null,
)

@Serializable
data class GraphMessagePreview(
    val id: String? = null,
    val createdDateTime: String? = null,
    val body: GraphMessageBody? = null,
    val from: GraphMessageFrom? = null,
)

// -- Teams Message ------------------------------------------------------------

@Serializable
data class GraphMessage(
    val id: String,
    val createdDateTime: String? = null,
    val lastModifiedDateTime: String? = null,
    val body: GraphMessageBody? = null,
    val from: GraphMessageFrom? = null,
    val messageType: String? = null,
)

@Serializable
data class GraphMessageBody(
    val contentType: String? = null,
    val content: String? = null,
)

@Serializable
data class GraphMessageFrom(
    val user: GraphUser? = null,
    val application: GraphApplication? = null,
)

@Serializable
data class GraphUser(
    val id: String? = null,
    val displayName: String? = null,
)

@Serializable
data class GraphApplication(
    val id: String? = null,
    val displayName: String? = null,
)

// -- Teams / Channels ---------------------------------------------------------

@Serializable
data class GraphTeam(
    val id: String,
    val displayName: String? = null,
    val description: String? = null,
)

@Serializable
data class GraphChannel(
    val id: String,
    val displayName: String? = null,
    val description: String? = null,
    val membershipType: String? = null,
)

// -- Send message request body ------------------------------------------------

@Serializable
data class SendMessageRequest(
    val body: GraphMessageBody,
)

// -- Mail (Outlook) -----------------------------------------------------------

@Serializable
data class GraphMailMessage(
    val id: String,
    val subject: String? = null,
    val bodyPreview: String? = null,
    val body: GraphMailBody? = null,
    val from: GraphEmailAddress? = null,
    val toRecipients: List<GraphRecipient>? = null,
    val ccRecipients: List<GraphRecipient>? = null,
    val receivedDateTime: String? = null,
    val sentDateTime: String? = null,
    val isRead: Boolean? = null,
    val isDraft: Boolean? = null,
    val hasAttachments: Boolean? = null,
    val importance: String? = null,
    val conversationId: String? = null,
)

@Serializable
data class GraphMailBody(
    val contentType: String? = null,
    val content: String? = null,
)

@Serializable
data class GraphEmailAddress(
    val emailAddress: GraphEmailAddressDetail? = null,
)

@Serializable
data class GraphEmailAddressDetail(
    val name: String? = null,
    val address: String? = null,
)

@Serializable
data class GraphRecipient(
    val emailAddress: GraphEmailAddressDetail? = null,
)

@Serializable
data class SendMailRequest(
    val message: SendMailMessage,
    val saveToSentItems: Boolean = true,
)

@Serializable
data class SendMailMessage(
    val subject: String,
    val body: GraphMailBody,
    val toRecipients: List<GraphRecipient>,
    val ccRecipients: List<GraphRecipient> = emptyList(),
)

// -- Calendar -----------------------------------------------------------------

@Serializable
data class GraphEvent(
    val id: String? = null,
    val subject: String? = null,
    val body: GraphMailBody? = null,
    val start: GraphDateTimeTimeZone? = null,
    val end: GraphDateTimeTimeZone? = null,
    val location: GraphLocation? = null,
    val organizer: GraphEmailAddress? = null,
    val attendees: List<GraphAttendee>? = null,
    val isAllDay: Boolean? = null,
    val isCancelled: Boolean? = null,
    val isOnlineMeeting: Boolean? = null,
    val onlineMeetingUrl: String? = null,
    val recurrence: GraphRecurrence? = null,
    val showAs: String? = null,
    val webLink: String? = null,
)

@Serializable
data class GraphDateTimeTimeZone(
    val dateTime: String? = null,
    val timeZone: String? = null,
)

@Serializable
data class GraphLocation(
    val displayName: String? = null,
)

@Serializable
data class GraphAttendee(
    val emailAddress: GraphEmailAddressDetail? = null,
    val type: String? = null,
    val status: GraphAttendeeStatus? = null,
)

@Serializable
data class GraphAttendeeStatus(
    val response: String? = null,
    val time: String? = null,
)

@Serializable
data class GraphRecurrence(
    val pattern: GraphRecurrencePattern? = null,
    val range: GraphRecurrenceRange? = null,
)

@Serializable
data class GraphRecurrencePattern(
    val type: String? = null,
    val interval: Int? = null,
    val daysOfWeek: List<String>? = null,
)

@Serializable
data class GraphRecurrenceRange(
    val type: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
)

@Serializable
data class CreateEventRequest(
    val subject: String,
    val body: GraphMailBody? = null,
    val start: GraphDateTimeTimeZone,
    val end: GraphDateTimeTimeZone,
    val location: GraphLocation? = null,
    val attendees: List<GraphAttendee>? = null,
    val isOnlineMeeting: Boolean = false,
)

// -- OneDrive / SharePoint ----------------------------------------------------

@Serializable
data class GraphDriveItem(
    val id: String,
    val name: String? = null,
    val size: Long? = null,
    val createdDateTime: String? = null,
    val lastModifiedDateTime: String? = null,
    val webUrl: String? = null,
    val file: GraphFileInfo? = null,
    val folder: GraphFolderInfo? = null,
    @SerialName("@microsoft.graph.downloadUrl") val downloadUrl: String? = null,
    val parentReference: GraphItemReference? = null,
)

@Serializable
data class GraphFileInfo(
    val mimeType: String? = null,
    val hashes: GraphHashes? = null,
)

@Serializable
data class GraphHashes(
    val sha256Hash: String? = null,
)

@Serializable
data class GraphFolderInfo(
    val childCount: Int? = null,
)

@Serializable
data class GraphItemReference(
    val driveId: String? = null,
    val id: String? = null,
    val path: String? = null,
)

@Serializable
data class GraphSearchResult(
    val id: String? = null,
    val name: String? = null,
    val webUrl: String? = null,
    val size: Long? = null,
    val lastModifiedDateTime: String? = null,
    val file: GraphFileInfo? = null,
    val folder: GraphFolderInfo? = null,
    val parentReference: GraphItemReference? = null,
)

// -- Session status from browser pool -----------------------------------------

@Serializable
data class BrowserPoolSessionStatus(
    @SerialName("client_id") val clientId: String,
    val state: String,
    @SerialName("last_activity") val lastActivity: String? = null,
    @SerialName("last_token_extract") val lastTokenExtract: String? = null,
    @SerialName("novnc_url") val novncUrl: String? = null,
)
