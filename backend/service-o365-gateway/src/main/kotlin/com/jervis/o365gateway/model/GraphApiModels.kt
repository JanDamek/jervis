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

// -- Session status from browser pool -----------------------------------------

@Serializable
data class BrowserPoolSessionStatus(
    @SerialName("client_id") val clientId: String,
    val state: String,
    @SerialName("last_activity") val lastActivity: String? = null,
    @SerialName("last_token_extract") val lastTokenExtract: String? = null,
    @SerialName("novnc_url") val novncUrl: String? = null,
)
